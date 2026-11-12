package ir.layercloud.cortexdrone.service.world;

import ir.layercloud.cortexdrone.config.ModPaths;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The live server lost base blocks during a mining task. The rule existed only
 * as prose in memory, so these tests pin the machine-readable enforcement:
 * inside the cylinder is protected, outside is free, and a reload swaps the
 * set atomically.
 */
public class ProtectionZonesTest {
    @After
    public void resetRegistry() {
        ProtectionZones.configure(null);
    }

    private static ProtectedZone base() {
        // The recorded base: chamber at (-301,55,502) with a vertical shaft to
        // the surface around y=100, protected by the owner's 50-block rule.
        return new ProtectedZone("base", 0, -301, 85, 502, 50, 70);
    }

    @Test
    public void chamberAndShaftAreInsideTheZone() {
        ProtectionZones.configure(Arrays.asList(base()));
        assertTrue("chamber floor", ProtectionZones.blocksBreaking(0, -301, 55, 502));
        assertTrue("shaft midpoint", ProtectionZones.blocksBreaking(0, -301, 110, 502));
        assertTrue("shaft wall", ProtectionZones.blocksBreaking(0, -299, 108, 503));
        assertTrue("edge of the horizontal radius", ProtectionZones.blocksBreaking(0, -252, 57, 502));
    }

    @Test
    public void miningAreaAndOtherDimensionsStayFree() {
        ProtectionZones.configure(Arrays.asList(base()));
        assertFalse("recorded mining area at -219,78,597",
                ProtectionZones.blocksBreaking(0, -219, 78, 597));
        assertFalse("nether", ProtectionZones.blocksBreaking(-1, -301, 55, 502));
        assertFalse("one block outside the horizontal edge",
                ProtectionZones.blocksBreaking(0, -250, 57, 502));
    }

    @Test
    public void zoneAtNamesTheOwnerVolume() {
        ProtectionZones.configure(Arrays.asList(base()));
        ProtectedZone zone = ProtectionZones.zoneAt(null, 0, 0, 0);
        assertNull("a null world has no zone", zone);
        ProtectionZones.configure(Collections.<ProtectedZone>emptyList());
        assertFalse(ProtectionZones.blocksBreaking(0, -301, 55, 502));
    }

    @Test
    public void malformedZonesAreRepairedNotCrashing() {
        ProtectedZone broken = new ProtectedZone(null, 0, 0, 0, 0, -3, Double.NaN);
        ProtectionZones.configure(Arrays.asList(null, broken));
        List<ProtectedZone> active = ProtectionZones.all();
        assertNotNull(active);
        assertTrue(active.size() == 1);
        assertTrue("radius must be repaired", active.get(0).getRadius() > 0);
        assertTrue("label must be repaired", active.get(0).getLabel().length() > 0);
    }

    @Test
    public void theWorldZoneFileIsTheOnlySource() throws Exception {
        File world = Files.createTempDirectory("cortexdrone-world").toFile();
        File file = ModPaths.protectedZonesFile(world);
        Files.write(file.toPath(),
                ("[{\"label\":\"base\",\"dimension\":0,\"x\":-301,\"y\":85,\"z\":502,"
                        + "\"radius\":50,\"verticalRadius\":70}]")
                        .getBytes(StandardCharsets.UTF_8));

        ProtectionZones.configure(null);
        assertEquals(1, ProtectionZones.load(file));
        assertTrue(ProtectionZones.blocksBreaking(0, -301, 55, 502));

        // An emptied world file must clear the previous set, not keep it.
        Files.write(file.toPath(), "[]".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, ProtectionZones.load(file));
        assertFalse(ProtectionZones.blocksBreaking(0, -301, 55, 502));
    }

    @Test
    public void loadingAMissingWorldFileClearsZones() throws Exception {
        File world = Files.createTempDirectory("cortexdrone-empty").toFile();
        ProtectionZones.configure(Arrays.asList(base()));
        assertEquals(0, ProtectionZones.load(ModPaths.protectedZonesFile(world)));
        assertEquals(0, ProtectionZones.count());
    }

    @Test
    public void navAndBreakPathsConsultTheRegistry() {
        // Structural guard: the enforcement points are what turn a memory entry
        // into an actual refusal.
        String planner = read("src/main/java/ir/layercloud/cortexdrone/service/navigation/impl/TunnelPlanner.java");
        String dig = read("src/main/java/ir/layercloud/cortexdrone/service/navigation/impl/DigPathfinder.java");
        String tools = read("src/main/java/ir/layercloud/cortexdrone/harness/tools/BlockTools.java");
        assertTrue("automatic tunnelling must refuse protected blocks",
                planner.contains("ProtectionZones.blocksBreaking"));
        assertTrue("dig-aware A* must treat protected blocks as walls",
                dig.contains("ProtectionZones.blocksBreaking"));
        assertTrue("explicit break_block must ask for confirmation",
                tools.contains("confirm_zone_break"));
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
