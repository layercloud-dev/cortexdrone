package ir.layercloud.cortexdrone.codemode;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.behavior.BuildingService;
import ir.layercloud.cortexdrone.behavior.CombatService;
import ir.layercloud.cortexdrone.inventory.ChestSorter;
import ir.layercloud.cortexdrone.inventory.SmeltingService;
import ir.layercloud.cortexdrone.behavior.MiningService;
import ir.layercloud.cortexdrone.config.AIConfig;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.FlightPathfinder;
import ir.layercloud.cortexdrone.world.WorldScanner;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

public class LlmAgentService {
    private static final int MAX_AGENT_TURNS = 100;
    private static final long AGENT_DEADLINE_MS = 8L * 60L * 1000L; // 8 minutes
    private static final String SYSTEM_PROMPT_TEMPLATE = CodeModeRuntime.loadResourceText("/system-prompt.md");
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final WorldScanner worldScanner;
    private final CodeModeRuntime codeModeRuntime;
    private final RpcDispatcher rpcDispatcher;
    private int consecutiveConnectFailures = 0;
    private long lastConnectFailureAtMs = 0L;
    // Actual LLM gateway error text (HTTP code + body or exception message) from the last
    // failed call — surfaced to the player instead of a generic "unreachable" reply.
    private volatile String lastLlmError = null;

    // Debug: log the API key presence + gateway health on first call (masked) so silent-failure
    // ("why don't you respond") is visible in logs without leaking the secret.
    private static volatile boolean llmKeyLogged = false;
    private static String maskKey(String key) {
        if (key == null || key.isEmpty()) return "<empty>";
        if (key.length() <= 12) return key.substring(0, 3) + "..." + key.substring(key.length() - 2);
        return key.substring(0, 8) + "..." + key.substring(key.length() - 2) + " (len=" + key.length() + ")";
    }

    private static void logKeyDiagnosticsOnce(DroneController ctrl, DroneLogger logger) {
        if (llmKeyLogged) return;
        llmKeyLogged = true;
        try {
            String envKey = System.getenv("CORTEXDRONE_API_KEY");
            logger.log("LLM", ctrl, "API-key diagnostics: env=" + (envKey == null || envKey.isEmpty() ? "<unset>" : maskKey(envKey)) + " | in-JVM default/fallback=" + maskKey((new AIConfig()).apiKey), "gateway=127.0.0.1:7002");
        } catch (Throwable t) {
            logger.log("LLM", ctrl, "API-key diagnostics failed: " + t.toString(), "");
        }
    }

    private static void logKeyDiagnostics(DroneController ctrl, DroneLogger logger, String reason) {
        // always regenerates a small debug line so the running key is observable from /debug
        try {
            String envKey = System.getenv("CORTEXDRONE_API_KEY");
            logger.log("LLM", ctrl, "API-key state (" + reason + "): env=" + (envKey == null || envKey.isEmpty() ? "<unset>" : maskKey(envKey))
                + " | gateway health check: key=" + maskKey((new AIConfig()).apiKey), "reason=" + reason);
        } catch (Throwable ignored) {}
    }
    public LlmAgentService(
        AIDroneManager manager,
        DroneLogger logger,
        WorldScanner worldScanner,
        FlightPathfinder pathfinder,
        InventoryService inventoryService,
        ChestSorter chestSorter,
        SmeltingService smeltingService,
        MiningService miningService,
        BuildingService buildingService,
        CombatService combatService,
        CodeModeRuntime codeModeRuntime,
        RpcDispatcher rpcDispatcher
    ) {
        this.manager = manager;
        this.logger = logger;
        this.worldScanner = worldScanner;
        this.codeModeRuntime = codeModeRuntime;
        this.rpcDispatcher = rpcDispatcher;
    }

