package ir.layercloud.cortexdrone.harness.skill;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class SkillLibraryTest {
    @Test
    public void bundledSkillsLoadAndSearchByNaturalLanguage() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-skills").toFile();
        SkillLibrary library = new SkillLibrary(root);
        List<Skill> all = library.search("", 100);
        Set<String> names = new HashSet<>();
        for (Skill skill : all) names.add(skill.getName());
        assertTrue(names.contains("mine_and_deliver"));
        assertTrue(names.contains("mine_blocks"));
        assertTrue(names.contains("transport_entity"));
        assertTrue(names.contains("store_items"));

        List<Skill> mine = library.search("mine iron ore and bring it back", 3);
        assertFalse(mine.isEmpty());
        assertEquals("mine_and_deliver", mine.get(0).getName());
    }

    @Test
    public void saveVersionsAndReloadsFromConfigDirectory() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-skills-save").toFile();
        SkillLibrary library = new SkillLibrary(root);
        Skill first = library.save("test_skill", "does a thing", "test thing", null,
                "return drone.state();", "saved");
        assertNotNull(first);
        assertEquals(1, first.getVersion());

        Skill second = library.save("test_skill", "does a better thing", "test thing", null,
                "return drone.state();", "refined");
        assertNotNull(second);
        assertEquals(2, second.getVersion());

        SkillLibrary reloaded = new SkillLibrary(root);
        assertEquals(2, reloaded.get("test_skill").getVersion());
        assertEquals("does a better thing", reloaded.get("test_skill").getDescription());
    }
}
