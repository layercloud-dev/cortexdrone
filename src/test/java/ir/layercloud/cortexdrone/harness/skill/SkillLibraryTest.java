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
    public void disablingALearnedSkillMovesItAsideAndRestoresTheBundledOne() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-skills-disable").toFile();
        SkillLibrary library = new SkillLibrary(root);
        library.save("mine_blocks", "override", "", null, "return 'custom';", "refined");

        assertTrue(library.disable("mine_blocks"));
        assertEquals("the bundled skill is active again",
                "bundled", library.get("mine_blocks").getSource());
        assertTrue("the learned directory is kept, not deleted",
                new File(root, "skills-disabled/mine_blocks/SKILL.md").isFile());
        assertFalse(new File(root, "skills/mine_blocks").exists());
        assertTrue(library.disabledNames().contains("mine_blocks"));

        assertTrue(library.enable("mine_blocks"));
        assertEquals("refined", library.get("mine_blocks").getSource());
        assertTrue(library.get("mine_blocks").getCode().contains("custom"));
        assertFalse(new File(root, "skills-disabled/mine_blocks").exists());

        // A restart sees the enabled directory again, not the retired one.
        assertEquals("refined", new SkillLibrary(root).get("mine_blocks").getSource());
    }

    @Test
    public void bundledOrUnknownSkillsCannotBeDisabled() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-skills-disable-bundled").toFile();
        SkillLibrary library = new SkillLibrary(root);
        assertFalse("a bundled skill has no directory to retire", library.disable("guard"));
        assertNotNull(library.get("guard"));
        assertFalse(library.disable("does_not_exist"));
        assertFalse(library.enable("does_not_exist"));
        assertTrue(library.disabledNames().isEmpty());
    }

    @Test
    public void theDirectoryNameOwnsTheLearnedSkillKey() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-skills-dirname").toFile();
        File dir = new File(root, "skills/My Farm");
        assertTrue(dir.mkdirs());
        Files.write(new File(dir, "SKILL.md").toPath(),
                ("---\nname: \"Fancy Farm\"\ndescription: \"tend the farm\"\n---\n\n# Farm\n")
                        .getBytes("UTF-8"));
        Files.write(new File(dir, "code.js").toPath(), "return 'farm';".getBytes("UTF-8"));

        SkillLibrary library = new SkillLibrary(root);
        assertNotNull("the directory name is the stable key", library.get("my_farm"));
        assertEquals("The directory, not the frontmatter, is authoritative",
                "my_farm", library.get("my_farm").getName());

        // disable/delete must address the directory that actually exists.
        assertTrue(library.disable("my_farm"));
        assertTrue(new File(root, "skills-disabled/My Farm/SKILL.md").isFile());
        assertTrue(library.enable("my_farm"));
        assertTrue(new File(root, "skills/My Farm/SKILL.md").isFile());
    }

    @Test
    public void aUtf8BomDoesNotHideTheFrontmatter() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-skills-bom").toFile();
        File dir = new File(root, "skills/bom_skill");
        assertTrue(dir.mkdirs());
        String text = "\uFEFF---\nname: \"bom_skill\"\ndescription: \"survives a BOM\"\n"
                + "triggers:\n  - \"do the bom thing\"\n---\n\n# Body\n";
        Files.write(new File(dir, "SKILL.md").toPath(), text.getBytes("UTF-8"));
        Files.write(new File(dir, "code.js").toPath(), "return 'bom';".getBytes("UTF-8"));

        Skill skill = new SkillLibrary(root).get("bom_skill");
        assertNotNull(skill);
        assertEquals("survives a BOM", skill.getDescription());
        assertTrue("the YAML block must not leak into docs",
                !skill.getDocs().contains("description:"));
        assertEquals(1, skill.getTriggers().size());
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
