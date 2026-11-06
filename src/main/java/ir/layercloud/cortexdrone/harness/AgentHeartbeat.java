package ir.layercloud.cortexdrone.harness;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watchdog for a Code Mode agent loop.
 *
 * <p>Detects the three ways a job silently dies:
 * <ul>
 *   <li>empty / failed LLM response,</li>
 *   <li>the model claims {@code done:true} while the physical job is still only a
 *       scan, is queued, or was never delivered,</li>
 *   <li>the loop exits (turn cap, deadline, exception) while the drone still has
 *       work, or before any progress was made.</li>
 * </ul>
 * In all of those cases it resurrects the job as a compacted continuation loop
 * (fresh small context, same goal, current drone state) instead of leaving the
 * drone parked in the middle of a task.
 */
final class AgentHeartbeat {
    private static final int MAX_CONTINUATIONS = 3;
    private static final long CONTINUATION_WINDOW_MS = 10L * 60L * 1000L;
    private static final long RESURRECT_DELAY_MS = 2500L;
    private static final Map<Integer, long[]> CONTINUATIONS = new ConcurrentHashMap<Integer, long[]>();

    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final DroneController ctrl;
    private final EntityPlayerMP player;
    private final String userPrompt;
    private final int loopId;

    private int lastTurn = 0;
    private String lastCode = "";
    private JsonObject lastExec = null;
    private boolean hadProgress = false;
    private int forcedDoneChecks = 0;
    private int emptyResponses = 0;
    private String lastReason = "";

    private AgentHeartbeat(AIDroneManager manager, DroneController ctrl, EntityPlayerMP player, String userPrompt, int loopId) {
        this.manager = manager;
        this.logger = manager.getLogger();
        this.ctrl = ctrl;
        this.player = player;
        this.userPrompt = userPrompt == null ? "" : userPrompt;
        this.loopId = loopId;
    }

    static AgentHeartbeat begin(AIDroneManager manager, DroneController ctrl, EntityPlayerMP player, String userPrompt, int loopId) {
        AgentHeartbeat hb = new AgentHeartbeat(manager, ctrl, player, userPrompt, loopId);
        hb.logger.log("HEARTBEAT", ctrl, "Loop " + loopId + " started", "prompt=\"" + LoopTracer.snippet(hb.userPrompt, 160) + "\"");
        return hb;
    }

    void observe(int turn, String code, JsonObject execRes) {
        this.lastTurn = turn;
        this.lastCode = code == null ? "" : code;
        if (execRes != null) this.lastExec = execRes;
        if (AgentLoopHeuristics.hasConcreteProgress(execRes, this.lastCode)) {
            this.hadProgress = true;
        }
    }

    void noteCompaction() {
        this.logger.log("HEARTBEAT", this.ctrl, "Context compacted for loop " + this.loopId, "turn=" + this.lastTurn);
    }

    void markEmptyResponse(String detail) {
        this.emptyResponses++;
        this.lastReason = detail == null ? "empty response" : detail;
        this.logger.log("HEARTBEAT", this.ctrl, "Empty/failed response in loop " + this.loopId,
                "count=" + this.emptyResponses + " detail=" + LoopTracer.snippet(this.lastReason, 200));
    }

    /**
     * @return true when the loop should retry instead of reporting a backend failure.
     */
    boolean retryAfterBackendFailure(JsonArray messages, int turn) {
        markEmptyResponse("backend failure");
        if (this.emptyResponses > 3) return false;
        this.logger.log("HEARTBEAT", this.ctrl, "Retrying loop " + this.loopId + " after backend failure",
                "attempt=" + this.emptyResponses + " turn=" + turn);
        return this.ctrl.drone != null && !this.ctrl.drone.isDead;
    }

