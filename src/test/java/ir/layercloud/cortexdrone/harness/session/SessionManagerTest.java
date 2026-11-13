package ir.layercloud.cortexdrone.harness.session;

import ir.layercloud.cortexdrone.DroneController;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * The session map must not grow back after a controller is forgotten.
 *
 * <p>{@code ToolContext.cancelled()} runs on every tool call; when it used
 * {@code session()} it recreated the map entry for a drone that had just been
 * despawned and forgotten, leaking the controller (and its session) for the
 * lifetime of the server.</p>
 */
public class SessionManagerTest {
    private static DroneController controller() {
        return DroneController.forTest(1, "drone1", "steve");
    }

    @Test
    public void peekDoesNotCreateASession() {
        SessionManager manager = new SessionManager();
        DroneController controller = controller();

        assertNull(manager.peek(controller));
        assertNull("the map stays empty", manager.peek(controller));

        DroneSession created = manager.session(controller);
        assertNotNull(created);
        assertSame(created, manager.peek(controller));
    }

    @Test
    public void forgettingAControllerCannotBeUndoneByAReadOnlyCheck() {
        SessionManager manager = new SessionManager();
        DroneController controller = controller();
        manager.session(controller).begin(1, "mine stone");

        manager.forget(controller);
        assertNull(manager.peek(controller));
        assertNull("forget() must not be resurrected by peek()", manager.peek(controller));
    }
}
