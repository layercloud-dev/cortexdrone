package ir.layercloud.cortexdrone.manager;

import ir.layercloud.cortexdrone.DroneController;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves action operations to their category manager.
 *
 * <p>The registry knows only managers. JSON translation lives in the tool/HTTP
 * transport layer; contexts, ticking and cancellation live in the manager that
 * owns the action category.</p>
 */
public final class ActionRegistry {
    private final Map<String, ActionManager<?, ?, ?>> byOperation =
            new LinkedHashMap<>();
    private final List<ActionManager<?, ?, ?>> managers =
            new ArrayList<>();

    public void register(ActionManager<?, ?, ?> manager) {
        if (manager == null) return;
        for (String operation : manager.operations()) {
            ActionManager<?, ?, ?> previous = this.byOperation.put(operation, manager);
            if (previous != null && previous != manager) {
                throw new IllegalStateException("Duplicate action operation '" + operation
                        + "' in " + previous.category() + " and " + manager.category());
            }
        }
        this.managers.add(manager);
    }

    public void tick(DroneController controller) {
        for (ActionManager<?, ?, ?> manager : this.managers) {
            manager.tick(controller);
        }
    }

    public void cancelAll(DroneController controller) {
        for (ActionManager<?, ?, ?> manager : this.managers) {
            manager.cancelAll(controller);
        }
    }

    public int activeContextCount(DroneController controller) {
        int count = 0;
        for (ActionManager<?, ?, ?> manager : this.managers) {
            count += manager.activeContextCount(controller);
        }
        return count;
    }
}