    /**
     * Refuses a premature {@code done:true}: a scan-only result, still-queued work,
     * or mined goods that were never delivered must not end the job.
     */
    boolean rejectIncompleteDone(String reply) {
        if (this.forcedDoneChecks >= 2) return false;
        if (!AgentLoopHeuristics.promptExpectsPhysicalAction(this.userPrompt)) return false;
        if (looksBlocked(reply) || looksBlocked(this.ctrl.lastThought)) return false;
        boolean concrete = AgentLoopHeuristics.hasConcreteProgress(this.lastExec, this.lastCode) || deliveredEvidence(this.lastExec);
        boolean active = hasActiveWork();
        boolean deliveryPending = promptMentionsDelivery(this.userPrompt)
                && !deliveredEvidence(this.lastExec) && countInventory() > 0;
        if (!concrete || active || deliveryPending) {
            this.forcedDoneChecks++;
            this.logger.log("HEARTBEAT", this.ctrl, "Rejected premature done:true for loop " + this.loopId,
                    "check=" + this.forcedDoneChecks + " concrete=" + concrete + " active=" + active
                            + " deliveryPending=" + deliveryPending + " reply=\"" + LoopTracer.snippet(reply, 140) + "\"");
            return true;
        }
        return false;
    }

    String incompleteDoneFeedback() {
        return "HEARTBEAT: you marked done:true but the job is not complete. "
                + "Original task: " + LoopTracer.snippet(this.userPrompt, 320) + "\n"
                + "Current drone: " + statusSummary() + "\n"
                + "Do NOT answer done again until the goal is actually finished. Continue with the next "
                + "PHYSICAL JavaScript step (inspect / goto / breakBlock / transfer) and then verify the "
                + "result. Only when the requested items/actions are truly complete, reply done:true with "
                + "the delivered amount. If genuinely blocked, reply done:true and name the exact blocker.";
    }

