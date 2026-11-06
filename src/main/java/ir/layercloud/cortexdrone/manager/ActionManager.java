package ir.layercloud.cortexdrone.manager;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.context.ActionContext;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.*;

/**
 * Long-lived manager for one action category.
 *
 * <p>The manager owns the action lifecycle but no JSON. A transport translator
 * parses RPC/HTTP input into {@code R} and serializes the returned {@code T}.
 * The manager only creates a context, creates one action, executes it, returns
 * the typed result and retains/reaps the action/context pair.</p>
 */
public abstract class ActionManager<C extends ActionContext, R, T> {
    private final String category;
    private final String[] operations;
    private final Map<DroneController, Map<String, Execution<C>>> active =
            new IdentityHashMap<>();

    protected ActionManager(String category, String... operations) {
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

    protected abstract Action<C> createAction(C context, String operation, R request);

    protected abstract T result(C context);

    public final T handle(DroneController controller, EntityPlayerMP player,
                          String operation, R request) {
        if (!supports(operation)) {
            throw new IllegalArgumentException("Unsupported " + this.category
                    + " action: " + operation);
        }

        // A new call for an operation always replaces the previous execution,
        // even when the new one is one-shot. Otherwise a retained action (LED
        // animation, light aura, delivery flight) could keep running after a
        // newer command already replaced its effect.
        cancelExisting(controller, operation);

        C context = createContext(controller, player, operation);
        Action<C> action = null;
        try {
            action = createAction(context, operation, request);
            action.execute(context);
            T result = result(context);
            if (context.isRetained()) {
                retain(controller, operation, new Execution<>(action, context));
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

    /**
     * Typed execution used by internal entry points that already built the action.
     */
    protected final void run(DroneController controller,
                             String operation, C context, Action<C> action) {
        cancelExisting(controller, operation);
        try {
            action.execute(context);
            if (context.isRetained()) {
                retain(controller, operation, new Execution<>(action, context));
            } else {
                context.complete();
            }
        } catch (Throwable failure) {
            action.cancel(context);
            context.cancel();
            throw failure;
        }
    }

    public final void tick(DroneController controller) {
        Map<String, Execution<C>> executions = this.active.get(controller);
        if (executions == null) return;

        for (Execution<C> execution : new ArrayList<>(executions.values())) {
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

    private void cancelExisting(DroneController controller, String operation) {
        Map<String, Execution<C>> executions = this.active.get(controller);
        if (executions == null) return;
        Execution<C> previous = executions.remove(operation);
        if (previous == null) return;
        previous.action.cancel(previous.context);
        previous.context.cancel();
        if (executions.isEmpty()) this.active.remove(controller);
    }

    private void retain(DroneController controller, String operation, Execution<C> execution) {
        Map<String, Execution<C>> executions = this.active.computeIfAbsent(controller, k -> new LinkedHashMap<>());
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
