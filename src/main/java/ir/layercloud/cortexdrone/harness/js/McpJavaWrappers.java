package ir.layercloud.cortexdrone.harness.js;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJavaClass;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;

import java.lang.reflect.Array;

/**
 * Rhino wrappers that keep Java class/instance handles usable on a reobfuscated
 * production server.
 *
 * <p>Two behaviours are added on top of Rhino's wrappers:</p>
 * <ul>
 *   <li>{@code .class} on classes and instances, plus the {@code Class}
 *       methods, so {@code Java.type("x.y.Z").class.getDeclaredField(...)}
 *       works;</li>
 *   <li>MCP-to-SRG aliasing, including a retry inside
 *       {@code Class.getMethod/getField}, so the model's fallback
 *       {@code Class.forName(...).getMethod("getInstance")} does not fail on
 *       the obfuscated server.</li>
 * </ul>
 */
final class McpJavaWrappers {
    private McpJavaWrappers() {
    }

    /**
     * A live Java object that also answers {@code .class} and aliased members.
     */
    static final class ObjectWrapper extends NativeJavaObject {
        ObjectWrapper(Scriptable scope, Object javaObject, Class<?> staticType) {
            super(scope, javaObject, staticType);
        }

        @Override
        public Object get(String name, Scriptable start) {
            if ("class".equals(name)) {
                Class<?> type = this.javaObject != null ? this.javaObject.getClass() : null;
                Scriptable parent = getParentScope();
                if (type != null && parent != null) {
                    return new ObjectWrapper(parent, type, Class.class);
                }
            }
            if (this.javaObject instanceof Class && isReflectionLookup(name)) {
                return new ReflectionAlias(name, (Class<?>) this.javaObject);
            }
            Object direct = super.get(name, start);
            if (direct != Scriptable.NOT_FOUND) return direct;
            Class<?> owner = this.javaObject != null ? this.javaObject.getClass() : null;
            String method = McpAliases.method(owner, name);
            if (method != null) {
                Object aliased = super.get(method, start);
                if (aliased != Scriptable.NOT_FOUND) return aliased;
            }
            String field = McpAliases.field(owner, name);
            if (field != null) {
                Object aliased = super.get(field, start);
                if (aliased != Scriptable.NOT_FOUND) return aliased;
            }
            return direct;
        }

        @Override
        public boolean has(String name, Scriptable start) {
            if ("class".equals(name) || super.has(name, start)) return true;
            Class<?> owner = this.javaObject != null ? this.javaObject.getClass() : null;
            String method = McpAliases.method(owner, name);
            if (method != null && super.has(method, start)) return true;
            String field = McpAliases.field(owner, name);
            return field != null && super.has(field, start);
        }
    }

    /**
     * A class handle that answers {@code .class}, {@code Class} methods, and
     * MCP static names.
     */
    static final class ClassWrapper extends NativeJavaClass {
        ClassWrapper(Scriptable scope, Class<?> javaClass) {
            super(scope, javaClass);
        }

        private Scriptable asClassInstance() {
            Scriptable parent = getParentScope();
            return parent == null ? null
                    : new ObjectWrapper(parent, getClassObject(), Class.class);
        }

        @Override
        public Object get(String name, Scriptable start) {
            if ("class".equals(name)) return asClassInstance();
            Scriptable asClass = asClassInstance();
            if (asClass != null && !super.has(name, start) && asClass.has(name, asClass)) {
                return asClass.get(name, asClass);
            }
            Object direct = super.get(name, start);
            if (direct != Scriptable.NOT_FOUND) return direct;
            String method = McpAliases.method(getClassObject(), name);
            if (method != null) {
                Object aliased = super.get(method, start);
                if (aliased != Scriptable.NOT_FOUND) return aliased;
            }
            String field = McpAliases.field(getClassObject(), name);
            if (field != null) {
                Object aliased = super.get(field, start);
                if (aliased != Scriptable.NOT_FOUND) return aliased;
            }
            return direct;
        }

        @Override
        public boolean has(String name, Scriptable start) {
            if ("class".equals(name) || super.has(name, start)) return true;
            String method = McpAliases.method(getClassObject(), name);
            if (method != null && super.has(method, start)) return true;
            String field = McpAliases.field(getClassObject(), name);
            return field != null && super.has(field, start);
        }
    }

    private static boolean isReflectionLookup(String name) {
        return "getMethod".equals(name) || "getDeclaredMethod".equals(name)
                || "getField".equals(name) || "getDeclaredField".equals(name);
    }

    /**
     * Alias-aware {@code Class.getMethod/getDeclaredMethod/getField/getDeclaredField}.
     * Retries with the SRG name only when the first lookup throws.
     */
    static final class ReflectionAlias extends BaseFunction {
        private final String operation;
        private final Class<?> target;

        ReflectionAlias(String operation, Class<?> target) {
            this.operation = operation;
            this.target = target;
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            String member = args.length > 0 ? Context.toString(args[0]) : "";
            try {
                return invoke(member, args);
            } catch (Throwable first) {
                boolean fieldLookup = this.operation.contains("Field");
                String alias = fieldLookup
                        ? McpAliases.field(this.target, member)
                        : McpAliases.method(this.target, member);
                if (alias == null) throw Context.throwAsScriptRuntimeEx(first);
                try {
                    return invoke(alias, args);
                } catch (Throwable second) {
                    throw Context.throwAsScriptRuntimeEx(first);
                }
            }
        }

        private Object invoke(String member, Object[] args) throws Exception {
            if (this.operation.contains("Field")) {
                return this.operation.startsWith("getDeclared")
                        ? this.target.getDeclaredField(member) : this.target.getField(member);
            }
            Class<?>[] params = parameterTypes(args.length > 1 ? args[1] : null);
            return this.operation.startsWith("getDeclared")
                    ? this.target.getDeclaredMethod(member, params)
                    : this.target.getMethod(member, params);
        }
    }

    static Class<?>[] parameterTypes(Object value) {
        if (value == null || value instanceof Undefined) return new Class<?>[0];
        if (value instanceof NativeArray) {
            NativeArray array = (NativeArray) value;
            int length = (int) array.getLength();
            Class<?>[] types = new Class<?>[length];
            for (int i = 0; i < length; i++) types[i] = typeOf(array.get(i, array));
            return types;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            Class<?>[] types = new Class<?>[length];
            for (int i = 0; i < length; i++) types[i] = typeOf(Array.get(value, i));
            return types;
        }
        return new Class<?>[]{typeOf(value)};
    }

    private static Class<?> typeOf(Object value) {
        Class<?> type = JavaInterop.unwrapClassValue(value);
        if (type != null) return type;
        Object raw = JavaInterop.unwrapValue(value);
        if (raw instanceof Class) return (Class<?>) raw;
        return Object.class;
    }
}
