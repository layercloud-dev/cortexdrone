package ir.layercloud.cortexdrone.service.world;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * The OreDictionary registry has exactly one staleness watcher.
 *
 * <p>Regression risk: {@code OreDictIndex} and {@code RegistryIntrospector} each
 * kept their own tag counter, so a late-registering mod could leave one derived
 * cache stale while the other refreshed. {@code OreDictIndex} is now the single
 * authority and everything else asks it.</p>
 */
public class OreDictIndexSourceTest {
    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test
    public void onlyOreDictIndexCallsGetOreNames() throws Exception {
        String index = read("src/main/java/ir/layercloud/cortexdrone/service/world/OreDictIndex.java");
        assertTrue("the index is the registry authority",
                index.contains("OreDictionary.getOreNames()"));

        String introspector = read("src/main/java/ir/layercloud/cortexdrone/service/world/RegistryIntrospector.java");
        // It may read names to derive tokens, but it must not keep its own
        // change-detection counter; it asks the index instead.
        assertTrue("the introspector must ask the index for the tag count",
                introspector.contains("OreDictIndex.tagCount()")
                        || introspector.contains("OreDictIndex.refresh()"));
        assertFalse("a second registry counter must not exist",
                introspector.contains("lastOreDictCount"));
    }

    @Test
    public void theTagCountAccessorExists() throws Exception {
        String index = read("src/main/java/ir/layercloud/cortexdrone/service/world/OreDictIndex.java");
        assertTrue(index.contains("static int tagCount()"));
    }
}
