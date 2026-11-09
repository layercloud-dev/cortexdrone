package ir.layercloud.cortexdrone.harness.js;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJavaClass;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.WrapFactory;
import org.mozilla.javascript.Wrapper;

import java.lang.reflect.Array;

/**
 * Java interop surface for the in-process Rhino runtime.
 *
 * <p>When reflection is enabled the engine builds its scope with Rhino's
 * {@code ImporterTopLevel}, so {@code Packages}, {@code java.*}, {@code javax.*},
 * {@code importClass} and {@code importPackage} all work. This class adds the
 * pieces Rhino leaves out:</p>
 *
 * <ul>
 *   <li>{@code Java.type("fully.qualified.Class")} plus the {@code .class} and
 *       {@code []} suffixes, so {@code Java.type("net.minecraft.world.World")}
 *       and {@code Java.type("java.lang.String.class")} both resolve;</li>
 *   <li>a {@code .class} property on class objects and on live instances,
 *       which ordinary Rhino does not expose;</li>
 *   <li>{@code Java.from}, {@code Java.to}, {@code Java.isJavaObject},
 *       {@code Java.isType} and {@code Java.getClassName};</li>
 *   <li>a wrap factory so every Java object crossing into JS gets the above.</li>
 * </ul>
 *
 * <p>Minecraft state is not thread safe. Scripts run on the agent thread, so
 * live objects may be inspected there but must be mutated through the
 * {@code main(fn)} bridge the engine binds next to this one.</p>
 */
final class JavaInterop {

    private JavaInterop() {
    }

    /** Removes the package globals Rhino installs even without the importer. */
    static void removePackageGlobals(Scriptable scope) {
        if (!(scope instanceof ScriptableObject)) return;
        ScriptableObject object = (ScriptableObject) scope;
        for (String name : new String[]{"Packages", "java", "javax", "org", "com", "edu", "net"}) {
            if (object.has(name, object)) {
                object.delete(name);
            }
        }
    }

    /** Loader that can see the mod, Minecraft and the JDK. */
    static ClassLoader classLoader() {
        ClassLoader loader = JavaInterop.class.getClassLoader();
        if (loader != null) return loader;
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : ClassLoader.getSystemClassLoader();
    }

    /**
     * Shutter used when reflection is disabled: package access, reflection,
     * sockets, files and process spawning stay invisible. The engine also skips
     * the importer globals in that mode, so this is defence in depth.
     */
    static ClassShutter restrictedShutter() {
        return new ClassShutter() {
            @Override
            public boolean visibleToScripts(String fullClassName) {
                if (fullClassName == null) return false;
                return !(fullClassName.startsWith("java.io.")
                        || fullClassName.startsWith("java.nio.file.")
                        || fullClassName.startsWith("java.net.")
                        || fullClassName.startsWith("java.lang.reflect.")
                        || fullClassName.equals("java.lang.Runtime")
                        || fullClassName.equals("java.lang.ProcessBuilder")
                        || fullClassName.startsWith("sun.")
                        || fullClassName.startsWith("jdk."));
            }
        };
    }

    /** Wraps Java classes and instances so scripts can ask for {@code .class}. */
    static WrapFactory wrapFactory() {
        return new InteropWrapFactory();
    }

