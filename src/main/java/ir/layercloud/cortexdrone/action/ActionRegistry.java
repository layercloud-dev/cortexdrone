package ir.layercloud.cortexdrone.action;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.model.DroneController;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Resolves a master action name to the categorized {@link Action} that owns
 * it. Registration order is deterministic; duplicate operations are rejected
 * so two categories can never silently shadow each other.
 */
public final class ActionRegistry {
    private final List<Action> actions = new ArrayList<Action>();
    private final Map<String, Action> byOperation = new LinkedHashMap<String, Action>();

    public void register(Action action) {
        if (action == null) return;
        this.actions.add(action);
        String[] operations = action.operations();
        if (operations == null) return;
        for (String operation : operations) {
            if (operation == null || operation.isEmpty()) continue;
            Action previous = this.byOperation.put(operation, action);
            if (previous != null && previous != action) {
                throw new IllegalStateException("Duplicate action operation '" + operation
                    + "' in " + previous.category() + " and " + action.category());
            }
        }
    }

    public Action find(String operation) {
        return operation != null ? this.byOperation.get(operation) : null;
    }

    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String operation, JsonObject args) {
        Action action = find(operation);
        if (action == null) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("error", "No action registered for: " + operation);
            return error;
        }
        return action.handle(ctrl, player, operation, args);
    }

}
