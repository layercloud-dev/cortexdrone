package ir.layercloud.cortexdrone.action;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.ActionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Resolves master operations to their category service.
 *
 * <p>The registry knows only services. Contexts, ticking and cancellation live
 * in the service that owns the action category.</p>
 */
public final class ActionRegistry {
    private final Map<String, ActionService<?>> byOperation =
        new LinkedHashMap<String, ActionService<?>>();
    private final List<ActionService<?>> services = new ArrayList<ActionService<?>>();

    public void register(ActionService<?> service) {
        if (service == null) return;
        for (String operation : service.operations()) {
            ActionService<?> previous = this.byOperation.put(operation, service);
            if (previous != null && previous != service) {
                throw new IllegalStateException("Duplicate action operation '" + operation
                    + "' in " + previous.category() + " and " + service.category());
            }
        }
        this.services.add(service);
    }

    public JsonObject handle(DroneController controller, EntityPlayerMP player,
                             String operation, JsonObject args) {
        ActionService<?> service = operation != null ? this.byOperation.get(operation) : null;
        if (service == null) return ActionJson.error("No action registered for: " + operation);
        return service.handle(controller, player, operation, args);
    }

    public void tick(DroneController controller) {
        for (ActionService<?> service : this.services) {
            service.tick(controller);
        }
    }

    public void cancelAll(DroneController controller) {
        for (ActionService<?> service : this.services) {
            service.cancelAll(controller);
        }
    }

    public ActionService<?> find(String operation) {
        return operation != null ? this.byOperation.get(operation) : null;
    }

    public int activeContextCount(DroneController controller) {
        int count = 0;
        for (ActionService<?> service : this.services) {
            count += service.activeContextCount(controller);
        }
        return count;
    }
}
