package ir.layercloud.cortexdrone.action.movement;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves typed movement operations without knowing about RPC/JSON. */
final class MovementActionRegistry {
    private final Map<String, MovementAction> byOperation = new LinkedHashMap<String, MovementAction>();

    void register(MovementAction action) {
        if (action == null) return;
        MovementAction previous = this.byOperation.put(action.operation(), action);
        if (previous != null && previous != action) {
            throw new IllegalStateException("Duplicate movement operation: " + action.operation());
        }
    }

    MovementResult execute(String operation, MovementRequest request) {
        MovementAction action = this.byOperation.get(operation);
        if (action == null) return MovementResult.failure("No movement action for: " + operation);
        return action.execute(request);
    }
}
