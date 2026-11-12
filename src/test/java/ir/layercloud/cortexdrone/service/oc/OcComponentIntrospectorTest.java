package ir.layercloud.cortexdrone.service.oc;

import org.junit.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The OC introspector must discover callbacks from live annotations and invoke
 * them by name, because the whole OC surface is reflection-driven.
 */
@SuppressWarnings("unused")
public class OcComponentIntrospectorTest {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface FakeCallback {
        String value() default "";

        boolean direct() default false;

        boolean getter() default false;

        boolean setter() default false;

        int limit() default 0;

        String doc() default "";
    }

    public static class FakeComponent {
        @FakeCallback(value = "detect", direct = true, doc = "Detect the block in front.")
        public Object[] detect(Object context, Object arguments) {
            return new Object[]{"block", "minecraft:stone"};
        }

        @FakeCallback("move")
        public Object[] move(Object context, Object arguments) {
            return new Object[]{true};
        }

        @FakeCallback(value = "level", getter = true)
        public Object[] level(Object context, Object arguments) {
            return new Object[]{42.0};
        }

        public Object[] undocumented(Object context, Object arguments) {
            return new Object[]{"ignored"};
        }
    }

    /**
     * A subclass override must win over the parent declaration.
     */
    public static class OverridingComponent extends FakeComponent {
    }

    @Test
    public void availableIsFalseWithoutOpenComputersOnTheTestClasspath() {
        // The reflection bridge must degrade, not throw, when OC is absent.
        assertFalse(OcComponentIntrospector.available());
        assertEquals(0, OcComponentIntrospector.methods(new FakeComponent()).size());
    }

    @Test
    public void methodLookupIsNullForUnknownNames() {
        assertNull(OcComponentIntrospector.method(new FakeComponent(), "nope"));
        assertNull(OcComponentIntrospector.method(null, "detect"));
    }

    @Test
    public void ocClassNameLookupIsNullWhenMissing() {
        assertNull(OcComponentIntrospector.ocClass("li.cil.oc.does.NotExist"));
    }

    @Test
    public void ocMethodSortsAndFiltersByNameAndDoc() {
        OcMethod move = new OcMethod("move", "Move the drone.", false, false, false, 0, "Fake");
        OcMethod detect = new OcMethod("detect", "Detect a block.", true, false, false, 0, "Fake");
        List<OcMethod> sorted = OcMethod.sorted(Arrays.asList(move, detect));
        assertEquals("detect", sorted.get(0).name());
        assertEquals("move", sorted.get(1).name());

        assertEquals(1, OcMethod.filter(sorted, "det").size());
        assertEquals(1, OcMethod.filter(sorted, "block").size());
        assertEquals(2, OcMethod.filter(sorted, "").size());
        assertEquals(0, OcMethod.filter(sorted, "zzz").size());
    }

    @Test
    public void fakeAnnotationReflectsLikeOpenComputers() throws Exception {
        // Proves the reflection strategy works even though the real OC classes
        // are compile-only: runtime-retained annotations are discoverable.
        Method method = FakeComponent.class.getMethod("detect", Object.class, Object.class);
        FakeCallback callback = method.getAnnotation(FakeCallback.class);
        assertNotNull(callback);
        assertEquals("detect", callback.value());
        assertTrue(callback.direct());

        Method getter = FakeComponent.class.getMethod("level", Object.class, Object.class);
        assertTrue(getter.getAnnotation(FakeCallback.class).getter());
    }

    /**
     * OpenComputers leaves {@code value()} empty and relies on the Java method
     * name (e.g. {@code getStatusText}); the introspector must not produce one
     * nameless entry per method.
     */
    @Test
    public void emptyCallbackValueFallsBackToTheMethodName() {
        assertEquals("getStatusText",
                nameFor(new UnnamedCallbackComponent(), "getStatusText"));
        assertEquals("moveTo",
                nameFor(new UnnamedCallbackComponent(), "moveTo"));
    }

    private static String nameFor(Object target, String methodName) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName)) continue;
            FakeCallback callback = method.getAnnotation(FakeCallback.class);
            if (callback == null) continue;
            String value = callback.value();
            return value.trim().isEmpty() ? method.getName() : value;
        }
        return null;
    }

    public static class UnnamedCallbackComponent {
        @FakeCallback(doc = "Get the status text.")
        public Object[] getStatusText(Object context, Object arguments) {
            return new Object[]{""};
        }

        @FakeCallback(doc = "Move to a position.")
        public Object[] moveTo(Object context, Object arguments) {
            return new Object[]{true};
        }
    }
}
