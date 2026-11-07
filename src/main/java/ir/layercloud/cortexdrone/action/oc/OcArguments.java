package ir.layercloud.cortexdrone.action.oc;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an OpenComputers {@code Arguments} object without referencing OC types.
 *
 * <p>{@code Arguments} is an interface, so a dynamic proxy is enough. OC callbacks
 * read positional values through {@code checkX}/{@code optX}; this adapter maps
 * JSON numbers, strings, booleans, arrays and objects onto those slots and
 * converts on demand.</p>
 */
public final class OcArguments {
    private static final String ARGUMENTS = "li.cil.oc.api.machine.Arguments";

    private final List<Object> values;

    private OcArguments(List<Object> values) {
        this.values = values != null ? values : new ArrayList<Object>();
    }

    public static OcArguments of(List<Object> values) {
        return new OcArguments(normalize(values));
    }

    /** The proxy implementing OC's {@code Arguments}, or {@code null} when OC is absent. */
    public Object proxy() {
        Class<?> type = type();
        if (type == null) return null;
        return java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> dispatch(proxy, method, args));
    }

    private static Class<?> type() {
        try {
            return Class.forName(ARGUMENTS);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object dispatch(Object proxy, java.lang.reflect.Method method, Object[] args) {
        String name = method.getName();
        if ("equals".equals(name)) return proxy == (args != null && args.length > 0 ? args[0] : null);
        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
        if ("toString".equals(name)) return "CortexDroneOcArguments" + this.values;
        if ("count".equals(name)) return this.values.size();
        if ("toArray".equals(name)) return this.values.toArray();
        if ("iterator".equals(name)) return this.values.iterator();

        int index = args != null && args.length > 0 && args[0] instanceof Number
                ? ((Number) args[0]).intValue() : 0;
        if (name.startsWith("opt")) {
            Object fallback = args != null && args.length > 1 ? args[1] : null;
            return convert(name.substring(3), valueAt(index), fallback);
        }
        if (name.startsWith("is")) {
            Object value = valueAt(index);
            String kind = name.substring(2);
            return matches(kind, value);
        }
        if (name.startsWith("check")) {
            return convert(name.substring(5), valueAt(index), null);
        }
        return null;
    }

    private Object valueAt(int index) {
        return index >= 0 && index < this.values.size() ? this.values.get(index) : null;
    }

    private static Object convert(String kind, Object value, Object fallback) {
        if (value == null) return fallback;
        if ("Any".equals(kind)) return value;
        if ("Boolean".equals(kind)) {
            if (value instanceof Boolean) return value;
            if (value instanceof Number) return ((Number) value).doubleValue() != 0.0;
            return Boolean.parseBoolean(String.valueOf(value));
        }
        if ("Integer".equals(kind)) return value instanceof Number
                ? ((Number) value).intValue() : parseInt(value, fallback);
        if ("Long".equals(kind)) return value instanceof Number
                ? ((Number) value).longValue() : (long) parseInt(value, fallback);
        if ("Double".equals(kind)) return value instanceof Number
                ? ((Number) value).doubleValue() : parseDouble(value, fallback);
        if ("String".equals(kind)) return String.valueOf(value);
        if ("ByteArray".equals(kind)) return value instanceof byte[]
                ? value : String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if ("Table".equals(kind)) return value instanceof Map ? value : new LinkedHashMap<String, Object>();
        if ("ItemStack".equals(kind)) return value;
        return value;
    }

    private static Object parseInt(Object value, Object fallback) {
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Object parseDouble(Object value, Object fallback) {
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean matches(String kind, Object value) {
        if (value == null) return false;
        if ("Boolean".equals(kind)) return value instanceof Boolean;
        if ("Integer".equals(kind)) return value instanceof Integer;
        if ("Long".equals(kind)) return value instanceof Long;
        if ("Double".equals(kind)) return value instanceof Double || value instanceof Float;
        if ("String".equals(kind)) return value instanceof CharSequence;
        if ("ByteArray".equals(kind)) return value instanceof byte[];
        if ("Table".equals(kind)) return value instanceof Map;
        if ("ItemStack".equals(kind)) return !(value instanceof Number || value instanceof CharSequence
                || value instanceof Boolean || value instanceof Map || value instanceof List);
        return false;
    }

    /**
     * Converts JSON-ish containers to the flat shapes OC expects. OC only knows
     * primitives, arrays, maps and item stacks, so nested lists become maps with
     * 1-based keys, the same convention its Lua runtime uses.
     */
    private static List<Object> normalize(List<Object> input) {
        List<Object> out = new ArrayList<>();
        if (input == null) return out;
        for (Object value : input) out.add(flatten(value));
        return out;
    }

    private static Object flatten(Object value) {
        if (value == null) return null;
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), flatten(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            Map<String, Object> out = new LinkedHashMap<>();
            for (int i = 0; i < list.size(); i++) {
                out.put(String.valueOf(i + 1), flatten(list.get(i)));
            }
            return out;
        }
        if (value.getClass().isArray() && !(value instanceof byte[])) {
            int length = Array.getLength(value);
            Map<String, Object> out = new LinkedHashMap<>();
            for (int i = 0; i < length; i++) {
                out.put(String.valueOf(i + 1), flatten(Array.get(value, i)));
            }
            return out;
        }
        return value;
    }
}