    public JsonObject buildDroneContextForLlm(EntityPlayerMP player, DroneController ctrl) {
        JsonObject ctx = new JsonObject();
        World world = ctrl.drone.field_70170_p;
        double refX = player != null ? player.field_70165_t : ctrl.drone.field_70165_t;
        double refY = player != null ? player.field_70163_u : ctrl.drone.field_70163_u;
        double refZ = player != null ? player.field_70161_v : ctrl.drone.field_70161_v;
        ctx.addProperty("player", player != null ? player.func_70005_c_() : ctrl.ownerName);
        ctx.addProperty("playerX", MathHelper.func_76128_c(refX));
        ctx.addProperty("playerY", MathHelper.func_76128_c(refY));
        ctx.addProperty("playerZ", MathHelper.func_76128_c(refZ));
        int facing = player != null ? (MathHelper.func_76128_c((double) (player.field_70177_z * 4.0f / 360.0f) + 0.5D) & 3) : 0;
        String[] dirs = new String[]{"South(+Z)", "West(-X)", "North(-Z)", "East(+X)"};
        ctx.addProperty("playerFacing", dirs[facing]);
        if (player != null) {
            ctx.addProperty("playerYaw", Math.round(player.field_70177_z * 10.0f) / 10.0f);
            ctx.addProperty("playerPitch", Math.round(player.field_70125_A * 10.0f) / 10.0f);
            JsonObject lookBlock = WorldScanner.raycastPlayerLook(world, player, 80.0);
            if (lookBlock != null) {
                ctx.add("playerCrosshairLookBlock", lookBlock);
            }
            JsonObject supportBlock = WorldScanner.findPlayerSupportBlock(world, player);
            if (supportBlock != null) {
                ctx.add("playerStandingOnBlock", supportBlock);
            }
            ItemStack held = player.func_71045_bC();
            ctx.addProperty("playerHeldItem", (held != null && held.field_77994_a > 0)
                ? (held.func_82833_r() + ":" + held.func_77960_j() + " x" + held.field_77994_a)
                : "Empty");
        }
        ctx.addProperty("droneName", ctrl.name);
        ctx.addProperty("droneX", Math.round(ctrl.drone.field_70165_t * 10.0) / 10.0);
        ctx.addProperty("droneY", Math.round(ctrl.drone.field_70163_u * 10.0) / 10.0);
        ctx.addProperty("droneZ", Math.round(ctrl.drone.field_70161_v * 10.0) / 10.0);
        ctx.addProperty("mode", ctrl.mode.name());
        ctx.addProperty("droneCanSeeSky", world.func_72937_j(
            MathHelper.func_76128_c(ctrl.drone.field_70165_t),
            MathHelper.func_76128_c(ctrl.drone.field_70163_u),
            MathHelper.func_76128_c(ctrl.drone.field_70161_v)
        ));
        ctx.addProperty("breadcrumbCount", ctrl.navigationHistory.size());
        ctx.addProperty("droneInventory", InventoryService.formatInventorySummary(ctrl.drone.mainInventory()));
        ctx.addProperty("playerInventory", player != null ? InventoryService.formatInventorySummary(player.field_71071_by) : "Offline");

        // Slim situational awareness for smarter tool selection (no scanNeighbors dump; LLM uses
        // world.findItemInChests / world.scanBlocks / world.getEntities on demand instead)
        ctx.addProperty("distToPlayerM", Math.round(Math.hypot(
            ctrl.drone.field_70165_t - refX, ctrl.drone.field_70163_u - refY) * 10.0) / 10.0);
        ctx.addProperty("droneMode", ctrl.mode.name());
        ctx.addProperty("mineQueueSize", ctrl.mineQueue.size());
        ctx.addProperty("buildQueueSize", ctrl.buildQueue.size());
        ctx.addProperty("lastPathPartial", ctrl.lastPathPartial);
        ctx.addProperty("pathNodesExpanded", ctrl.lastPathNodesExpanded);
        if (!ctrl.lastThought.isEmpty()) ctx.addProperty("lastThought", ctrl.lastThought);
        JsonObject queues = new JsonObject();
        queues.addProperty("mineFilter", ctrl.mineFilter);
        queues.addProperty("huntFilter", ctrl.huntFilter);
        ctx.add("queues", queues);

        String ownerKey = player != null ? player.func_70005_c_() : ctrl.ownerName;
        List<AIConfig.SavedLocation> savedLocs = this.manager.getConfig().listLocations(ownerKey);
        if (!savedLocs.isEmpty()) {
            JsonObject locsObj = new JsonObject();
            for (AIConfig.SavedLocation loc : savedLocs) {
                locsObj.addProperty(loc.name, "(" + loc.x + "," + loc.y + "," + loc.z + ")"
                    + (loc.tags != null && !loc.tags.isEmpty() ? " [" + loc.tags + "]" : ""));
            }
            ctx.add("savedLocations", locsObj);
        }

        // Hint-only: WorldScanner caches by centre bucket + radius for 7s, so the
        // 24m scan here and any nearby 16m telemetry scan share one computation.
        JsonObject resources = new JsonObject();
        Map<String, int[]> scan = this.worldScanner.scanNearbyResources(world, refX, refY, refZ, 24);
        for (Map.Entry<String, int[]> e : scan.entrySet()) {
            int[] v = e.getValue();
            resources.addProperty(e.getKey(), v[0] + " (nearest: " + v[1] + "," + v[2] + "," + v[3] + ")");
        }
        ctx.add("nearbyBlocks24m", resources);
        return ctx;
    }

