package ir.layercloud.cortexdrone.service.machine;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * The HBM integration facts live in one descriptor.
 *
 * <p>Regression risk: class names, the recipe directory and wiki endpoints used
 * to be string literals in three classes, so pointing at a different mod build
 * meant editing several files and they could drift apart.</p>
 */
public class HbmDescriptorTest {
    @Test
    public void theDescriptorNamesTheIntegration() {
        assertEquals("hbm", HbmDescriptor.MOD_ID);
        assertEquals("hbmRecipes", HbmDescriptor.RECIPE_DIRECTORY);
    }

    @Test
    public void recipeClassesAreTheKnownEntryPoints() {
        List<String> classes = HbmDescriptor.recipeClasses();
        assertTrue(classes.size() >= 2);
        for (String name : classes) {
            assertTrue(name + " must be an HBM class", HbmDescriptor.isOwnClass(name));
            assertTrue(name, name.endsWith("Recipes") || name.endsWith("SerializableRecipe"));
        }
    }

    @Test
    public void wrapperClassesAreHbmTypes() {
        for (String name : HbmDescriptor.wrapperClasses()) {
            assertTrue(name + " must be an HBM class", HbmDescriptor.isOwnClass(name));
        }
    }

    @Test
    public void docEndpointsAreHttpsApis() {
        List<String> endpoints = HbmDescriptor.docEndpoints();
        assertFalse(endpoints.isEmpty());
        for (String url : endpoints) {
            assertTrue(url + " must be https", url.startsWith("https://"));
            assertTrue(url + " must be an api endpoint", url.contains("/api"));
        }
    }

    @Test
    public void registryOwnershipIsCaseInsensitive() {
        assertTrue(HbmDescriptor.ownsRegistryName("hbm:item.plate_iron"));
        assertTrue(HbmDescriptor.ownsRegistryName("HBM:item.plate_iron"));
        assertFalse(HbmDescriptor.ownsRegistryName("minecraft:stone"));
        assertFalse(HbmDescriptor.ownsRegistryName(null));
    }

    @Test
    public void classOwnershipIsCaseInsensitive() {
        assertTrue(HbmDescriptor.isOwnClass("com.hbm.inventory.RecipesCommon"));
        assertFalse(HbmDescriptor.isOwnClass("com.example.Thing"));
        assertFalse(HbmDescriptor.isOwnClass(null));
    }

    @Test
    public void fallbackHandlersNameAClassAndAKind() {
        java.util.List<String[]> handlers = HbmDescriptor.fallbackHandlers();
        assertFalse(handlers.isEmpty());
        for (String[] handler : handlers) {
            assertEquals(2, handler.length);
            assertTrue("class must be HBM", HbmDescriptor.isOwnClass(handler[0]));
            assertFalse("kind must be non-empty", handler[1].trim().isEmpty());
        }
    }

    @Test
    public void theDescriptorListsAreImmutable() {
        try {
            HbmDescriptor.recipeClasses().add("com.hbm.Evil");
            fail("the recipe class list must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}
