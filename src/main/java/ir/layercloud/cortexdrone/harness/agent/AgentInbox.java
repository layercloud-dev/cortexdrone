package ir.layercloud.cortexdrone.harness.agent;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.harness.session.DroneSession;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.List;

/**
 * Delivers player chat that arrived while an agent loop was working.
 *
 * <p>The running model is the only router: there is no keyword, regex or
 * intent classifier between the player and the conversation. Each line is
 * appended as a user message with its provenance, so the model can answer it,
 * adjust the plan or cancel.</p>
 */
final class AgentInbox {
    private AgentInbox() {
    }

    static void drain(DroneSession session, Conversation conversation, DroneLogger logger,
                      DroneController controller, int loopId, int turn) {
        List<String> messages = session.drainMessages();
        for (String message : messages) {
            logger.log("TASK", controller, "Player message delivered to the running loop",
                    "loop=" + loopId + " turn=" + turn + " text=\""
                            + LoopTracer.snippet(message, 200) + "\"");
            conversation.user("The player says while you are working: \"" + message + "\". "
                    + "Treat it as part of this task: answer it, adjust your plan, or call "
                    + "cancel if they asked you to stop. Do not ignore a change.");
        }
    }

    /**
     * A line that arrived after the last inbox check would be lost when the
     * generation ends. Hand it to the coordinator on the server thread exactly
     * like a fresh chat line.
     */
    static void requeueLate(final EntityPlayerMP player, final DroneController controller,
                            final DroneSession session, final AIDroneManager manager) {
        if (controller == null || controller.drone == null || controller.drone.isDead) return;
        if (!session.hasQueuedMessages()) return;
        final List<String> pending = session.drainMessages();
        manager.getMod().queueMain(() -> {
            for (String message : pending) {
                manager.getTaskCoordinator().handle(player, controller, message);
            }
        });
    }
}