    /**
     * Called once in the loop's finally; may schedule a compacted replacement loop.
     */
    void onLoopExit(boolean completed) {
        if (completed) {
            resetContinuations(this.ctrl.id);
            this.logger.log("HEARTBEAT", this.ctrl, "Loop " + this.loopId + " completed",
                    "turns=" + this.lastTurn + " hadProgress=" + this.hadProgress);
            return;
        }
        if (this.ctrl == null || this.ctrl.drone == null || this.ctrl.drone.isDead) return;
        if (this.ctrl.isLoopCancelled(this.loopId)) {
            this.logger.log("HEARTBEAT", this.ctrl, "Loop " + this.loopId + " was cancelled; no resurrection", "");
            return;
        }
        if (this.ctrl.activeLoopId.get() != this.loopId) {
            this.logger.log("HEARTBEAT", this.ctrl, "Loop " + this.loopId + " superseded by a newer instruction; no resurrection", "");
            return;
        }

        boolean actionTask = AgentLoopHeuristics.promptExpectsPhysicalAction(this.userPrompt);
        boolean active = hasActiveWork();
        boolean deliveryPending = promptMentionsDelivery(this.userPrompt)
                && countInventory() > 0 && !deliveredEvidence(this.lastExec);
        boolean failed = this.emptyResponses > 0 || this.lastReason.contains("error") || this.lastReason.contains("backend");
        boolean shouldResurrect = (actionTask && (active || !this.hadProgress || deliveryPending)) || failed;
        if (!shouldResurrect) {
            this.logger.log("HEARTBEAT", this.ctrl, "Loop " + this.loopId + " ended without physical work; nothing to resurrect",
                    "turns=" + this.lastTurn + " hadProgress=" + this.hadProgress);
            return;
        }
        if (!takeContinuationBudgetForDrone()) {
            this.logger.log("HEARTBEAT", this.ctrl, "Continuation budget exhausted for drone " + this.ctrl.id,
                    "loop=" + this.loopId + " reason=" + this.lastReason);
            return;
        }

        final int expectedGeneration = this.ctrl.activeLoopId.get();
        final String resumePrompt = buildResumePrompt();
        this.logger.log("HEARTBEAT", this.ctrl, "Scheduling resurrection of loop " + this.loopId,
                "active=" + active + " hadProgress=" + this.hadProgress + " deliveryPending=" + deliveryPending
                        + " emptyResponses=" + this.emptyResponses);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(RESURRECT_DELAY_MS);
                } catch (InterruptedException ignored) {
                    return;
                }
                if (ctrl.drone == null || ctrl.drone.isDead) return;
                if (ctrl.activeLoopId.get() != expectedGeneration) return;
                logger.log("HEARTBEAT", ctrl, "Resurrecting abandoned job as a compacted continuation loop",
                        "fromLoop=" + loopId);
                try {
                    manager.handleNaturalInstruction(player, ctrl, resumePrompt);
                } catch (Throwable t2) {
                    logger.log("HEARTBEAT", ctrl, "Resurrection failed", "error=" + t2);
                }
            }
        }, "CortexDrone-Heartbeat-" + this.ctrl.id);
        t.setDaemon(true);
        t.start();
    }

    private String buildResumePrompt() {
        return "[HEARTBEAT RESUME] Original task: " + LoopTracer.snippet(this.userPrompt, 500) + "\n"
                + "The previous loop paused before finishing (turn=" + this.lastTurn
                + ", hadProgress=" + this.hadProgress + ", reason=" + LoopTracer.snippet(this.lastReason, 120) + ").\n"
                + "Current drone: " + statusSummary() + "\n"
                + "Resume the SAME task from the current state. Do not repeat steps that already completed. "
                + "Use JS Code Mode. Finish with done:true only when the original goal is actually delivered "
                + "or the exact blocker is named.";
    }

    private String statusSummary() {
        if (this.ctrl == null || this.ctrl.drone == null) return "drone unavailable";
        StringBuilder sb = new StringBuilder();
        sb.append("mode=").append(this.ctrl.mode != null ? this.ctrl.mode.name() : "?");
        sb.append(" pos=(").append(Math.round(this.ctrl.drone.posX)).append(',')
                .append(Math.round(this.ctrl.drone.posY)).append(',')
                .append(Math.round(this.ctrl.drone.posZ)).append(')');
        sb.append(" mineQueue=").append(this.ctrl.mineQueue.size());
        sb.append(" inventory=").append(countInventory()).append(" items");
        if (this.lastExec != null) {
            sb.append(" lastResult=").append(LoopTracer.snippet(this.lastExec.toString(), 320));
        }
        return sb.toString();
    }

    private boolean hasActiveWork() {
        if (this.ctrl == null) return false;
        if (!this.ctrl.mineQueue.isEmpty()) return true;
        Mode m = this.ctrl.mode;
        return m == Mode.MINE || m == Mode.DROP || m == Mode.DEPOSIT;
    }

    private int countInventory() {
        if (this.ctrl == null || this.ctrl.drone == null) return 0;
        try {
            net.minecraft.inventory.IInventory inv = this.ctrl.drone.mainInventory();
            if (inv == null) return 0;
            int total = 0;
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                net.minecraft.item.ItemStack st = inv.getStackInSlot(i);
                if (st != null && st.stackSize > 0) total += st.stackSize;
            }
            return total;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static boolean promptMentionsDelivery(String prompt) {
        if (prompt == null) return false;
        String p = prompt.toLowerCase();
        return p.contains("come back") || p.contains("bring") || p.contains("deliver")
                || p.contains("give me") || p.contains("hand me") || p.contains("stack")
                || p.contains("return with") || p.contains("bring back");
    }

    private static boolean looksBlocked(String text) {
        if (text == null) return false;
        String t = text.toLowerCase();
        return t.contains("blocked") || t.contains("cannot") || t.contains("can't")
                || t.contains("unable") || t.contains("impossible") || t.contains("not found")
                || t.contains("no iron") || t.contains("could not find");
    }

    private static boolean deliveredEvidence(JsonObject execRes) {
        if (execRes == null || !execRes.has("result") || !execRes.get("result").isJsonObject()) return false;
        JsonObject r = execRes.getAsJsonObject("result");
        String[] keys = new String[]{"delivered", "gave", "given", "gaveCount", "itemsGiven",
                "transferred", "withdrawn", "deposited"};
        for (String k : keys) {
            if (!r.has(k) || r.get(k).isJsonNull()) continue;
            JsonElement v = r.get(k);
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsInt() > 0) return true;
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean()) return true;
        }
        return false;
    }

    private boolean takeContinuationBudgetForDrone() {
        long now = System.currentTimeMillis();
        long[] state = CONTINUATIONS.get(Integer.valueOf(this.ctrl.id));
        if (state == null || now - state[1] > CONTINUATION_WINDOW_MS) {
            state = new long[]{0L, now};
            CONTINUATIONS.put(Integer.valueOf(this.ctrl.id), state);
        }
        synchronized (state) {
            if (state[0] >= MAX_CONTINUATIONS) return false;
            state[0]++;
            return true;
        }
    }

    private static void resetContinuations(int ctrlId) {
        CONTINUATIONS.remove(Integer.valueOf(ctrlId));
    }
}