    public static String sanitizeThought(String rawThought, String fallbackReasoning) {
        return LlmResponseParser.sanitizeThought(rawThought, fallbackReasoning);
    }

    public static JsonObject parseCodeModeLlmContent(String raw) {
        return LlmResponseParser.parse(raw, "");
    }

    public static JsonObject parseCodeModeLlmContent(String raw, String reasoningContent) {
        return LlmResponseParser.parse(raw, reasoningContent);
    }

    public void runAgentLoop(final EntityPlayerMP player, final DroneController ctrl, final String userPrompt, final JsonObject worldContext, final int myLoopId) {
        try {
            StringBuilder sysSb = new StringBuilder(SYSTEM_PROMPT_TEMPLATE);
            sysSb.append("\nInitial World State: ").append(worldContext.toString());
            if (this.manager.getSkillLibrary() != null) {
                List<SkillLibrary.SkillEntry> matchedSkills = this.manager.getSkillLibrary().searchSkills(userPrompt, 3);
                if (!matchedSkills.isEmpty()) {
                    sysSb.append("\nRelevant Reusable Skills: ").append(this.manager.getSkillLibrary().toJsonArray(matchedSkills).toString());
                }
            }
            String sysPrompt = sysSb.toString();

            JsonArray messages = new JsonArray();
            JsonObject mSys = new JsonObject();
            mSys.addProperty("role", "system");
            mSys.addProperty("content", sysPrompt);
            messages.add(mSys);

            JsonObject mUser = new JsonObject();
            mUser.addProperty("role", "user");
            mUser.addProperty("content", userPrompt);
            messages.add(mUser);

            boolean executedAnyAction = false;
            boolean hadSelfCorrection = false;
            boolean hadProgress = false;
            int inspectionTurns = 0;
            String lastReactionKey = null;
            String lastQueryKey = null;
            int sameQueryStreak = 0;
            int emptySearchStreak = 0;
            JsonObject lastMachineBlocker = null;
            final long loopDeadline = System.currentTimeMillis() + AGENT_DEADLINE_MS;

            for (int turn = 1; turn <= MAX_AGENT_TURNS; turn++) {
                if (ctrl.activeLoopId.get() != myLoopId || ctrl.drone == null || ctrl.drone.field_70128_L) {
                    this.logger.log("THINK", ctrl, "Loop #" + myLoopId + " superseded at Turn " + turn, "");
                    return;
                }
                // Deterministic deadline: stop loitering instead of burning turns on
                // re-queries. Always speak a partial reply so the player is not left
                // staring at a silent drone.
                if (System.currentTimeMillis() > loopDeadline) {
                    this.logger.log("LLM", ctrl, "Agent loop hit wall-clock deadline (8 min) at Turn " + turn
                        + " — reporting partial progress", "");
                    final String deadlineReply = AgentLoopHeuristics.progressReplyFor(
                        "Stopped at the time limit. No further progress was possible without more info.",
                        hadProgress, ctrl.lastThought);
                    this.manager.getMod().queueMain(new Runnable() {
                        @Override public void run() { manager.droneSay(player, ctrl, deadlineReply); }
                    });
                    return;
                }

                final int curTurn = turn;
                this.manager.getMod().queueMain(new Runnable() {
                    @Override
                    public void run() {
                        if (ctrl.drone != null && !ctrl.drone.field_70128_L && ctrl.mode == Mode.FOLLOW) {
                            ctrl.drone.lightColor_$eq(0xAA55FF);
                            ctrl.drone.statusText_$eq("Thinking...\nTurn " + curTurn);
                        }
                    }
                });

                JsonObject plan = callOpenAiChatMessages(ctrl, messages);
                if (ctrl.activeLoopId.get() != myLoopId || ctrl.drone == null || ctrl.drone.field_70128_L) {
                    return;
                }
                if (plan == null) {
                    String detail = lastLlmError != null ? lastLlmError : "no response from LLM gateway";
                    this.logger.log("LLM", ctrl, "LLM backend failed on Turn " + turn
                        + " — reporting the error to the player and stopping", "detail=" + detail);
                    final String partialMsg = AgentLoopHeuristics.progressReplyFor(
                        "AI backend error: " + detail + ". Fix the API key / gateway then re-send your instruction.",
                        hadProgress, ctrl.lastThought);
                    this.manager.getMod().queueMain(new Runnable() {
                        @Override public void run() { manager.droneSay(player, ctrl, partialMsg); }
                    });
                    return;
                }

                String rawThought = plan.has("thought") && !plan.get("thought").isJsonNull() ? plan.get("thought").getAsString() : "";
                String rawReasoning = plan.has("_reasoning") && !plan.get("_reasoning").isJsonNull() ? plan.get("_reasoning").getAsString() : "";
                final String thought = LlmResponseParser.sanitizeThought(rawThought, rawReasoning);
                if (!thought.isEmpty()) {
                    ctrl.recordThought(turn, thought);
                    this.logger.log("THINK", ctrl, "Turn " + turn + ": " + thought, "");
                    this.manager.getMod().queueMain(new Runnable() {
                        @Override
                        public void run() {
                            if (ctrl.drone != null && !ctrl.drone.field_70128_L && ctrl.mode == Mode.FOLLOW) {
                                ctrl.drone.statusText_$eq("Think T" + curTurn + "\n" + AIDroneManager.trimStatus(thought));
                            }
                        }
                    });
                }

                boolean isMalformed = plan.has("malformed") && plan.get("malformed").getAsBoolean();
                String code = "";
                JsonArray fallbackTools = null;
                if (plan.has("code") && !plan.get("code").isJsonNull()) {
                    code = plan.get("code").getAsString().trim();
                }
                if (code.isEmpty() && plan.has("tool_calls") && plan.get("tool_calls").isJsonArray()) {
                    JsonArray tcs = plan.getAsJsonArray("tool_calls");
                    for (JsonElement el : tcs) {
                        if (el.isJsonObject() && el.getAsJsonObject().has("code") && !el.getAsJsonObject().get("code").isJsonNull()) {
                            code = el.getAsJsonObject().get("code").getAsString().trim();
                            break;
                        }
                    }
                    if (code.isEmpty() && tcs.size() > 0) {
                        fallbackTools = tcs;
                    }
                }
                if (code.isEmpty() && fallbackTools == null && plan.has("actions") && plan.get("actions").isJsonArray()) {
                    JsonArray acts = plan.getAsJsonArray("actions");
                    if (acts.size() > 0) {
                        fallbackTools = acts;
                    }
                }

                boolean done = !isMalformed && plan.has("done") && !plan.get("done").isJsonNull() && plan.get("done").getAsBoolean();
                final String reply = (!isMalformed && plan.has("reply") && !plan.get("reply").isJsonNull())
                    ? plan.get("reply").getAsString().trim()
                    : "";
                // Deterministic early-finish for machine-config-only / empty / long no-code essay.
                if (AgentLoopHeuristics.shouldForceFinal(plan, messages)) {
                    final String earlyReply = "Blocked: the searched recipe/item path is machine-config-only or empty. "
                        + "Next step: provide the exact machine + missing ingredient, or pick the cheap vanilla "
                        + "alternative, then re-request.";
                    this.logger.log("CODEMODE", ctrl, "Turn " + turn + " early-final forced done:true", "");
                    this.manager.getMod().queueMain(new Runnable() {
                        @Override public void run() { manager.droneSay(player, ctrl, earlyReply); }
                    });
                    return;
                }
                if (AgentLoopHeuristics.isMachineBlockerResult(lastMachineBlocker) && !AgentLoopHeuristics.codeMentionsPhysicalAction(code)
                    && !done && !plan.has("malformed")) {
                    final String blockerReply = "Blocked on an HBM machine config — the only recipe path is "
                        + "machine-gated (AssemblyMachine/ChemicalPlant/BlastFurnace/Anvil GUI-only). "
                        + "Next step: set up/bring that machine + power/fuel and the missing ingredient(s), "
                        + "then re-request. (Last tool result named the exact machine/ingredient.)";
                    this.logger.log("CODEMODE", ctrl, "Turn " + turn
                        + " machine-config blocker seen -> immediate done:true (no re-query)", "");
                    this.manager.getMod().queueMain(new Runnable() {
                        @Override public void run() { manager.droneSay(player, ctrl, blockerReply); }
                    });
                    return;
                }
                // Also: proseBlocker salvage (prose machine blocker) inside parse()
                // already set done=true in one turn; nothing extra to do here.
                if (isMalformed || (code.isEmpty() && fallbackTools == null && !executedAnyAction && AgentLoopHeuristics.promptExpectsActionQueue(userPrompt))) {
                    hadSelfCorrection = true;
                    this.logger.log("CODEMODE", ctrl, "Turn " + turn + " malformed or missing JS code -> self-correcting", "thought=" + thought);
                    if (turn < MAX_AGENT_TURNS) {
                        AgentLoopHeuristics.appendTurnFeedback(
                            messages,
                            thought,
                            "",
                            "FORMAT ERROR: Your response was malformed or missing executable JavaScript `\"code\"`. Do NOT output plain text or stop without executing the task! Respond ONLY with a valid JSON object:\n{\"thought\": \"...\", \"code\": \"await ...\", \"done\": true, \"reply\": \"...\"}"
                        );
                        continue;
                    }
                }

                if (code.isEmpty() && fallbackTools != null) {
                    executedAnyAction = true;
                    JsonArray toolResults = new JsonArray();
                    for (JsonElement el : fallbackTools) {
                        if (el.isJsonObject()) {
                            JsonObject ao = el.getAsJsonObject();
                            String tool = ao.has("tool") ? ao.get("tool").getAsString() : "";
                            JsonObject args = ao.has("args") && ao.get("args").isJsonObject() ? ao.getAsJsonObject("args") : ao;
                            toolResults.add(this.rpcDispatcher.executeOnMainThread(ctrl, player, tool, args));
                        }
                    }
                    if (!done && turn < MAX_AGENT_TURNS) {
                        JsonObject droneStatus = this.rpcDispatcher.executeOnMainThread(ctrl, player, "get_drone", new JsonObject());
                        AgentLoopHeuristics.appendTurnFeedback(
                            messages,
                            thought,
                            "",
                            "Tool Call Results:\n"
                                + AgentLoopHeuristics.truncateForPrompt(toolResults.toString(), 1800)
                                + "\nCurrent Drone Status:\n"
                                + AgentLoopHeuristics.truncateForPrompt(droneStatus.toString(), 1200)
                                + "\nContinue with JavaScript `\"code\"`, or set `\"done\": true` and provide your final `\"reply\"`."
                        );
                        continue;
                    }
                    if (!reply.isEmpty()) {
                        this.manager.getMod().queueMain(new Runnable() {
                            @Override
                            public void run() {
                                manager.droneSay(player, ctrl, reply);
                            }
                        });
                    }
                    return;
                }

                if (!code.isEmpty()) {
                    executedAnyAction = true;
                    this.manager.getMod().queueMain(new Runnable() {
                        @Override
                        public void run() {
                            if (ctrl.drone != null && !ctrl.drone.field_70128_L && ctrl.mode == Mode.FOLLOW) {
                                ctrl.drone.lightColor_$eq(0xAA55FF);
                            }
                        }
                    });

                    final AtomicBoolean jsFinished = new AtomicBoolean(false);
                    if (done && !thought.isEmpty()) {
                        Thread ackThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Thread.sleep(1200);
                                    if (!jsFinished.get() && ctrl.activeLoopId.get() == myLoopId) {
                                        manager.getMod().queueMain(new Runnable() {
                                            @Override
                                            public void run() {
                                                manager.droneSay(player, ctrl, "Working on it: " + thought);
                                            }
                                        });
                                    }
                                } catch (InterruptedException ignored) {
                                }
                            }
                        }, "CortexDrone-Ack-" + ctrl.id);
                        ackThread.setDaemon(true);
                        ackThread.start();
                    }

