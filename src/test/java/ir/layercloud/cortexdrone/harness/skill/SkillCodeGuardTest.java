package ir.layercloud.cortexdrone.harness.skill;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

public class SkillCodeGuardTest {
    @Test
    public void acceptsParameterizedWorkflow() {
        String code = ""
                + "var cfg = (typeof params === 'object' && params !== null) ? params : {};\n"
                + "var playerName = cfg.player || ctx.player || ctx.owner;\n"
                + "var player = drone.getPlayer(playerName ? { name: playerName } : {});\n"
                + "var dest = cfg.destination || { x: player.blockX, y: player.blockY + 2, z: player.blockZ };\n"
                + "var found = drone.getEntities({ radius: cfg.radius || 10, filter: 'player' });\n"
                + "var target = null;\n"
                + "for (var i = 0; i < found.entities.length; i++) {\n"
                + "  if (!playerName || found.entities[i].name === playerName) { target = found.entities[i]; break; }\n"
                + "}\n"
                + "if (!target) return { ok: false, error: 'player not found near the drone' };\n"
                + "drone.pickEntity({ entity_id: target.id });\n"
                + "drone.goto({ x: dest.x, y: dest.y, z: dest.z });\n";
        assertTrue(SkillCodeGuard.describe(SkillCodeGuard.inspect(code)),
                SkillCodeGuard.isClean(code));
    }

    @Test
    public void rejectsHardcodedPlayerNameComparison() {
        String code = ""
                + "var found = drone.getEntities({ radius: 10, filter: 'player' });\n"
                + "for (var i = 0; i < found.entities.length; i++) {\n"
                + "  if (found.entities[i].name === 'MHProDev') { target = found.entities[i]; break; }\n"
                + "}\n";
        List<SkillCodeGuard.Violation> violations = SkillCodeGuard.inspect(code);
        assertEquals(1, violations.size());
        assertEquals(SkillCodeGuard.Kind.PLAYER_NAME, violations.get(0).kind());
        assertEquals(3, violations.get(0).line());
    }

    @Test
    public void rejectsHardcodedPlayerNameOptionAndDefault() {
        assertFalse(SkillCodeGuard.isClean(
                "var p = drone.getPlayer({ name: 'Steve' });"));
        assertFalse(SkillCodeGuard.isClean(
                "var playerName = cfg.player || 'MHProDev';"));
    }

    @Test
    public void rejectsHardcodedCoordinates() {
        assertFalse(SkillCodeGuard.isClean(
                "drone.goto({ x: -1170, y: 65, z: -1030, max_wait_ms: 120000 });"));
        assertFalse(SkillCodeGuard.isClean(
                "drone.goto(-1170, 65, -1030);"));
        assertFalse(SkillCodeGuard.isClean(
                "var base = { x: 240, y: 64, z: -80 };"));
        assertFalse(SkillCodeGuard.isClean(
                "drone.withdrawChest({ filter: 'iron', from_x: 240, from_y: 64, from_z: -80 });"));
    }

    @Test
    public void acceptsCoordinatesDerivedFromState() {
        String code = ""
                + "var p = drone.getPlayer();\n"
                + "var dest = { x: p.blockX, y: p.blockY + 1, z: p.blockZ };\n"
                + "drone.goto({ x: dest.x, y: dest.y, z: dest.z });\n";
        assertTrue(SkillCodeGuard.isClean(code));
    }

    @Test
    public void ignoresLiteralsInsideComments() {
        String code = ""
                + "// old run was at x: -1170, y: 65, z: -1030 for MHProDev\n"
                + "/* entity_id: 4821, slot: 5 */\n"
                + "var p = drone.getPlayer();\n";
        assertTrue(SkillCodeGuard.describe(SkillCodeGuard.inspect(code)),
                SkillCodeGuard.isClean(code));
    }

