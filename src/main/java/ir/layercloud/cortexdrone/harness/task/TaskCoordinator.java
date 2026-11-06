package ir.layercloud.cortexdrone.harness.task;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
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

        if (active) {
            if (intent == TaskIntent.QUESTION) {
                this.manager.getLogger().log("TASK", ctrl, "Message classified as QUESTION while a task is active",
                        "prompt=\"" + cleanPrompt + "\"");
                this.manager.droneSay(player, ctrl, activeTaskReply(ctrl));
                return;
            }
            if (intent == TaskIntent.BY_THE_WAY) {
                session.addNote(cleanPrompt);
                this.manager.getLogger().log("TASK", ctrl, "Message classified as BY_THE_WAY; attached as note",
                        "prompt=\"" + cleanPrompt + "\"");
                this.manager.droneSay(player, ctrl, "Noted - keeping the current task. " + shortTask(ctrl));
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
            this.manager.droneSay(player, ctrl, "Noted.");
            return;
        }

        final int myLoopId = session.nextLoopId();
        session.begin(myLoopId, cleanPrompt);
        String senderName = player != null ? player.getCommandSenderName() : ctrl.ownerName;
        this.manager.getLogger().log("CMD", ctrl,
                "Master-tool session from " + senderName + " (loopId=" + myLoopId + ", intent=" + intent.name() + ")",
                "prompt=\"" + cleanPrompt + "\"");

        final JsonObject worldContext = this.manager.getAgentService().buildDroneContextForLlm(player, ctrl);
        worldContext.addProperty("taskPrompt", cleanPrompt);
        worldContext.addProperty("taskIntent", intent.name());
        worldContext.addProperty("singleSession", true);
        session.registerLoop(myLoopId);
        final String sessionPrompt = cleanPrompt;
        CortexDroneMod.HTTP_POOL.submit(() -> manager.getAgentService().runAgentLoop(player, ctrl, sessionPrompt, worldContext, myLoopId));
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