                    JsonObject execRes = this.codeModeRuntime.executeJavaScript(ctrl, player, code, myLoopId, done);
                    jsFinished.set(true);

                    if (ctrl.activeLoopId.get() != myLoopId || ctrl.drone == null || ctrl.drone.field_70128_L) {
                        return;
                    }
                    if (execRes.has("superseded") && execRes.get("superseded").getAsBoolean()) {
                        return;
                    }

                    boolean ok = execRes.has("ok") && execRes.get("ok").getAsBoolean();
                    if (ok) {
                        hadProgress = hadProgress || AgentLoopHeuristics.hasConcreteProgress(execRes, code);
                        if (hadProgress) {
                            inspectionTurns = 0;
                            sameQueryStreak = 0;
                            emptySearchStreak = 0;
                        }
                        if (AgentLoopHeuristics.isMachineBlockerResult(execRes)) {
                            lastMachineBlocker = execRes;
                        }
                        // Empty-search streak: meteor hunt re-queried findDistant/
                        // kb.lookup dozens of times with found:false / count:0.
                        if (AgentLoopHeuristics.isEmptySearchResult(execRes) && !AgentLoopHeuristics.codeMentionsPhysicalAction(code)) {
                            emptySearchStreak++;
                        } else {
                            emptySearchStreak = 0;
                        }
                    }
                    if (!ok) {
                        done = false;
                        hadSelfCorrection = true;
                        this.logger.log("CODEMODE", ctrl, "Turn " + turn + " JS Error -> Self-Correcting", execRes.toString());
                        AgentLoopHeuristics.appendTurnFeedback(
                            messages, thought, code,
                            "JS FAILED: " + AgentLoopHeuristics.truncateForPrompt(execRes.toString(), 1800)
                                + "\nFix the bug in ONE corrected JS \"code\" step and re-run it. Do NOT re-query kb.lookup/getRecipes/findItemInChests for the same target.");
                        continue;
                    }

