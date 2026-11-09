package ir.layercloud.cortexdrone.harness.task;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.harness.agent.AgentService;
import ir.layercloud.cortexdrone.harness.session.DroneSession;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Owns the single task/session decision: a message either starts a new
 * generation, or is delivered to the running generation.
 *
 * <p>There is deliberately no keyword or regex routing here. A running loop's
 * own model decides what a later message means — a question, a note, a change
 * or a cancellation — because only it knows the task and the evidence it has
 * gathered. The coordinator only decides whether a loop is alive.</p>
 */
@RequiredArgsConstructor
public final class TaskCoordinator {
    private final AIDroneManager manager;

    public void handle(final EntityPlayerMP player, final DroneController ctrl, final String prompt) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.isDead) return;
        String cleanPrompt = prompt != null ? prompt.trim() : "";
        if (cleanPrompt.isEmpty()) return;

        DroneSession session = session(ctrl);
        if (session.hasActiveTaskOrLoop(0)) {
            // The running generation owns the conversation. Queueing keeps one
            // task and lets the model interpret the message with full context.
            session.addNote(cleanPrompt);
            session.enqueueMessage(cleanPrompt);
            this.manager.getLogger().log("TASK", ctrl,
                    "Message queued for the running loop; the model decides what it means",
                    "prompt=\"" + cleanPrompt + "\"");
            return;
        }

        final int myLoopId = session.nextLoopId();
        session.begin(myLoopId, cleanPrompt);
        String senderName = player != null ? player.getCommandSenderName() : ctrl.ownerName;
        this.manager.getLogger().log("CMD", ctrl,
                "Task session from " + senderName + " (loopId=" + myLoopId + ")",
                "prompt=\"" + cleanPrompt + "\"");

        final AgentService agent = this.manager.getAgentService();
        final String contextMessage = agent.buildContext(player, ctrl, cleanPrompt, "MESSAGE");
        session.registerLoop(myLoopId);
        final int loopId = myLoopId;
        CortexDroneMod.AGENT_POOL.submit(() -> agent.runAgentLoop(
                player, ctrl, cleanPrompt, contextMessage, loopId, "MESSAGE"));
    }

    /** Stops the active generation immediately; used by {@code /drone stop}. */
    public void stop(DroneController ctrl) {
        if (ctrl == null) return;
        DroneSession session = session(ctrl);
        session.cancelOtherLoops(0, "player_cancel");
        this.manager.clearActionState(ctrl);
        session.cancel();
        this.manager.getNavigationManager().stop(ctrl, null, "Player stopped the task");
    }

    private DroneSession session(DroneController ctrl) {
        return this.manager.getSessionManager().session(ctrl);
    }
}
