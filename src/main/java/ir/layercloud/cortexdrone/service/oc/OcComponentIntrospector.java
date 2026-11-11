package ir.layercloud.cortexdrone.service.oc;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection bridge to OpenComputers' component callback API.
 *
 * <p>OpenComputers marks every component method with {@code @Callback}. The
 * annotation is runtime-retained, so the live API can be enumerated instead of
 * copied into a table that rots. Everything here is reflective because
 * OpenComputers is an optional mod: the server classes are absent from unit
 * tests and from servers that never installed it.</p>
 *
 * <p>This class only answers questions and invokes one method. Choosing when to
 * call, retrying and policy belong to the manager/action layers.</p>
 */
public final class OcComponentIntrospector {
    private static final String CALLBACK = "li.cil.oc.api.machine.Callback";
    private static final Map<Class<?>, List<OcMethod>> CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, Method>> INVOKERS = new ConcurrentHashMap<>();
    private static volatile boolean annotationPresent;
    private static volatile boolean annotationChecked;

    private OcComponentIntrospector() {
    }

    /**
     * True when OpenComputers is loaded and exposes the callback annotation.
     */
    public static boolean available() {
        return callbackType() != null;
    }

    private static Class<? extends Annotation> callbackType() {
        if (!annotationChecked) {
            synchronized (OcComponentIntrospector.class) {
                if (!annotationChecked) {
                    annotationPresent = loadCallback() != null;
                    annotationChecked = true;
                }
            }
        }
        return annotationPresent ? loadCallback() : null;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> loadCallback() {
        try {
            return (Class<? extends Annotation>) Class.forName(CALLBACK);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Every callback exposed by an object's class hierarchy.
     *
     * <p>Duplicates are resolved in favour of the most derived declaration so an
     * addon override wins over the base component.</p>
     */
    public static List<OcMethod> methods(Object target) {
        if (target == null) return Collections.emptyList();
        Class<?> type = target.getClass();
        List<OcMethod> cached = CACHE.get(type);
        if (cached != null) return cached;
        List<OcMethod> discovered = discover(type);
        CACHE.put(type, discovered);
        return discovered;
    }

    private static List<OcMethod> discover(Class<?> type) {
        Class<? extends Annotation> annotation = callbackType();
        if (annotation == null) return Collections.emptyList();
        Map<String, OcMethod> byName = new LinkedHashMap<>();
        Map<String, Method> methods = new LinkedHashMap<>();
        for (Class<?> current : hierarchy(type)) {
            Method[] declared = current.getMethods();
            Arrays.sort(declared, Comparator.comparing(Method::getName));
            for (Method method : declared) {
                Annotation callback = method.getAnnotation(annotation);
                if (callback == null) continue;
                // OC's @Callback value defaults to "" and the callback name is
                // then the Java method name, e.g. getStatusText.
                String name = string(callback, "value", method.getName());
                if (name.trim().isEmpty()) name = method.getName();
                if (byName.containsKey(name)) continue;
                byName.put(name, new OcMethod(
                        name,
                        string(callback, "doc", ""),
                        bool(callback, "direct", false),
                        bool(callback, "getter", false),
                        bool(callback, "setter", false),
                        number(callback, "limit", 0),
                        method.getDeclaringClass().getName()));
                methods.put(name, method);
            }
        }
        if (!methods.isEmpty()) {
            Map<String, Method> existing = INVOKERS.get(type);
            if (existing == null) INVOKERS.put(type, methods);
        }
        List<OcMethod> result = new ArrayList<>(byName.values());
        Collections.sort(result, OcMethod.BY_NAME);
        return Collections.unmodifiableList(result);
    }

    /**
     * Class then superclasses then interfaces, most specific first.
     */
    private static List<Class<?>> hierarchy(Class<?> type) {
        List<Class<?>> chain = new ArrayList<>();
        if (type == null) return chain;
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            chain.add(current);
        }
        Class<?>[] interfaces = type.getInterfaces();
        chain.addAll(Arrays.asList(interfaces));
        return chain;
    }

    /**
     * Lookup one callback by its OC name.
     */
    public static OcMethod method(Object target, String name) {
        if (target == null || name == null) return null;
        for (OcMethod method : methods(target)) {
            if (method.name().equals(name)) return method;
        }
        return null;
    }

    private static Method invoker(Object target, String name) {
        if (target == null || name == null) return null;
        methods(target);
        Map<String, Method> methods = INVOKERS.get(target.getClass());
        return methods != null ? methods.get(name) : null;
    }

    /**
     * Invokes one callback.
     *
     * <p>OpenComputers passes {@code (Context, Arguments)} to component methods.
     * The drone entity itself implements {@code Context}; for other components
     * the caller supplies one, or {@code null} when the method ignores it.</p>
     *
     * @return the raw Java return value ({@code Object[]} for most callbacks)
     */
    public static Object invoke(Object target, String name, Object context, Object arguments)
            throws Exception {
        Method method = invoker(target, name);
        if (method == null) {
            throw new NoSuchMethodException("OpenComputers callback '" + name
                    + "' is not exposed by " + target.getClass().getName());
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        Object[] call = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            call[i] = i == 0 ? context : arguments;
        }
        try {
            method.setAccessible(true);
        } catch (Throwable ignored) {
        }
        return method.invoke(target, call);
    }

    /**
     * Loads an OpenComputers class by name, or {@code null} when absent.
     */
    public static Class<?> ocClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String string(Annotation annotation, String member, String fallback) {
        Object value = member(annotation, member);
        return value instanceof String ? (String) value : fallback;
    }

    private static boolean bool(Annotation annotation, String member, boolean fallback) {
        Object value = member(annotation, member);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static int number(Annotation annotation, String member, int fallback) {
        Object value = member(annotation, member);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static Object member(Annotation annotation, String member) {
        try {
            return annotation.annotationType().getMethod(member).invoke(annotation);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