                    if (done && turn < 24 && AgentLoopHeuristics.promptExpectsActionQueue(userPrompt) && AgentLoopHeuristics.hasZeroQueuedWarning(execRes)) {
                        done = false;
                        hadSelfCorrection = true;
                        this.logger.log("CODEMODE", ctrl, "Turn " + turn + " suspicious 0-queued result -> prompting self-correction", execRes.toString());
                        AgentLoopHeuristics.appendTurnFeedback(
                            messages,
                            thought,
                            code,
                            "JS Execution SUCCEEDED:\n"
                                + AgentLoopHeuristics.truncateForPrompt(execRes.toString(), 1800)
                                + "\nWarning: your code queued/affected 0 blocks/items. Inspect the coordinates/names in the result above and fix your code so the task actually executes!"
                        );
                        continue;
                    }

                    if (done && hadSelfCorrection && this.manager.getSkillLibrary() != null) {
                        this.manager.getSkillLibrary().recordSelfCorrectedSkill(userPrompt, thought, code);
                        this.logger.log("RLM", ctrl, "Distilled self-corrected workflow into SkillLibrary", "turn=" + turn);
                    }

                    if (!done && turn < MAX_AGENT_TURNS) {
                        JsonObject droneStatus = this.rpcDispatcher.executeOnMainThread(ctrl, player, "get_drone", new JsonObject());
                        String feedback = "JS OK:\n"
                            + AgentLoopHeuristics.truncateForPrompt(execRes.toString(), 1800)
                            + "\nStatus:\n" + AgentLoopHeuristics.truncateForPrompt(droneStatus.toString(), 1200)
                            + "\nNext JS \"code\" step, or \"done\":true + final \"reply\" if complete.";
                        String codeFingerprint = AgentLoopHeuristics.fingerprintCode(code);
                        if (lastReactionKey != null && lastReactionKey.equals(codeFingerprint)) {
                            this.logger.log("CODEMODE", ctrl, "Turn " + turn + " repeated code detected -> deterministic different step", "");
                            feedback += "\n\nNON-DETERMINISTIC PATTERN: this \"code\" is byte-identical to the previous turn (same call, same args). Do NOT re-issue it — produce a DIFFERENT next step or finish with \"done\":true.";
                        }
                        lastReactionKey = codeFingerprint;
                        boolean inspectionOnly = !AgentLoopHeuristics.codeMentionsPhysicalAction(code);
                        if (inspectionOnly) {
                            inspectionTurns++;
                        } else {
                            inspectionTurns = 0;
                        }
                        if (inspectionTurns >= 2) {
                            feedback += "\n\nANTI-LOITER: 2 inspection-only turns with no physical action (mine/craft/withdraw/deposit/mineMany/place...). Next \"code\" MUST perform a physical action or finish done:true with a blocker — a 3rd inspection turn is forbidden.";
                            this.logger.log("CODEMODE", ctrl, "Turn " + turn + " inspectionTurns=" + inspectionTurns + " -> forced-action nudge", "");
                        }
                        // Deterministic same-query streak (meteor hunt pattern):
                        // same inspection tool + same target arg with no progress.
                        String qk = AgentLoopHeuristics.queryFingerprint(code);
                        if (!qk.isEmpty() && qk.equals(lastQueryKey) && !AgentLoopHeuristics.hasConcreteProgress(execRes, code)) {
                            sameQueryStreak++;
                        } else if (!qk.isEmpty()) {
                            sameQueryStreak = 1;
                        }
                        lastQueryKey = qk.isEmpty() ? lastQueryKey : qk;
                        if (sameQueryStreak >= 2) {
                            feedback += "\n\nANTI-RE-QUERY: you have re-queried '" + qk + "' " + (sameQueryStreak + 1)
                                + "x with no progress. Record what you know and act: withdraw/craft with "
                                + "found items, or — if every search returns empty/not-found — finish done:true "
                                + "with the exact blocker and next step. Do NOT call " + qk + " again this task.";
                            this.logger.log("CODEMODE", ctrl, "Turn " + turn + " same-query streak=" + sameQueryStreak
                                + " qk=" + qk + " -> anti-requery nudge", "");
                        }
                        if (emptySearchStreak >= 2) {
                            feedback += "\n\nEMPTY-SEARCH: last results were empty (found:false/count:0). "
                                + "Do NOT keep scanning wider radii for the same target. Either act on the "
                                + "nearest alternative (different ore/block/registry name from kb.lookup) or "
                                + "finish done:true: state what was searched, the exact blocker, and the next "
                                + "concrete step (e.g. fly to a known biome/chunk, change filter).";
                            this.logger.log("CODEMODE", ctrl, "Turn " + turn + " emptySearchStreak=" + emptySearchStreak
                                + " -> empty-search nudge", "");
                        }
                        boolean repeated = AgentLoopHeuristics.isRepeatedToolResult(AgentLoopHeuristics.lastUserContent(messages), feedback);
                        if (repeated) {
                            feedback += "\n\n" + AgentLoopHeuristics.nudgeForRepeat(userPrompt);
                            this.logger.log("CODEMODE", ctrl, "Turn " + turn + " repeated tool result detected -> anti-requery nudge", "");
                        }
                        // Progress relay: first concrete progress speaks once so the
                        // player sees the drone is working (not holding chat spam).
                        if (!hadProgress && AgentLoopHeuristics.hasConcreteProgress(execRes, code)) {
                            final String relayMsg = "Progress: first step done. Continuing with next step.";
                            this.manager.getMod().queueMain(new Runnable() {
                                @Override public void run() { manager.droneSay(player, ctrl, relayMsg); }
                            });
                            hadProgress = true;
                        }
                        AgentLoopHeuristics.appendTurnFeedback(messages, thought, code, feedback);
                        continue;
                    }