    @Test
    public void rejectsHardcodedEntityIdSlotAndDimension() {
        assertFalse(SkillCodeGuard.isClean(
                "drone.pickEntity({ entity_id: 4821 });"));
        assertFalse(SkillCodeGuard.isClean(
                "drone.moveItem({ from_slot: 5, to_slot: 36, count: 8 });"));
        assertFalse(SkillCodeGuard.isClean(
                "var slot = 36; drone.moveItem({ from_slot: slot });"));
        assertFalse(SkillCodeGuard.isClean(
                "var entityId = 4821; drone.pickEntity({ entity_id: entityId });"));
        assertFalse(SkillCodeGuard.isClean(
                "var dim = player.dimension; drone.memory({ action: 'remember', name: 'base', x: 1, y: 2, z: 3, dimension: 0 });"));
        assertTrue(SkillCodeGuard.isClean(
                "drone.moveItem({ from_slot: -1, to_slot: -1, count: cfg.count || 8 });"));
    }

    @Test
    public void rejectsCoordinatesAssignedToVariables() {
        assertFalse(SkillCodeGuard.isClean("var homeX = -1170;"));
        assertFalse(SkillCodeGuard.isClean("var homeZ = -1030;"));
        assertFalse(SkillCodeGuard.isClean("var x = 100, z = 200;"));
        assertTrue(SkillCodeGuard.isClean("var x = 2; var z = -3;"));
    }

    @Test
    public void rejectsTheReportedRecordedWorkflow() {
        String code = ""
                + "var p = drone.getPlayer({});\n"
                + "var found = drone.getEntities({ radius: 10, filter: 'player' });\n"
                + "var target = null;\n"
                + "for (var i = 0; i < (found.entities || []).length; i++) {\n"
                + "  if (found.entities[i].name === 'MHProDev') { target = found.entities[i]; break; }\n"
                + "}\n"
                + "drone.pickEntity({ entity_id: target.id });\n"
                + "var dest = { x: -1170, y: 65, z: -1030 };\n"
                + "drone.goto({ x: dest.x, y: dest.y, z: dest.z, max_wait_ms: 150000 });\n"
                + "drone.unpickEntity({});\n";
        List<SkillCodeGuard.Violation> violations = SkillCodeGuard.inspect(code);
        assertTrue("the reported script must be rejected", violations.size() >= 2);
        boolean player = false;
        boolean position = false;
        for (SkillCodeGuard.Violation violation : violations) {
            player |= violation.kind() == SkillCodeGuard.Kind.PLAYER_NAME;
            position |= violation.kind() == SkillCodeGuard.Kind.POSITION;
        }
        assertTrue("player name must be flagged: " + SkillCodeGuard.describe(violations), player);
        assertTrue("coordinates must be flagged: " + SkillCodeGuard.describe(violations), position);
    }

    @Test
    public void legitimateCoordinateSizedVariablesAreNotRejected() {
        String sizes = "var chunkX = 0; var chunkZ = 0; var sizeX = 7; var sizeZ = 7;";
        assertTrue(SkillCodeGuard.describe(SkillCodeGuard.inspect(sizes)),
                SkillCodeGuard.isClean(sizes));
        String heights = "var maxY = 255; var minY = 4; var indexY = 0;";
        assertTrue("a build height is not a position",
                SkillCodeGuard.isClean(heights));
        assertFalse("a named base coordinate is still instance data",
                SkillCodeGuard.isClean("var baseY = 64;"));
    }

    @Test
    public void everyBundledSkillIsParameterized() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-guard-bundled").toFile();
        SkillLibrary library = new SkillLibrary(root);
        for (Skill skill : library.all()) {
            assertTrue("bundled skill '" + skill.getName() + "' embeds instance data: "
                            + SkillCodeGuard.describe(SkillCodeGuard.inspect(skill.getCode())),
                    SkillCodeGuard.isClean(skill.getCode()));
        }
    }

    @Test
    public void libraryRefusesToPersistHardcodedCode() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-guard-save").toFile();
        SkillLibrary library = new SkillLibrary(root);
        assertNull(library.save("leaky", "bad skill", "", null,
                "var p = drone.getPlayer({ name: 'MHProDev' });", "refined"));
        assertFalse(new File(root, "skills/leaky").exists());
    }
}
