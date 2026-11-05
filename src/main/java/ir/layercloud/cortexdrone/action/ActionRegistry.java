package ir.layercloud.cortexdrone.action;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.model.DroneController;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Resolves a master operation to its categorized {@link Action} and owns the
 * live action contexts.
 *
 * <p>Every invocation gets a fresh context. One-shot contexts are discarded as
 * soon as their RPC call returns. Retained contexts are ticked on the server
 * thread and removed when they complete, the drone disappears or a newer
 * invocation of the same operation replaces them.</p>
 */
public final class ActionRegistry {
    private final Map<String, Binding<?>> byOperation = new LinkedHashMap<String, Binding<?>>();
    private final Map<DroneController, Map<String, ActionContext>> active =
        new IdentityHashMap<DroneController, Map<String, ActionContext>>();

    public <C extends ActionContext> void register(Action<C> action) {
        if (action == null) return;
        String[] operations = action.operations();
        if (operations == null) return;
        for (String operation : operations) {
            if (operation == null || operation.isEmpty()) continue;
            Binding<C> binding = new Binding<C>(action);
            Binding<?> previous = this.byOperation.put(operation, binding);
            if (previous != null && previous.action != action) {
                throw new IllegalStateException("Duplicate action operation '" + operation
                    + "' in " + previous.action.category() + " and " + action.category());
            }
        }
    }

    public JsonObject handle(DroneController ctrl, EntityPlayerMP player,
                             String operation, JsonObject args) {
        Binding<?> binding = operation != null ? this.byOperation.get(operation) : null;
        if (binding == null) return error("No action registered for: " + operation);
        return binding.invoke(this, ctrl, player, operation, args);
    }

    /** Ticks retained contexts and discards the finished ones. */
    public void tick(DroneController ctrl) {
        Map<String, ActionContext> contexts = this.active.get(ctrl);
        if (contexts == null) return;
        for (ActionContext context : new ArrayList<ActionContext>(contexts.values())) {
            if (!context.isRetained()) {
                context.complete();
                continue;
            }
            try {
                context.tick();
            } catch (Throwable ignored) {
                context.cancel();
            }
        }
        Iterator<Map.Entry<String, ActionContext>> it = contexts.entrySet().iterator();
        while (it.hasNext()) {
            ActionContext context = it.next().getValue();
            if (!context.isActive() || !context.isRetained()) {
                context.cancel();
                it.remove();
            }
        }
        if (contexts.isEmpty()) this.active.remove(ctrl);
    }

    /** Cancels every live context owned by one controller. */
    public void cancelAll(DroneController ctrl) {
        Map<String, ActionContext> contexts = this.active.remove(ctrl);
        if (contexts == null) return;
        for (ActionContext context : contexts.values()) context.cancel();
    }

    public Action<?> find(String operation) {
        Binding<?> binding = operation != null ? this.byOperation.get(operation) : null;
        return binding != null ? binding.action : null;
    }

    /** Number of retained contexts for diagnostics and tests. */
    public int activeContextCount(DroneController ctrl) {
        Map<String, ActionContext> contexts = this.active.get(ctrl);
        return contexts != null ? contexts.size() : 0;
    }

    private static JsonObject error(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", message);
        return result;
    }

    private static final class Binding<C extends ActionContext> {
        private final Action<C> action;

        Binding(Action<C> action) {
            this.action = action;
        }

        JsonObject invoke(ActionRegistry registry, DroneController ctrl, EntityPlayerMP player,
                          String operation, JsonObject args) {
            Map<String, ActionContext> contexts = registry.active.get(ctrl);
            if (contexts == null) {
                contexts = new LinkedHashMap<String, ActionContext>();
                registry.active.put(ctrl, contexts);
            }
            ActionContext previous = contexts.remove(operation);
            if (previous != null) previous.cancel();

            C context = this.action.createContext(ctrl, player, operation);
            JsonObject result;
            try {
                result = this.action.handle(context, operation, args);
            } catch (Throwable t) {
                context.cancel();
                throw t;
            }
            if (context.isRetained()) {
                contexts.put(operation, context);
            } else {
                context.complete();
            }
            if (contexts.isEmpty()) registry.active.remove(ctrl);
            return result;
        }
    }
}
