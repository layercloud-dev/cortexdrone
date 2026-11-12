package ir.layercloud.cortexdrone.harness.skill;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
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
    public void retiredSkillsJsonIsNotReadOrMigrated() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-skills-retired").toFile();
        File legacy = new File(root, "skills.json");
        String json = "[{\"name\":\"old_learned\",\"description\":\"old\","
                + "\"code\":\"return 'old';\",\"source\":\"saved\",\"version\":1}]";
        Files.write(legacy.toPath(), json.getBytes("UTF-8"));

        SkillLibrary library = new SkillLibrary(root);
        assertNull("the retired single-file store must not be imported",
                library.get("old_learned"));
        assertTrue("the retired file is left untouched", legacy.isFile());
        assertFalse(new File(root, "skills/old_learned").exists());
    }

    @Test
    public void learnedSkillIsStoredAsSkillMarkdownDirectory() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-skills-persist").toFile();
        SkillLibrary library = new SkillLibrary(root);
        Skill saved = library.save("my_skill", "mine", "test mine", null,
                Collections.singletonList("pickaxe"),
                "# My skill\n\nUse it for tests.\n", "return 1;", "saved");
        assertNotNull(saved);

        File dir = new File(root, "skills/my_skill");
        File markdown = new File(dir, "SKILL.md");
        File code = new File(dir, "code.js");
        assertTrue("SKILL.md must exist", markdown.isFile());
        assertTrue("code.js must exist", code.isFile());
        String text = new String(Files.readAllBytes(markdown.toPath()), "UTF-8");
        assertTrue(text.startsWith("---"));
        assertTrue(text.contains("name: \"my_skill\""));
        assertTrue(text.contains("# My skill"));
        assertTrue(new String(Files.readAllBytes(code.toPath()), "UTF-8").contains("return 1;"));

        // Bundled skills are never copied into the config directory.
        assertFalse(new File(root, "skills/mine_blocks").exists());
    }

    @Test
    public void saveVersionsAndReloadsFromConfigDirectory() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-skills-save").toFile();
        SkillLibrary library = new SkillLibrary(root);
        Skill first = library.save("test_skill", "does a thing", "test thing", null,
                "return drone.state();", "saved");
        assertNotNull(first);
        assertEquals(1, first.getVersion());
        assertTrue(new File(root, "skills/test_skill/SKILL.md").isFile());

        Skill second = library.save("test_skill", "does a better thing", "test thing", null,
                "return drone.state();", "refined");
        assertNotNull(second);
        assertEquals(2, second.getVersion());

        SkillLibrary reloaded = new SkillLibrary(root);
        assertEquals(2, reloaded.get("test_skill").getVersion());
        assertEquals("does a better thing", reloaded.get("test_skill").getDescription());
    }

    @Test
    public void deletingAnOverrideRestoresTheBundledSkill() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-skills-delete").toFile();
        SkillLibrary library = new SkillLibrary(root);
        library.save("mine_blocks", "override", "", null, "return 'custom';", "refined");
        assertTrue(library.get("mine_blocks").getCode().contains("custom"));

        assertTrue(library.delete("mine_blocks"));
        Skill restored = library.get("mine_blocks");
        assertNotNull(restored);
        assertEquals("bundled", restored.getSource());
        assertTrue(restored.getCode().contains("scanVeins"));
        assertFalse(new File(root, "skills/mine_blocks").exists());
    }
}
