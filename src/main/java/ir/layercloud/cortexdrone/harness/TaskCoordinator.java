package ir.layercloud.cortexdrone.harness;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Owns the single task/session decision: a message is either a question, a
 * side note, a task modification, or a cancellation. Only a new/modified task
 * starts an agent loop; there is no instruction queue.
 */
public final class TaskCoordinator {
    private final AIDroneManager manager;

    public TaskCoordinator(AIDroneManager manager) {
        this.manager = manager;
    }

    public void handle(final EntityPlayerMP player, final DroneController ctrl, final String prompt) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70128_L) return;
        String cleanPrompt = prompt != null ? prompt.trim() : "";
        boolean active = ctrl.task.isActive() || ctrl.hasActiveTaskOrLoop(0);
        TaskIntent intent = TaskIntentClassifier.classify(cleanPrompt, active);

        if (active) {
            if (intent == TaskIntent.QUESTION) {
                this.manager.getLogger().log("TASK", ctrl, "Message classified as QUESTION while a task is active",
                    "prompt=\"" + cleanPrompt + "\"");
                this.manager.droneSay(player, ctrl, activeTaskReply(ctrl));
                return;
            }
            if (intent == TaskIntent.BY_THE_WAY) {
                ctrl.task.addNote(cleanPrompt);
                this.manager.getLogger().log("TASK", ctrl, "Message classified as BY_THE_WAY; attached as note",
                    "prompt=\"" + cleanPrompt + "\"");
                this.manager.droneSay(player, ctrl, "Noted - keeping the current task. " + shortTask(ctrl));
                return;
            }
            if (intent == TaskIntent.CANCEL) {
                cancel(ctrl, "Player cancelled active task");
                this.manager.droneSay(player, ctrl, "Task cancelled. Hovering in place - give me a new instruction when ready.");
                return;
            }
            // MODIFY / NEW_TASK: replace the running session immediately.
            String previous = ctrl.task.getSummary();
            this.manager.getLogger().log("TASK", ctrl, "Message classified as " + intent.name() + "; replacing active task",
                "prompt=\"" + cleanPrompt + "\"");
            ctrl.cancelOtherLoops(0);
            ctrl.clearAllTaskQueues();
            ctrl.task.cancel();
            if (intent == TaskIntent.MODIFY && previous != null && !previous.isEmpty()) {
                cleanPrompt = cleanPrompt + "\n\n[Task modification. Previous task: " + previous + "]";
            }
        } else if (intent == TaskIntent.CANCEL) {
            this.manager.droneSay(player, ctrl, "No task is running. Nothing to cancel.");
            return;
        } else if (intent == TaskIntent.BY_THE_WAY) {
            ctrl.task.addNote(cleanPrompt);
            this.manager.droneSay(player, ctrl, "Noted.");
            return;
        }

        final int myLoopId = ctrl.activeLoopId.incrementAndGet();
        ctrl.task.begin(myLoopId, cleanPrompt);
        String senderName = player != null ? player.func_70005_c_() : ctrl.ownerName;
        this.manager.getLogger().log("CMD", ctrl,
            "Master-tool session from " + senderName + " (loopId=" + myLoopId + ", intent=" + intent.name() + ")",
            "prompt=\"" + cleanPrompt + "\"");

        final JsonObject worldContext = this.manager.getAgentService().buildDroneContextForLlm(player, ctrl);
        worldContext.addProperty("taskPrompt", cleanPrompt);
        worldContext.addProperty("taskIntent", intent.name());
        worldContext.addProperty("singleSession", true);
        ctrl.registerLoop(myLoopId);
        final String sessionPrompt = cleanPrompt;
        CortexDroneMod.HTTP_POOL.submit(new Runnable() {
            @Override
            public void run() {
                manager.getAgentService().runAgentLoop(player, ctrl, sessionPrompt, worldContext, myLoopId);
            }
        });
    }

    private void cancel(DroneController ctrl, String reason) {
        this.manager.getLogger().log("TASK", ctrl, "Message classified as CANCEL", "reason=" + reason);
        ctrl.cancelOtherLoops(0);
        ctrl.clearAllTaskQueues();
        ctrl.task.cancel();
        ctrl.navViaSurface = false;
        ctrl.navFinalX = Double.NaN;
        ctrl.navFinalY = Double.NaN;
        ctrl.navFinalZ = Double.NaN;
        ctrl.goalX = ctrl.drone.field_70165_t;
        ctrl.goalY = ctrl.drone.field_70163_u;
        ctrl.goalZ = ctrl.drone.field_70161_v;
        this.manager.setDroneMode(ctrl, Mode.STAY, reason);
    }

    private static String shortTask(DroneController ctrl) {
        String summary = ctrl.task.getSummary();
        if (summary == null || summary.isEmpty()) summary = ctrl.task.getPrompt();
        if (summary == null || summary.length() == 0) return "Task is running (" + ctrl.mode.name() + ").";
        return "Task: " + (summary.length() > 120 ? summary.substring(0, 120) + "..." : summary);
    }

    private static String activeTaskReply(DroneController ctrl) {
        StringBuilder sb = new StringBuilder();
        sb.append(shortTask(ctrl));
        sb.append(" Mode: ").append(ctrl.mode.name()).append('.');
        if (ctrl.drone != null) {
            sb.append(String.format(" Position: (%.1f, %.1f, %.1f).",
                ctrl.drone.field_70165_t, ctrl.drone.field_70163_u, ctrl.drone.field_70161_v));
        }
        if (ctrl.currentMineTarget != null) {
            sb.append(" Breaking block (").append(ctrl.currentMineTarget[0]).append(',')
                .append(ctrl.currentMineTarget[1]).append(',').append(ctrl.currentMineTarget[2]).append("). ");
        }
        sb.append(" I keep working on it; ask again for status, say 'by the way ...' to note something, or say 'stop' to cancel.");
        return sb.toString();
    }
}
