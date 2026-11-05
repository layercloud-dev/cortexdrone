package ir.layercloud.cortexdrone.action;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.model.DroneController;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Common contract for categorized actions.
 *
 * <p>{@code C} is the category's lifecycle context. It is created for one
 * invocation, mutated by the action while the operation runs, and discarded
 * once the operation finishes. A context that outlives the RPC call (for
 * example a navigation request) opts in with {@link ActionContext#retain()}
 * and is ticked by {@link ActionRegistry} until it completes.</p>
 *
 * <p>JSON stays at this RPC boundary. Category internals should use typed
 * requests/results, as movement does with {@code MovementRequest} and
 * {@code MovementResult}.</p>
 */
public interface Action<C extends ActionContext> {
    /** Human-readable category, e.g. block, interact, effect, movement. */
    String category();

    /** Canonical operation names handled by this action. */
    String[] operations();

    /** Creates the category context for one operation invocation. */
    C createContext(DroneController controller, EntityPlayerMP player, String operation);

    /** Executes one operation against its context. */
    JsonObject handle(C context, String operation, JsonObject args);
}
