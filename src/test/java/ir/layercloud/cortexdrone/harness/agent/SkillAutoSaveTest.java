package ir.layercloud.cortexdrone.harness.agent;

import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import ir.layercloud.cortexdrone.harness.tool.ToolCatalog;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * The auto-save safety net must keep working workflows and reject recordings.
 */
public class SkillAutoSaveTest {
    private static final String CLEAN_MUTATING_SCRIPT = ""
            + "var p = drone.getPlayer();\n"
            + "var placed = 0;\n"
            + "var block = 'stone';\n"
            + "for (var i = 0; i < 4; i++) {\n"
            + "  var result = drone.placeBlock({ x: p.blockX + i, y: p.blockY, z: p.blockZ, block: block });\n"
            + "  if (result && result.ok !== false) placed++;\n"
            + "}\n"
            + "var after = drone.state();\n"
            + "return { ok: placed > 0, placed: placed, at: { x: after.x, y: after.y, z: after.z } };\n";

    private static SkillAutoSave autoSave(File root) {
        ToolRegistry registry = new ToolRegistry();
        registry.declareAll(ToolCatalog.standard());
        return new SkillAutoSave(registry, new SkillLibrary(root), new DroneLogger());
    }

    @Test
    public void aCleanMutatingScriptIsSaved() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-autosave-good").toFile();
        SkillAutoSave autoSave = autoSave(root);
        autoSave.maybeSave(null, "build a small stone wall", CLEAN_MUTATING_SCRIPT, false);

        String expected = ChatLines.skillName("build a small stone wall");
        assertNotNull("a clean mutating workflow must be kept",
                new SkillLibrary(root).get(expected));
    }

    @Test
    public void aRefinedOrReadOnlyScriptIsNotDuplicated() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-autosave-skip").toFile();
        SkillAutoSave autoSave = autoSave(root);

        autoSave.maybeSave(null, "build a small stone wall", CLEAN_MUTATING_SCRIPT, true);
        assertNull(new SkillLibrary(root).get(ChatLines.skillName("build a small stone wall")));

        String readOnly = "var s = drone.state();\n"
                + "var found = drone.scanBlocks({ filter: 'iron_ore', radius: 24 });\n"
                + "return { count: found.count, y: s.y, note: 'recon only, no mutation' };\n";
        autoSave.maybeSave(null, "look for iron ore", readOnly, false);
        assertNull(new SkillLibrary(root).get(ChatLines.skillName("look for iron ore")));
    }

    @Test
    public void aRecordingWithInstanceDataIsRefused() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-autosave-hardcoded").toFile();
        SkillAutoSave autoSave = autoSave(root);

        String recording = ""
                + "drone.goto({ x: -1170, y: 65, z: -1030 });\n"
                + "drone.placeBlock({ x: -1170, y: 65, z: -1030, block: 'stone' });\n"
                + "return { ok: true };\n";
        autoSave.maybeSave(null, "build where I stood before", recording, false);
        assertNull("a hardcoded recording must not become a skill",
                new SkillLibrary(root).get(ChatLines.skillName("build where I stood before")));
    }
}
