package ir.layercloud.cortexdrone.harness;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertTrue;

public class SkillLibraryTest {
    @Test
    public void bundledSkillsAreLoadedFromResources() throws Exception {
        File dir = Files.createTempDirectory("cortexdrone-skills").toFile();
        try {
            SkillLibrary library = new SkillLibrary(dir);
            List<SkillLibrary.SkillEntry> all = library.searchSkills("", 100);
            Set<String> names = new HashSet<>();
            for (SkillLibrary.SkillEntry entry : all) {
                names.add(entry.getName());
            }
            assertTrue(names.contains("mine_and_deliver"));
            assertTrue(names.contains("mine_blocks"));
            assertTrue(names.contains("transport_entity"));
            assertTrue(names.contains("store_items"));
        } finally {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) file.delete();
            }
            dir.delete();
        }
    }
}
