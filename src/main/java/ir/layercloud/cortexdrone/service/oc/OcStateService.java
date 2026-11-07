package ir.layercloud.cortexdrone.service.oc;

import li.cil.oc.common.entity.Drone;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads OpenComputers drone and machine state for the {@code oc_*} tools.
 *
 * <p>All access is reflective or through the public OC API, so a server without
 * OpenComputers, or a different OC build, degrades to an
 * {@link OcState#unavailable} snapshot instead of failing to load this class.</p>
 */
public final class OcStateService {
    private final Field machineField;
    private final Field controlField;
    private final boolean ready;

    public OcStateService() {
        Field machine = null;
        Field control = null;
        try {
            machine = Drone.class.getDeclaredField("machine");
            machine.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            control = Drone.class.getDeclaredField("control");
            control.setAccessible(true);
        } catch (Throwable ignored) {
        }
        this.machineField = machine;
        this.controlField = control;
        this.ready = machine != null;
    }

    /** True when the drone/machine fields could be resolved. */
    public boolean ready() {
        return this.ready;
    }

    public OcState read(Drone drone) {
        if (drone == null) return OcState.unavailable("No drone entity linked.");
        if (!OcComponentIntrospector.available()) {
            return OcState.unavailable("OpenComputers is not loaded on this server.");
        }
        try {
            boolean running = callBoolean(drone, "isRunning", false);
            OcState.Builder builder = OcState.builder()
                    .address(addressOf(drone))
                    .tier(safeInt(drone, "tier", 0))
                    .statusText(safeString(drone, "statusText"))
                    .lightColor(safeInt(drone, "lightColor", 0))
                    .running(running);

            Object machine = this.machineField != null ? this.machineField.get(drone) : null;
            if (machine != null) {
                builder.machineState(machineState(machine))
                        .running(callBoolean(machine, "isRunning", running))
                        .paused(callBoolean(machine, "isPaused", false))
                        .components(callInt(machine, "componentCount", 0))
                        .maxComponents(callInt(machine, "maxComponents", 0))
                        .componentNames(componentNames(machine));
                double buffer = callDouble(drone, "globalBuffer", 0.0);
                double capacity = callDouble(drone, "globalBufferSize", 0.0);
                builder.energy(buffer, capacity);
            }

            Object control = this.controlField != null ? this.controlField.get(drone) : null;
            if (control != null) {
                builder.methods(OcComponentIntrospector.methods(control));
            } else if (this.machineField == null) {
                builder.methods(OcComponentIntrospector.methods(drone));
            }
            return builder.build();
        } catch (Throwable failure) {
            return OcState.unavailable(failure.getClass().getSimpleName()
                    + (failure.getMessage() != null ? ": " + failure.getMessage() : ""));
        }
    }

    /** The component object that exposes the OC callbacks for this drone. */
    public Object component(Drone drone) {
        if (drone == null) return null;
        try {
            Object control = this.controlField != null ? this.controlField.get(drone) : null;
            return control != null ? control : drone;
        } catch (Throwable ignored) {
            return drone;
        }
    }

    /** The live machine object, or {@code null} when the drone has none. */
    public Object machine(Drone drone) {
        if (drone == null || this.machineField == null) return null;
        try {
            return this.machineField.get(drone);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The OC {@code Context} for a drone; the entity itself implements it. */
    public Object context(Drone drone) {
        return drone;
    }

    /** Loads an OC component class by name for deeper introspection. */
    public Class<?> componentClass(String name) {
        return OcComponentIntrospector.ocClass(name);
    }

    private static String addressOf(Drone drone) {
        try {
            return drone.node() != null ? drone.node().address() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String machineState(Object machine) {
        try {
            Object stack = machine.getClass().getMethod("state").invoke(machine);
            Object top = stack.getClass().getMethod("top").invoke(stack);
            return String.valueOf(top);
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static List<String> componentNames(Object machine) {
        try {
            Object map = machine.getClass().getMethod("components").invoke(machine);
            if (map instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> typed = (Map<String, String>) map;
                return OcState.sortedKeys(typed);
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<>();
    }

    private static String safeString(Object target, String method) {
        Object value = call(target, method);
        return value != null ? String.valueOf(value) : "";
    }

    private static int safeInt(Object target, String method, int fallback) {
        return callInt(target, method, fallback);
    }

    private static boolean callBoolean(Object target, String method, boolean fallback) {
        Object value = call(target, method);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static int callInt(Object target, String method, int fallback) {
        Object value = call(target, method);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static double callDouble(Object target, String method, double fallback) {
        Object value = call(target, method);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static Object call(Object target, String method) {
        if (target == null) return null;
        try {
            Method resolved = target.getClass().getMethod(method);
            resolved.setAccessible(true);
            return resolved.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
