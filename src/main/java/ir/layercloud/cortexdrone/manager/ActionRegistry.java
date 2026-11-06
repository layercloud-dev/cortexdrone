package ir.layercloud.cortexdrone.manager;

import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.manager.ActionManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves master operations to their category service.
 *
 * <p>The registry knows only services. JSON translation lives in the RPC/HTTP
 * transport layer; contexts, ticking and cancellation live in the service that
 * owns the action category.</p>
 */
public final class ActionRegistry {
    private final Map<String, ActionManager<?, ?, ?>> byOperation =
        new LinkedHashMap<String, ActionManager<?, ?, ?>>();
    private final List<ActionManager<?, ?, ?>> services =
        new ArrayList<ActionManager<?, ?, ?>>();

    public void register(ActionManager<?, ?, ?> service) {
        if (service == null) return;
        for (String operation : service.operations()) {
            ActionManager<?, ?, ?> previous = this.byOperation.put(operation, service);
            if (previous != null && previous != service) {
                throw new IllegalStateException("Duplicate action operation '" + operation
                    + "' in " + previous.category() + " and " + service.category());
            }
        }
        this.services.add(service);
    }

    public void tick(DroneController controller) {
        for (ActionManager<?, ?, ?> service : this.services) {
            service.tick(controller);
        }
    }

    public void cancelAll(DroneController controller) {
        for (ActionManager<?, ?, ?> service : this.services) {
            service.cancelAll(controller);
        }
    }

    public ActionManager<?, ?, ?> find(String operation) {
        return operation != null ? this.byOperation.get(operation) : null;
    }

    public int activeContextCount(DroneController controller) {
        int count = 0;
        for (ActionManager<?, ?, ?> service : this.services) {
            count += service.activeContextCount(controller);
        }
        return count;
    }
}
