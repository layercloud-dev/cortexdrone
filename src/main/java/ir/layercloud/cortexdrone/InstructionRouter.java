package ir.layercloud.cortexdrone;

import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.harness.task.TaskCoordinator;
import ir.layercloud.cortexdrone.harness.tool.Threads;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.concurrent.Callable;

/**
 * Routes a player's request to the agent, on the server thread.
 *
 * <p>Extracted from {@link AIDroneManager}. The rules are: parse the chat
 * prefix, resolve (or auto-spawn) the sender's drone, hop to the server thread
 * if needed, then hand off to {@link TaskCoordinator}. Those are one workflow,
 * so they live together instead of inside the manager's event handlers.</p>
 */
final class InstructionRouter {
    /** How long an instruction may wait for the server thread. */
    private static final long DISPATCH_TIMEOUT_MS = 5_000L;

    /** Everything the router needs from the manager. */
    interface Host {
        DroneController droneFor(String playerName);

        DroneController linkNearest(EntityPlayerMP player);

        DroneController spawn(EntityPlayerMP player, String customName);

        void say(EntityPlayerMP player, DroneController controller, String message);

        TaskCoordinator taskCoordinator();

        DroneLogger logger();

        /** Runs a task on the server thread and waits for it. */
        <T> T onMainThread(Callable<T> task, long timeoutMs) throws Exception;
    }

    private final Host host;

    InstructionRouter(Host host) {
        this.host = host;
    }

    /** Handles a chat line; ignores lines that are not addressed to a drone. */
    void onChat(EntityPlayerMP player, String message) {
        String droneName = null;
        DroneController owned = this.host.droneFor(player.getCommandSenderName());
        if (owned != null) droneName = owned.name;
        String prompt = ChatInstruction.parse(message, droneName);
        if (prompt == null || prompt.isEmpty()) return;
        handle(player, prompt);
    }

    /** Handles one instruction for the sender's drone, auto-spawning if needed. */
    void handle(EntityPlayerMP player, String prompt) {
        if (!Threads.isServerThread()) {
            dispatch(() -> handle(player, prompt));
            return;
        }
        DroneController controller = player != null
                ? this.host.droneFor(player.getCommandSenderName()) : null;
        if (controller == null && player != null) {
            controller = this.host.linkNearest(player);
            if (controller == null) {
                controller = this.host.spawn(player, null);
                this.host.say(player, controller, "Spawned and linked! Ready for your command.");
            }
        }
        handle(player, controller, prompt);
    }

    /** Handles one instruction for an explicit drone. */
    void handle(EntityPlayerMP player, DroneController controller, String prompt) {
        if (!Threads.isServerThread()) {
            dispatch(() -> handle(player, controller, prompt));
            return;
        }
        this.host.taskCoordinator().handle(player, controller, prompt);
    }

    /** Runs {@code task} on the server thread, logging a failed hop. */
    private void dispatch(Runnable task) {
        try {
            this.host.onMainThread((Callable<Void>) () -> {
                task.run();
                return null;
            }, DISPATCH_TIMEOUT_MS);
        } catch (Exception failure) {
            this.host.logger().log("ERROR", null,
                    "Failed to dispatch instruction to server thread", failure.toString());
        }
    }
}
