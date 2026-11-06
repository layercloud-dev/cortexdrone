package ir.layercloud.cortexdrone.harness.task;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.harness.agent.AgentService;
import ir.layercloud.cortexdrone.harness.memory.MemoryCapture;
import ir.layercloud.cortexdrone.harness.memory.MemoryEntry;
import ir.layercloud.cortexdrone.harness.memory.MemoryStore;
import ir.layercloud.cortexdrone.harness.session.DroneSession;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Owns the single task/session decision: a message is either a question, a
 * side note, a task modification, or a cancellation. Only a new/modified task
 * starts an agent loop; there is no instruction queue.
 */
@RequiredArgsConstructor
public final class TaskCoordinator {
    private final AIDroneManager manager;

    public void handle(final EntityPlayerMP player, final DroneController ctrl, final String prompt) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.isDead) return;
        DroneSession session = session(ctrl);
        String cleanPrompt = prompt != null ? prompt.trim() : "";
        boolean active = session.isActive() || session.hasActiveTaskOrLoop(0);
        TaskIntent intent = TaskIntentClassifier.classify(cleanPrompt, active);

        // Explicit "remember ..." / "this is my base" messages are persisted
        // immediately. They never start or replace a task, and while a task is
        // running they also become a note for the model.
        if (MemoryCapture.isMemoryMessage(cleanPrompt)) {
            captureMemory(player, ctrl, cleanPrompt, active, session);
            return;
        }

        if (active) {
            if (intent == TaskIntent.QUESTION) {
                this.manager.getLogger().log("TASK", ctrl, "Message classified as QUESTION while a task is active",
                        "prompt=\"" + cleanPrompt + "\"");
                this.manager.droneSay(player, ctrl, activeTaskReply(ctrl));
                return;
            }
            if (intent == TaskIntent.BY_THE_WAY) {
                session.addNote(cleanPrompt);
                boolean remembered = MemoryCapture.containsMemoryCue(cleanPrompt)
                        && captureMemory(player, ctrl, cleanPrompt, true, session);
                this.manager.getLogger().log("TASK", ctrl, "Message classified as BY_THE_WAY; attached as note",
                        "prompt=\"" + cleanPrompt + "\" remembered=" + remembered);
                if (!remembered) {
                    this.manager.droneSay(player, ctrl, "Noted - keeping the current task. " + shortTask(ctrl));
                }
                return;
            }
            if (intent == TaskIntent.CANCEL) {
                cancel(ctrl);
                this.manager.droneSay(player, ctrl, "Task cancelled. Hovering in place - give me a new instruction when ready.");
                return;
            }
            // MODIFY / NEW_TASK: replace the running session immediately.
            String previous = session.getSummary();
            this.manager.getLogger().log("TASK", ctrl, "Message classified as " + intent.name() + "; replacing active task",
                    "prompt=\"" + cleanPrompt + "\"");
            session.cancelOtherLoops(0);
            this.manager.clearActionState(ctrl);
            session.cancel();
            if (intent == TaskIntent.MODIFY && previous != null && !previous.isEmpty()) {
                cleanPrompt = cleanPrompt + "\n\n[Task modification. Previous task: " + previous + "]";
            }
        } else if (intent == TaskIntent.CANCEL) {
            this.manager.droneSay(player, ctrl, "No task is running. Nothing to cancel.");
            return;
        } else if (intent == TaskIntent.BY_THE_WAY) {
            session.addNote(cleanPrompt);
            if (MemoryCapture.containsMemoryCue(cleanPrompt)) {
                captureMemory(player, ctrl, cleanPrompt, false, session);
            } else {
                this.manager.droneSay(player, ctrl, "Noted.");
            }
            return;
        }

        final int myLoopId = session.nextLoopId();
        session.begin(myLoopId, cleanPrompt);
        String senderName = player != null ? player.getCommandSenderName() : ctrl.ownerName;
        this.manager.getLogger().log("CMD", ctrl,
                "Tool session from " + senderName + " (loopId=" + myLoopId + ", intent=" + intent.name() + ")",
                "prompt=\"" + cleanPrompt + "\"");

        final AgentService agent = this.manager.getAgentService();
        final String contextMessage = agent.buildContext(player, ctrl, cleanPrompt, intent.name());
        session.registerLoop(myLoopId);
        final String sessionPrompt = cleanPrompt;
        final int loopId = myLoopId;
        CortexDroneMod.AGENT_POOL.submit(() -> agent.runAgentLoop(player, ctrl, sessionPrompt, contextMessage, loopId));
    }

    /**
     * Persists a player memory statement with the player's (or drone's) current
     * position, then confirms it in chat. Returns false when nothing was saved.
     */
    private boolean captureMemory(EntityPlayerMP player, DroneController ctrl, String prompt,
                                  boolean active, DroneSession session) {
        MemoryStore memory = this.manager.getHarness() != null
                ? this.manager.getHarness().memory() : null;
        if (memory == null) return false;
        double x = player != null ? player.posX : ctrl.drone.posX;
        double y = player != null ? player.posY : ctrl.drone.posY;
        double z = player != null ? player.posZ : ctrl.drone.posZ;
        int dimension = player != null ? player.dimension : ctrl.drone.dimension;
        String owner = player != null ? player.getCommandSenderName() : ctrl.ownerName;
        MemoryEntry entry = memory.captureFromChat(owner, dimension, x, y, z, prompt);
        if (entry == null) return false;
        if (active && session != null) {
            session.addNote("Memory: [" + entry.getKind() + "] " + entry.getName()
                    + (entry.isHasPosition() ? " " + entry.positionSummary() : ""));
        }
        this.manager.getLogger().log("MEMORY", ctrl, "Remembered " + entry.getKind() + " '" + entry.getName() + "'",
                entry.getText() + (entry.isHasPosition() ? " @ " + entry.positionSummary() : ""));
        StringBuilder reply = new StringBuilder("Remembered ");
        reply.append(entry.getKind()).append(" '").append(entry.getName()).append('\'');
        if (entry.isHasPosition()) reply.append(" at ").append(entry.positionSummary());
        reply.append(active ? ". I'll keep working on the current task." : ".");
        this.manager.droneSay(player, ctrl, reply.toString());
        return true;
    }

    private void cancel(DroneController ctrl) {
        this.manager.getLogger().log("TASK", ctrl, "Message classified as CANCEL", "reason=Player cancelled active task");
        DroneSession session = session(ctrl);
        session.cancelOtherLoops(0);
        this.manager.clearActionState(ctrl);
        session.cancel();
        this.manager.getNavigationManager().stop(ctrl, null, "Player cancelled active task");
    }

    private DroneSession session(DroneController ctrl) {
        return this.manager.getSessionManager().session(ctrl);
    }

    private String shortTask(DroneController ctrl) {
        DroneSession session = session(ctrl);
        String summary = session.getSummary();
        if (summary == null || summary.isEmpty()) summary = session.getPrompt();
        if (summary == null || summary.isEmpty()) return "Task is running.";
        return "Task: " + (summary.length() > 120 ? summary.substring(0, 120) + "..." : summary);
    }

    private String activeTaskReply(DroneController ctrl) {
        StringBuilder sb = new StringBuilder();
        sb.append(shortTask(ctrl));
        if (ctrl.drone != null) {
            sb.append(String.format(" Position: (%.1f, %.1f, %.1f).",
                    ctrl.drone.posX, ctrl.drone.posY, ctrl.drone.posZ));
        }
        sb.append(" I keep working on it; ask again for status, say 'by the way ...' to note something, or say 'stop' to cancel.");
        return sb.toString();
    }
}
