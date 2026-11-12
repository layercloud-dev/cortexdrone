package ir.layercloud.cortexdrone.action.oc.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.oc.OcArguments;
import ir.layercloud.cortexdrone.action.oc.OcRequest;
import ir.layercloud.cortexdrone.action.oc.OcResult;
import ir.layercloud.cortexdrone.context.impl.OcContext;
import ir.layercloud.cortexdrone.service.oc.OcComponentIntrospector;
import ir.layercloud.cortexdrone.service.oc.OcMethod;
import ir.layercloud.cortexdrone.service.oc.OcStateService;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * Invokes one OpenComputers component callback on the drone.
 *
 * <p>The method must exist in the live {@code @Callback} surface, so this action
 * cannot call arbitrary Java. That is the whole point: the full OC API becomes
 * available, and nothing beyond it does.</p>
 */
@RequiredArgsConstructor
public final class OcCallAction implements Action<OcContext> {
    private final OcRequest request;
    private final OcStateService state;

    @Override
    public void execute(OcContext context) {
        DroneController controller = context.controller();
        Drone drone = controller != null ? controller.drone : null;
        if (drone == null || drone.worldObj == null) {
            context.result(OcResult.failure("No active drone or world."));
            return;
        }
        if (!OcComponentIntrospector.available()) {
            context.result(OcResult.failure("OpenComputers is not loaded on this server."));
            return;
        }
        String method = this.request.getMethod();
        if (method == null || method.trim().isEmpty()) {
            context.result(OcResult.failure("oc_call requires a method name."));
            return;
        }
        Object component = this.state.component(drone);
        if (component == null) {
            context.result(OcResult.failure("This drone exposes no OpenComputers component."));
            return;
        }
        OcMethod declared = OcComponentIntrospector.method(component, method);
        if (declared == null) {
            context.result(OcResult.failure(
                    "OpenComputers component has no callback '" + method + "'.",
                    "hint", "Use oc_methods to list the callbacks this drone exposes."));
            return;
        }
        Object arguments = OcArguments.of(this.request.getArguments()).proxy();
        try {
            Object raw = OcComponentIntrospector.invoke(component, method,
                    this.state.context(drone), arguments);
            List<Object> values = tuple(raw);
            context.result(OcResult.success(method,
                    values.isEmpty() ? null : values.get(0), values,
                    "declared_by", declared.owner(),
                    "direct", declared.direct()));
        } catch (Throwable failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            String message = cause.getMessage() != null ? cause.getMessage() : "";
            String hint = "method";
            if ("invalid side".equalsIgnoreCase(message) || "unsupported side".equalsIgnoreCase(message)) {
                hint = "Pass a side integer 0..5 (down=0, up=1, north=2, south=3, west=4, east=5) "
                        + "or a side name such as \"down\". The drone's drop signature is drop(side, count); "
                        + "select(slot) first for inventory slots.";
            } else if ("invalid slot".equalsIgnoreCase(message)) {
                hint = "Inventory slots are 1-based: select(1) is the first slot. state.inventory "
                        + "entries expose ocSlot for exactly this.";
            }
            context.result(OcResult.failure(
                    "OpenComputers callback '" + method + "' failed: "
                            + cause.getClass().getSimpleName()
                            + (message.isEmpty() ? "" : ": " + message),
                    "method", method,
                    "hint", hint));
        }
    }

    /**
     * OC callbacks return {@code Object[]}; a few helpers return a single value.
     */
    private static List<Object> tuple(Object raw) {
        List<Object> values = new ArrayList<>();
        if (raw == null) return values;
        if (raw instanceof Object[]) {
            for (Object value : (Object[]) raw) values.add(value);
            return values;
        }
        if (raw.getClass().isArray()) {
            int length = Array.getLength(raw);
            for (int i = 0; i < length; i++) values.add(Array.get(raw, i));
            return values;
        }
        values.add(raw);
        return values;
    }
}
