package ir.layercloud.cortexdrone.harness.js;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Java type-expression resolution for the script runtime.
 *
 * <p>Extracted from {@code JavaInterop}. These are the forms a model writes when
 * it reaches for a Java class, including the ones that are easy to get wrong:
 * primitives (not loadable by name) and array types.</p>
 */
public class JavaTypesTest {
    private static final ClassLoader LOADER = JavaTypesTest.class.getClassLoader();

    @Test
    public void aPlainClassNameResolves() {
        assertEquals(String.class, JavaTypes.resolve("java.lang.String", LOADER));
    }

    @Test
    public void theClassSuffixIsAccepted() {
        assertEquals(String.class, JavaTypes.resolve("java.lang.String.class", LOADER));
        assertEquals(String.class, JavaTypes.resolve("  java.lang.String.class  ", LOADER));
    }

    @Test
    public void primitivesResolveToRealPrimitiveTypes() {
        assertEquals(int.class, JavaTypes.resolve("int", LOADER));
        assertEquals(boolean.class, JavaTypes.resolve("boolean", LOADER));
        assertEquals(void.class, JavaTypes.resolve("void", LOADER));
        assertTrue(JavaTypes.isPrimitive("long"));
        assertFalse(JavaTypes.isPrimitive("java.lang.Long"));
    }

    @Test
    public void arraySuffixesBuildArrayClasses() {
        assertEquals(int[].class, JavaTypes.resolve("int[]", LOADER));
        assertEquals(String[].class, JavaTypes.resolve("java.lang.String[]", LOADER));
        assertEquals(String[][].class, JavaTypes.resolve("java.lang.String[][]", LOADER));
    }

    @Test
    public void classSuffixAndArraySuffixCombine() {
        assertEquals(String[].class, JavaTypes.resolve("java.lang.String[].class", LOADER));
    }

    @Test
    public void anEmptyNameIsRejected() {
        try {
            JavaTypes.resolve("   ", LOADER);
            fail("an empty type name must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("empty"));
        }
    }

    @Test
    public void anUnknownClassIsRejectedWithItsName() {
        try {
            JavaTypes.resolve("no.such.Class", LOADER);
            fail("an unknown class must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("no.such.Class"));
        }
    }

    @Test
    public void nullResolvesLikeEmpty() {
        try {
            JavaTypes.resolve(null, LOADER);
            fail("a null type name must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