    static void bind(final Context cx, Scriptable scope, final ClassLoader loader) {
        ScriptableObject java = (ScriptableObject) cx.newObject(scope);

        BaseFunction type = new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                String name = args.length > 0 ? Context.toString(args[0]) : "";
                boolean wantsClassLiteral = name.trim().endsWith(JavaTypes.CLASS_SUFFIX);
                Class<?> resolved;
                try {
                    resolved = resolve(name, loader);
                } catch (Throwable t) {
                    throw Context.reportRuntimeError("Java.type('" + name + "') failed: " + t.getMessage());
                }
                // Java.type("a.b.C") is the class object (constructor + statics).
                // Java.type("a.b.C.class") is the java.lang.Class instance.
                if (wantsClassLiteral) {
                    return inner.getWrapFactory().wrapAsJavaObject(inner, innerScope, resolved, Class.class);
                }
                return inner.getWrapFactory().wrapJavaClass(inner, innerScope, resolved);
            }
        };
        ScriptableObject.putProperty(java, "type", type);
        ScriptableObject.putProperty(java, "classOf", type);
        ScriptableObject.putProperty(java, "reflect", type);

        ScriptableObject.putProperty(java, "isJavaObject", new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                return args.length > 0 && (args[0] instanceof NativeJavaObject || args[0] instanceof Wrapper);
            }
        });
        ScriptableObject.putProperty(java, "isType", new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                return args.length > 0 && unwrapClass(args[0]) != null;
            }
        });
        ScriptableObject.putProperty(java, "getClassName", new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                Object raw = args.length > 0 ? unwrap(args[0]) : null;
                return raw == null ? Undefined.instance : raw.getClass().getName();
            }
        });
        ScriptableObject.putProperty(java, "from", new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                return fromJava(inner, innerScope, args.length > 0 ? args[0] : null);
            }
        });
        ScriptableObject.putProperty(java, "to", new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                Object values = args.length > 0 ? args[0] : null;
                Object typeArg = args.length > 1 ? args[1] : null;
                return toJava(inner, innerScope, values, typeArg, loader);
            }
        });
        ScriptableObject.putProperty(scope, "Java", java);

        bindImports(cx, scope, loader);
    }

    /**
     * Resolves {@code "java.util"} to the live package object via the global
     * {@code Packages} tree, avoiding Rhino's deprecated constructors.
     */
    private static Object packageFor(Scriptable scope, String name) {
        Object current = null;
        for (String part : name.split("\\.")) {
            if (part.isEmpty()) continue;
            if (current == null) {
                current = ScriptableObject.getProperty(scope, part);
                if (!(current instanceof Scriptable)) return Undefined.instance;
                continue;
            }
            if (!(current instanceof Scriptable)) return Undefined.instance;
            current = ScriptableObject.getProperty((Scriptable) current, part);
        }
        return current != null ? current : Undefined.instance;
    }

    /**
     * Rhino's importer only accepts class/package objects, but models write
     * {@code importClass('java.util.ArrayList')}. Keep the built-in behaviour
     * and add the string form on top.
     */
    private static void bindImports(final Context cx, final Scriptable scope, final ClassLoader loader) {
        final Object originalClass = ScriptableObject.getProperty(scope, "importClass");
        final Object originalPackage = ScriptableObject.getProperty(scope, "importPackage");

        ScriptableObject.putProperty(scope, "importClass", new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                Object result = Undefined.instance;
                for (Object arg : args) {
                    Object target = arg;
                    if (arg instanceof CharSequence) {
                        Class<?> type = resolve(arg.toString(), loader);
                        target = inner.getWrapFactory().wrapJavaClass(inner, innerScope, type);
                    }
                    if (originalClass instanceof Function) {
                        result = ((Function) originalClass).call(inner, innerScope, innerScope,
                                new Object[]{target});
                    }
                }
                return result;
            }
        });

        ScriptableObject.putProperty(scope, "importPackage", new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                Object arg = args.length > 0 ? args[0] : null;
                if (arg instanceof CharSequence) {
                    Object pkg = packageFor(innerScope, arg.toString());
                    return originalPackage instanceof Function
                            ? ((Function) originalPackage).call(inner, innerScope, innerScope,
                                    new Object[]{pkg})
                            : Undefined.instance;
                }
                return originalPackage instanceof Function
                        ? ((Function) originalPackage).call(inner, innerScope, thisObj, args)
                        : Undefined.instance;
            }
        });
    }

    /**
     * Resolves a class name, accepting a trailing {@code .class} and array
     * suffixes such as {@code java.lang.String[]}.
     */
    /** @see JavaTypes#resolve(String, ClassLoader) */
    static Class<?> resolve(String raw, ClassLoader loader) {
        return JavaTypes.resolve(raw, loader);
    }

    private static Object unwrap(Object value) {
        if (value instanceof Wrapper) {
            try {
                return ((Wrapper) value).unwrap();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return value;
    }

    private static Class<?> unwrapClass(Object value) {
        if (value instanceof NativeJavaClass) return ((NativeJavaClass) value).getClassObject();
        Object raw = unwrap(value);
        return raw instanceof Class ? (Class<?>) raw : null;
    }

    /** Java array -> JS array, without leaking NativeJavaArray wrappers. */
    private static Object fromJava(Context cx, Scriptable scope, Object value) {
        Object raw = unwrap(value);
        if (raw == null || !raw.getClass().isArray()) return value;
        int length = Array.getLength(raw);
        Object[] items = new Object[length];
        for (int i = 0; i < length; i++) {
            items[i] = Context.javaToJS(Array.get(raw, i), scope);
        }
        return cx.newArray(scope, items);
    }

    /** JS array -> Java array of the requested component type. */
    private static Object toJava(Context cx, Scriptable scope, Object value,
                                 Object typeArg, ClassLoader loader) {
        if (!(value instanceof NativeArray)) {
            throw Context.reportRuntimeError("Java.to expects a JS array as its first argument.");
        }
        NativeArray array = (NativeArray) value;
        int length = (int) array.getLength();
        Class<?> target;
        if (typeArg == null || typeArg instanceof Undefined) {
            target = Object.class;
        } else {
            Class<?> declared = unwrapClass(typeArg);
            if (declared != null) {
                target = declared;
            } else {
                target = resolve(Context.toString(typeArg), loader);
            }
        }
        Class<?> component = target.isArray() ? target.getComponentType() : target;
        Object out = Array.newInstance(component, length);
        for (int i = 0; i < length; i++) {
            Object item = array.get(i, array);
            Array.set(out, i, Context.jsToJava(item, component));
        }
        return Context.javaToJS(out, scope);
    }

    private static final class InteropWrapFactory extends WrapFactory {
        @Override
        public Scriptable wrapAsJavaObject(Context cx, Scriptable scope, Object javaObject, Class<?> staticType) {
            return new ClassExposingJavaObject(scope, javaObject, staticType);
        }

        @Override
        public Scriptable wrapJavaClass(Context cx, Scriptable scope, Class<?> javaClass) {
            return new ClassExposingJavaClass(scope, javaClass);
        }
    }

    /** A live Java object that also answers {@code .class}. */
    private static class ClassExposingJavaObject extends NativeJavaObject {
        ClassExposingJavaObject(Scriptable scope, Object javaObject, Class<?> staticType) {
            super(scope, javaObject, staticType);
        }

        @Override
        public Object get(String name, Scriptable start) {
            if ("class".equals(name)) {
                Class<?> type = this.javaObject != null ? this.javaObject.getClass() : null;
                Scriptable parent = getParentScope();
                if (type != null && parent != null) {
                    return new ClassExposingJavaObject(parent, type, Class.class);
                }
            }
            return super.get(name, start);
        }

        @Override
        public boolean has(String name, Scriptable start) {
            return "class".equals(name) || super.has(name, start);
        }
    }

    /** A class object that also answers {@code .class} and {@code Class} methods. */
    private static final class ClassExposingJavaClass extends NativeJavaClass {
        ClassExposingJavaClass(Scriptable scope, Class<?> javaClass) {
            super(scope, javaClass);
        }

        /** The wrapped class viewed as a {@code java.lang.Class} instance. */
        private Scriptable asClassInstance() {
            Scriptable parent = getParentScope();
            return parent == null ? null
                    : new ClassExposingJavaObject(parent, getClassObject(), Class.class);
        }

        @Override
        public Object get(String name, Scriptable start) {
            if ("class".equals(name)) return asClassInstance();
            Scriptable asClass = asClassInstance();
            // Class objects only answer statics; fall through to java.lang.Class
            // so getName()/getSimpleName()/getMethods() work on a type handle.
            if (asClass != null && !super.has(name, start) && asClass.has(name, asClass)) {
                return asClass.get(name, asClass);
            }
            return super.get(name, start);
        }

        @Override
        public boolean has(String name, Scriptable start) {
            return "class".equals(name) || super.has(name, start);
        }
    }
}
