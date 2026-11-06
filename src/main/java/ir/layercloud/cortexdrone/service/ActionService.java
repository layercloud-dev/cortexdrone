package ir.layercloud.cortexdrone.service;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.ActionArgs;
import ir.layercloud.cortexdrone.action.ActionJson;
import ir.layercloud.cortexdrone.context.ActionContext;
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
 *   <li>retain the action/context pair for ticking or release it.</li>
 * </ol>
 *
 * <p>Services never implement {@link Action} and contain no operation logic.
 * They manage actions; action classes do the work.</p>
 */
public abstract class ActionService<C extends ActionContext> {
    private final String category;
    private final String[] operations;
    private final Map<DroneController, Map<String, Execution<C>>> active =
        new IdentityHashMap<DroneController, Map<String, Execution<C>>>();

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
        Action<C> action = null;
        try {
            action = createAction(context, operation, ActionArgs.safe(args));
            action.execute(context);
            JsonObject result = serialize(context);
            if (context.isRetained()) {
                retain(controller, operation, new Execution<C>(action, context));
            } else {
                context.complete();
            }
            return result;
        } catch (Throwable failure) {
            if (action != null) action.cancel(context);
            context.cancel();
            throw failure;
        }
    }

    public final void tick(DroneController controller) {
        Map<String, Execution<C>> executions = this.active.get(controller);
        if (executions == null) return;

        for (Execution<C> execution : new ArrayList<Execution<C>>(executions.values())) {
            if (!execution.context.isRetained()) {
                execution.context.complete();
                continue;
            }
            try {
                execution.action.tick(execution.context);
            } catch (Throwable ignored) {
                execution.action.cancel(execution.context);
                execution.context.cancel();
            }
        }

        Iterator<Map.Entry<String, Execution<C>>> it = executions.entrySet().iterator();
        while (it.hasNext()) {
            Execution<C> execution = it.next().getValue();
            if (!execution.context.isActive() || !execution.context.isRetained()) {
                execution.context.complete();
                it.remove();
            }
        }
        if (executions.isEmpty()) this.active.remove(controller);
    }

    public final void cancelAll(DroneController controller) {
        Map<String, Execution<C>> executions = this.active.remove(controller);
        if (executions == null) return;
        for (Execution<C> execution : executions.values()) {
            execution.action.cancel(execution.context);
            execution.context.cancel();
        }
    }

    public final boolean supports(String operation) {
        if (operation == null) return false;
        for (String candidate : this.operations) {
            if (candidate.equals(operation)) return true;
        }
        return false;
    }

    public final int activeContextCount(DroneController controller) {
        Map<String, Execution<C>> executions = this.active.get(controller);
        return executions != null ? executions.size() : 0;
    }

    private void retain(DroneController controller, String operation, Execution<C> execution) {
        Map<String, Execution<C>> executions = this.active.get(controller);
        if (executions == null) {
            executions = new LinkedHashMap<String, Execution<C>>();
            this.active.put(controller, executions);
        }
        Execution<C> previous = executions.put(operation, execution);
        if (previous != null && previous != execution) {
            previous.action.cancel(previous.context);
            previous.context.cancel();
        }
    }

    private static final class Execution<C extends ActionContext> {
        private final Action<C> action;
        private final C context;

        private Execution(Action<C> action, C context) {
            this.action = action;
            this.context = context;
        }
    }
}
