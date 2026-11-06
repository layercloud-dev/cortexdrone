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
 * Long-lived manager for one action category.
 *
 * <p>The service owns the request boundary and the action lifecycle:</p>
 * <ol>
 *   <li>create a fresh context,</li>
 *   <li>create a single-use {@link Action} from the request,</li>
 *   <li>execute it against the context,</li>
 *   <li>serialize the result,</li>
 *   <li>retain the context for ticking or discard it.</li>
 * </ol>
 *
 * <p>Services never implement {@link Action}. They manage actions.</p>
 */
public abstract class ActionService<C extends ActionContext> {
    private final String category;
    private final String[] operations;
    private final Map<DroneController, Map<String, C>> active =
        new IdentityHashMap<DroneController, Map<String, C>>();

    protected ActionService(String category, String... operations) {
        this.category = category;
        this.operations = operations.clone();
    }

    public final String category() {
        return this.category;
    }

    public final String[] operations() {
        return this.operations.clone();
    }

    protected abstract C createContext(DroneController controller, EntityPlayerMP player,
                                       String operation);

    protected abstract Action<C> createAction(C context, String operation, JsonObject args);

    protected abstract JsonObject serialize(C context);

    public final JsonObject handle(DroneController controller, EntityPlayerMP player,
                                   String operation, JsonObject args) {
        if (!supports(operation)) {
            return ActionJson.error("Unsupported " + this.category + " action: " + operation);
        }

        C context = createContext(controller, player, operation);
        try {
            Action<C> action = createAction(context, operation, ActionArgs.safe(args));
            action.execute(context);
            JsonObject result = serialize(context);
            if (context.isRetained()) {
                retain(controller, operation, context);
            } else {
                context.complete();
            }
            return result;
        } catch (Throwable failure) {
            context.cancel();
            throw failure;
        }
    }

    public final void tick(DroneController controller) {
        Map<String, C> contexts = this.active.get(controller);
        if (contexts == null) return;

        for (C context : new ArrayList<C>(contexts.values())) {
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

        Iterator<Map.Entry<String, C>> it = contexts.entrySet().iterator();
        while (it.hasNext()) {
            C context = it.next().getValue();
            if (!context.isActive() || !context.isRetained()) {
                context.complete();
                it.remove();
            }
        }
        if (contexts.isEmpty()) this.active.remove(controller);
    }

    public final void cancelAll(DroneController controller) {
        Map<String, C> contexts = this.active.remove(controller);
        if (contexts == null) return;
        for (C context : contexts.values()) context.cancel();
    }

    public final boolean supports(String operation) {
        if (operation == null) return false;
        for (String candidate : this.operations) {
            if (candidate.equals(operation)) return true;
        }
        return false;
    }

    public final int activeContextCount(DroneController controller) {
        Map<String, C> contexts = this.active.get(controller);
        return contexts != null ? contexts.size() : 0;
    }

    private void retain(DroneController controller, String operation, C context) {
        Map<String, C> contexts = this.active.get(controller);
        if (contexts == null) {
            contexts = new LinkedHashMap<String, C>();
            this.active.put(controller, contexts);
        }
        C previous = contexts.put(operation, context);
        if (previous != null && previous != context) previous.cancel();
    }
}
