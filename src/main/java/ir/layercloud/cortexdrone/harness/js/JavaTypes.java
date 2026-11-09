package ir.layercloud.cortexdrone.harness.js;

import java.lang.reflect.Array;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves JavaScript type expressions to Java {@link Class} objects.
 *
 * <p>Extracted from {@code JavaInterop}. Accepts the three forms a script writes:
 * a plain name ({@code java.lang.String}), a {@code .class} suffix
 * ({@code java.lang.String.class}) and array suffixes ({@code int[]},
 * {@code String[][]}). Primitive names map to real primitives, since
 * {@code Class.forName("int")} does not exist.</p>
 */
final class JavaTypes {
    /** Suffix a script appends to ask for a class literal. */
    static final String CLASS_SUFFIX = ".class";
    private static final String ARRAY_SUFFIX = "[]";

    private static final Map<String, Class<?>> PRIMITIVES = new HashMap<>();

    static {
        PRIMITIVES.put("boolean", boolean.class);
        PRIMITIVES.put("byte", byte.class);
        PRIMITIVES.put("char", char.class);
        PRIMITIVES.put("short", short.class);
        PRIMITIVES.put("int", int.class);
        PRIMITIVES.put("long", long.class);
        PRIMITIVES.put("float", float.class);
        PRIMITIVES.put("double", double.class);
        PRIMITIVES.put("void", void.class);
    }

    private JavaTypes() {
    }

    /**
     * Resolves a type expression.
     *
     * @throws IllegalArgumentException when the name is empty or unknown
     */
    static Class<?> resolve(String raw, ClassLoader loader) {
        String name = raw == null ? "" : raw.trim();
        if (name.endsWith(CLASS_SUFFIX)) {
            name = name.substring(0, name.length() - CLASS_SUFFIX.length()).trim();
        }
        int dimensions = 0;
        while (name.endsWith(ARRAY_SUFFIX)) {
            dimensions++;
            name = name.substring(0, name.length() - ARRAY_SUFFIX.length()).trim();
        }
        if (name.isEmpty()) throw new IllegalArgumentException("empty class name");
        Class<?> type = load(name, loader);
        return dimensions == 0 ? type
                : Array.newInstance(type, new int[dimensions]).getClass();
    }

    /** Loads a class through the given, context and system class loaders. */
    private static Class<?> load(String name, ClassLoader loader) {
        Class<?> primitive = PRIMITIVES.get(name);
        if (primitive != null) return primitive;
        ClassNotFoundException last = null;
        for (ClassLoader candidate : candidates(loader)) {
            try {
                return Class.forName(name, false, candidate);
            } catch (ClassNotFoundException missing) {
                last = missing;
            }
        }
        throw new IllegalArgumentException("unknown class '" + name + "'", last);
    }

    /** The loader, then the thread's context loader, then the system loader. */
    private static ClassLoader[] candidates(ClassLoader loader) {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context == null || context == loader) return new ClassLoader[]{loader};
        return new ClassLoader[]{loader, context};
    }

    /** True when {@code name} is a JVM primitive (or {@code void}). */
    static boolean isPrimitive(String name) {
        return name != null && PRIMITIVES.containsKey(name);
    }
}