                    if (!reply.isEmpty()) {
                        this.manager.getMod().queueMain(new Runnable() {
                            @Override
                            public void run() {
                                manager.droneSay(player, ctrl, reply);
                            }
                        });
                    }
                    return;
                } else {
                    if (done || turn == MAX_AGENT_TURNS) {
                        final String fallbackReply = reply.isEmpty()
                            ? "I couldn't form a valid action plan for that. Please rephrase or simplify the task."
                            : reply;
                        this.manager.getMod().queueMain(new Runnable() {
                            @Override
                            public void run() {
                                manager.droneSay(player, ctrl, fallbackReply);
                            }
                        });
                        return;
                    }
                    AgentLoopHeuristics.appendTurnFeedback(
                        messages, thought, "",
                        "Next step: emit one JS \"code\" step (physical action preferred) or \"done\":true with final \"reply\" (partial reply OK: state what was delivered, the exact blocker, and the next concrete step).") ;
                }
            }
            // Cap reached without a clean done: report partial progress deterministically
            // (done:true with a partial reply at cap — never a silent stop).
            this.logger.log("LLM", ctrl, "Agent loop hit turn cap (" + MAX_AGENT_TURNS + ") — reporting partial progress", "");
            final String capReply = AgentLoopHeuristics.progressReplyFor(
                "Stopped at the turn cap. What stands so far is in the last status/checkpoint above; "
                    + "the blocker (missing item/machine) is named there.",
                hadProgress, ctrl.lastThought);
            this.manager.getMod().queueMain(new Runnable() {
                @Override public void run() { manager.droneSay(player, ctrl, capReply); }
            });
            return;
        } catch (Exception e) {
            this.logger.log("LLM", ctrl, "Pure Code Mode agent loop error", e.toString());
            final String errReply = "Stopped due to an internal error (" + e.toString() + "). Re-send the instruction to retry.";
            try {
                this.manager.getMod().queueMain(new Runnable() {
                    @Override public void run() { manager.droneSay(player, ctrl, errReply); }
                });
            } catch (Exception ignored) {
            }
        }
    }

    private JsonObject callOpenAiChatMessages(DroneController ctrl, JsonArray messages) throws Exception {
        AIConfig cfg = this.manager.getConfig();
        logKeyDiagnosticsOnce(ctrl, this.logger);
        JsonObject lastMalformed = null;
        int localFailures = 0;

        // Backoff 500->1000->2000ms; honor open circuit (capped 5s/turn).
        if (consecutiveConnectFailures >= 5 && System.currentTimeMillis() - lastConnectFailureAtMs < 60000) {
            long waitMs = 60000 - (System.currentTimeMillis() - lastConnectFailureAtMs);
            if (waitMs > 0) {
                this.logger.log("LLM", ctrl, "Circuit open, waiting " + Math.min(waitMs, 5000) + "ms before retry", "");
                Thread.sleep(Math.min(waitMs, 5000));
            }
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            URL url = new URL(cfg.apiUrl);
            boolean isLocal = cfg.apiUrl.contains("127.0.0.1") || cfg.apiUrl.contains("localhost");
            HttpURLConnection conn = (HttpURLConnection) (isLocal
                ? url.openConnection(Proxy.NO_PROXY)
                : url.openConnection());
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(35000);
            conn.setRequestProperty("Content-Type", "application/json");
            if (cfg.apiKey != null && !cfg.apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);
            }
            conn.setDoOutput(true);

            JsonObject req = new JsonObject();
            req.addProperty("model", cfg.model);
            req.addProperty("temperature", 0.2);
            req.addProperty("reasoning_effort", "low");
            req.addProperty("max_tokens", 1400);
            if (attempt == 1) {
                JsonObject respFmt = new JsonObject();
                respFmt.addProperty("type", "json_object");
                req.add("response_format", respFmt);
            }
            req.add("messages", AgentLoopHeuristics.pruneMessagesWindow(messages));

            try {
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(req.toString().getBytes(StandardCharsets.UTF_8));
                }

                int httpCode = conn.getResponseCode();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    httpCode >= 200 && httpCode < 300 ? conn.getInputStream() : conn.getErrorStream(),
                    StandardCharsets.UTF_8
                ))) {
                    StringBuilder rawSb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        rawSb.append(line).append('\n');
                    }
                    String rawBody = rawSb.toString().trim();
                    if (httpCode < 200 || httpCode >= 300) {
                        String errDetail = "LLM gateway HTTP " + httpCode + (rawBody.isEmpty() ? "" : " - " + rawBody.substring(0, Math.min(800, rawBody.length())));
                        // Provider error body is the single most useful debug diagnostic:
                        // log the full body text (not just the HTTP code) so the player
                        // can see whether it's a key issue, a model issue, or a rate limit.
                        lastLlmError = errDetail;
                        this.logger.log("LLM", ctrl, errDetail, "url=" + cfg.apiUrl + " | in-JVM key=" + maskKey(cfg.apiKey) + " | env=" + maskKey(System.getenv("CORTEXDRONE_API_KEY")));
                        if (attempt < 3) continue;
                        return lastMalformed;
                    }
                    JsonElement parsedResp = new JsonParser().parse(rawBody);
                    if (parsedResp == null || !parsedResp.isJsonObject()) {
                        if (attempt < 3) continue;
                        return lastMalformed;
                    }
                    JsonObject resp = parsedResp.getAsJsonObject();
                    if (!resp.has("choices") || !resp.get("choices").isJsonArray() || resp.getAsJsonArray("choices").size() == 0) {
                        lastLlmError = "LLM gateway returned no choices: " + rawBody.substring(0, Math.min(300, rawBody.length()));
                        this.logger.log("LLM", ctrl, lastLlmError, "url=" + cfg.apiUrl);
                        if (attempt < 3) continue;
                        return lastMalformed;
                    }
                    JsonObject msgObj = resp.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
                    String content = "";
                    String reasoning = "";
                    if (msgObj != null && msgObj.has("content") && !msgObj.get("content").isJsonNull()) {
                        content = msgObj.get("content").getAsString().trim();
                    }
                    if (msgObj != null && msgObj.has("reasoning_content") && !msgObj.get("reasoning_content").isJsonNull()) {
                        reasoning = msgObj.get("reasoning_content").getAsString().trim();
                    }
                    if (content.isEmpty() && reasoning.isEmpty()) {
                        if (attempt < 3) continue;
                        return lastMalformed;
                    }
                    JsonObject parsed = LlmResponseParser.parse(content.isEmpty() ? reasoning : content, reasoning);
                    if (parsed != null && parsed.has("malformed") && parsed.get("malformed").getAsBoolean()) {
                        lastMalformed = parsed;
                        if (attempt < 2) continue;
                    } else if (parsed != null) {
                        consecutiveConnectFailures = 0;
                    }
                    return parsed;
                }
            } catch (Exception ex) {
                String exMsg = ex.getMessage();
                String exDetail = "LLM gateway exception: " + ex.toString();
                lastLlmError = exDetail;
                this.logger.log("LLM", ctrl, exDetail, "url=" + cfg.apiUrl + " | in-JVM key=" + maskKey(cfg.apiKey) + " | env=" + maskKey(System.getenv("CORTEXDRONE_API_KEY")));
                boolean connectFailure = ex instanceof java.net.ConnectException || ex instanceof java.net.SocketTimeoutException || (exMsg != null && (exMsg.contains("Connection refused") || exMsg.contains("timed out")));
                if (attempt < 3) Thread.sleep(Math.min(2000, 500 << (attempt - 1)));
                if (connectFailure) {
                    localFailures++;
                    consecutiveConnectFailures++;
                    lastConnectFailureAtMs = System.currentTimeMillis();
                    if (attempt >= 3) throw ex;
                } else if (attempt >= 3) throw ex;
            }
        }
        return lastMalformed;
    }
}
