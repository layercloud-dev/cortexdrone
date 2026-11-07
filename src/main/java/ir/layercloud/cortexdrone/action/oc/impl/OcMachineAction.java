package ir.layercloud.cortexdrone.action.oc.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.oc.OcRequest;
import ir.layercloud.cortexdrone.action.oc.OcResult;
import ir.layercloud.cortexdrone.context.impl.OcContext;
import ir.layercloud.cortexdrone.service.oc.OcComponentIntrospector;
import ir.layercloud.cortexdrone.service.oc.OcStateService;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the OpenComputers machine lifecycle: start, stop, pause and signal.
 *
 * <p>These are the writes the OC API exposes on the machine side. Callback calls
 * live in {@link OcCallAction}; keeping them apart means a machine control never
 * has to guess a callback name.</p>
 */
@RequiredArgsConstructor
public final class OcMachineAction implements Action<OcContext> {
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
        Object machine = this.state.machine(drone);
        if (machine == null) {
            context.result(OcResult.failure("This drone has no OpenComputers machine."));
            return;
        }
        String control = this.request.getControl() != null
                ? this.request.getControl().trim().toLowerCase() : "";
        try {
            Object value;
            switch (control) {
                case "start":
                    value = call(machine, "start");
                    break;
                case "stop":
                    value = call(machine, "stop");
                    break;
                case "pause":
                    value = call(machine, "pause", doubleValue(this.request.getValue(), 1.0));
                    break;
                case "signal":
                    value = signal(machine, this.request.getValue(), this.request.getPayload());
                    break;
                default:
                    context.result(OcResult.failure(
                            "oc_machine control must be start, stop, pause or signal.",
                            "control", control));
                    return;
            }
            context.result(OcResult.success(control, value,
                    value == null ? null : java.util.Collections.singletonList(value),
                    "control", control));
        } catch (Throwable failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            context.result(OcResult.failure(
                    "OpenComputers machine '" + control + "' failed: "
                            + cause.getClass().getSimpleName()
                            + (cause.getMessage() != null ? ": " + cause.getMessage() : ""),
                    "control", control));
        }
    }

    /**
     * OC's {@code signal} takes a Scala {@code Seq}. Build the same flat map
     * shape the Lua runtime uses and let Scala's Java converters accept it.
     */
    private static Object signal(Object machine, String name, List<Object> payload) throws Exception {
        String signal = name != null && !name.trim().isEmpty() ? name.trim() : "cortexdrone";
        Object seq = scalaSeq(payload);
        Method method = findSignal(machine.getClass());
        if (method == null) throw new NoSuchMethodException("signal(String, Seq)");
        method.setAccessible(true);
        return method.invoke(machine, signal, seq);
    }

    private static Method findSignal(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (!"signal".equals(method.getName())) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 2 && parameters[0] == String.class) return method;
        }
        return null;
    }

    /** Uses scala.collection.JavaConverters when present, else a bare array. */
    private static Object scalaSeq(List<Object> payload) {
        Map<String, Object> table = new LinkedHashMap<>();
        if (payload != null) {
            for (int i = 0; i < payload.size(); i++) {
                table.put(String.valueOf(i + 1), payload.get(i));
            }
        }
        try {
            Class<?> converters = Class.forName("scala.collection.JavaConverters$");
            Object module = converters.getField("MODULE$").get(null);
            Object asScala = converters.getMethod("asScalaBufferConverter", java.util.List.class)
                    .invoke(module, java.util.Collections.singletonList(table));
            Method asScalaMethod = asScala.getClass().getMethod("asScala");
            return asScalaMethod.invoke(asScala);
        } catch (Throwable ignored) {
            return table;
        }
    }

    private static Object call(Object target, String method) throws Exception {
        return call(target, method, null);
    }

    private static Object call(Object target, String method, Object argument) throws Exception {
        for (Method candidate : target.getClass().getMethods()) {
            if (!method.equals(candidate.getName())) continue;
            Class<?>[] parameters = candidate.getParameterTypes();
            if (argument == null && parameters.length == 0) {
                candidate.setAccessible(true);
                return candidate.invoke(target);
            }
            if (argument != null && parameters.length == 1
                    && (parameters[0] == double.class || parameters[0] == Double.class)) {
                candidate.setAccessible(true);
                return candidate.invoke(target, argument);
            }
        }
        throw new NoSuchMethodException(method);
    }

    private static double doubleValue(String raw, double fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try {
            return Double.parseDouble(raw.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
