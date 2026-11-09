package ir.layercloud.cortexdrone.harness.skill;

import ir.layercloud.cortexdrone.service.navigation.impl.LandingScanner;
import ir.layercloud.cortexdrone.service.navigation.NavigationLimits;
import ir.layercloud.cortexdrone.service.inventory.InventoryLimits;
import ir.layercloud.cortexdrone.service.world.ScanLimits;
import ir.layercloud.cortexdrone.service.world.WorldBounds;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * The script-side limit table mirrors the Java policy classes.
 *
 * <p>Rhino cannot import Java constants into a skill, so {@code js/bootstrap.js}
 * carries one {@code LIMITS} object that mirrors them. This test pins every value
 * to its Java owner: a Java change fails here instead of silently diverging from
 * the shipped skills.</p>
 */
public class TransferCountAgreementTest {
    private static final Path BOOTSTRAP = Paths.get("src/main/resources/js/bootstrap.js");
    private static final Path SKILLS = Paths.get("src/main/resources/skills");

    private static String bootstrap() throws Exception {
        return new String(Files.readAllBytes(BOOTSTRAP), StandardCharsets.UTF_8);
    }

    /**
     * Reads one {@code LIMITS.<name>} value.
     *
     * <p>The entry may be a literal or a reference to another named constant
     * ({@code MAX_TRANSFER_COUNT}).</p>
     */
    private static int limit(String name) throws Exception {
        String source = bootstrap();
        Pattern pattern = Pattern.compile(
                "\\b" + Pattern.quote(name) + "\\s*:\\s*([A-Za-z_][A-Za-z0-9_]*|\\d+)");
        Matcher matcher = pattern.matcher(source);
        assertTrue("js/bootstrap.js must declare LIMITS." + name, matcher.find());
        String value = matcher.group(1);
        if (value.matches("\\d+")) return Integer.parseInt(value);
        Pattern named = Pattern.compile("var\\s+" + Pattern.quote(value) + "\\s*=\\s*(\\d+)\\s*;");
        Matcher namedMatcher = named.matcher(source);
        assertTrue("cannot resolve LIMITS." + name + " -> " + value, namedMatcher.find());
        return Integer.parseInt(namedMatcher.group(1));
    }

    @Test
    public void theTransferCeilingMatchesItsJavaOwner() throws Exception {
        Matcher matcher = Pattern.compile("var\\s+MAX_TRANSFER_COUNT\\s*=\\s*(\\d+)\\s*;")
                .matcher(bootstrap());
        assertTrue("js/bootstrap.js must declare MAX_TRANSFER_COUNT", matcher.find());
        assertEquals("the Java limit and the script prelude must be the same number",
                InventoryLimits.MAX_TRANSFER_COUNT, Integer.parseInt(matcher.group(1)));
    }

    @Test
    public void theFootprintAndHeightLimitsMatchWorldBounds() throws Exception {
        assertEquals(WorldBounds.MIN_PLAN_SIDE, limit("footprintMin"));
        assertEquals(WorldBounds.MAX_PLAN_SIDE, limit("footprintMax"));
        assertEquals(WorldBounds.MIN_PLAN_HEIGHT, limit("planHeightMin"));
        assertEquals(WorldBounds.MAX_PLAN_HEIGHT, limit("planHeightMax"));
        assertEquals(WorldBounds.MIN_PLAN_SIDE, limit("siteSideMin"));
        assertEquals(WorldBounds.MAX_SITE_SIDE, limit("siteSideMax"));
        assertEquals(WorldBounds.MAX_LANDING_Y, limit("landingY"));
    }

    @Test
    public void theScanRadiusLimitsMatchTheirJavaOwners() throws Exception {
        assertEquals(ScanLimits.MIN_RADIUS, limit("blockScanRadiusMin"));
        assertEquals(ScanLimits.MIN_RADIUS, limit("dimensionMin"));
        assertEquals(ScanLimits.DEFAULT_RADIUS, limit("scanRadiusDefault"));
        assertEquals(ScanLimits.TIME_SLICE_RADIUS, limit("smallScanRadiusMax"));
        assertEquals(ScanLimits.MAX_VEIN_RADIUS, limit("mediumRadiusMax"));
        assertEquals(ScanLimits.MAX_RADIUS, limit("scanRadiusMax"));
        assertEquals(InventoryLimits.MAX_FURNACE_RADIUS, limit("furnaceRadiusMax"));
        assertEquals(ScanLimits.MIN_DROP_RADIUS, limit("dropRadiusMin"));
        assertEquals(ScanLimits.MIN_DISTANT_RADIUS, limit("distantRadiusMin"));
        assertEquals(ScanLimits.MAX_DISTANT_RADIUS, limit("distantRadiusMax"));
        assertEquals(ScanLimits.DEFAULT_DISTANT_RADIUS, limit("distantRadiusDefault"));
        assertEquals(NavigationLimits.MIN_DIG_BLOCKS, limit("minDigBlocks"));
        assertEquals(NavigationLimits.MAX_DIG_BLOCKS, limit("maxDigBlocks"));
        assertEquals(NavigationLimits.MAX_WAIT_MS, limit("maxWaitMs"));
        assertEquals(LandingScanner.MAX_RADIUS, limit("landingRadiusMax"));
        assertEquals(NavigationLimits.DEFAULT_RECOVERY_ATTEMPTS, limit("recoveryAttempts"));
        assertEquals(InventoryLimits.DEFAULT_RADIUS, limit("inventoryRadiusDefault"));
        assertEquals(InventoryLimits.DEFAULT_CHEST_RADIUS, limit("chestRadiusDefault"));
        assertEquals(ScanLimits.DEFAULT_ROOM_RADIUS, limit("roomRadiusDefault"));
        assertEquals(LandingScanner.DEFAULT_RADIUS, limit("landingRadiusDefault"));
        assertEquals(InventoryLimits.DEFAULT_TRANSFER_COUNT, limit("defaultTransferCount"));
        assertEquals(NavigationLimits.DEFAULT_DIG_BLOCKS, limit("defaultDigBlocks"));
        assertEquals(WorldBounds.MAX_COORDINATE, limit("maxCoordinate"));
    }

    @Test
    public void noSkillHardCodesTheTransferCeiling() throws Exception {
        List<String> offenders = new ArrayList<>();
        String literal = String.valueOf(InventoryLimits.MAX_TRANSFER_COUNT);
        for (Path file : files()) {
            for (String line : read(file).split("\n")) {
                String code = line.trim();
                if (code.startsWith("*") || code.startsWith("//") || code.startsWith("#")) continue;
                if (code.contains(literal) && !code.contains("MAX_TRANSFER_COUNT")) {
                    offenders.add(file + ": " + code);
                }
            }
        }
        assertTrue("use MAX_TRANSFER_COUNT instead of the raw ceiling:\n"
                + String.join("\n", offenders), offenders.isEmpty());
    }

    @Test
    public void noSkillRestatesAPlanBound() throws Exception {
        List<String> offenders = new ArrayList<>();
        // Pairs specific enough not to collide with unrelated counts; the
        // semantic radius guard lives in SkillBoundsAgreementTest.
        String[][] pairs = {
                {"3", String.valueOf(WorldBounds.MAX_PLAN_SIDE)},
                {"2", String.valueOf(WorldBounds.MAX_PLAN_HEIGHT)},
                {String.valueOf(WorldBounds.MIN_PLAN_SIDE), String.valueOf(WorldBounds.MAX_SITE_SIDE)},
        };
        for (Path file : files()) {
            for (String line : read(file).split("\n")) {
                String code = line.trim();
                if (!code.contains("clamp(")) continue;
                if (code.contains("LIMITS.")) continue;
                for (String[] pair : pairs) {
                    String literal = ", " + pair[0] + ", " + pair[1] + ")";
                    if (code.contains(literal)) offenders.add(file + ": " + code);
                }
            }
        }
        assertTrue("use LIMITS.* instead of a restated bound:\n"
                + String.join("\n", offenders), offenders.isEmpty());
    }

    @Test
    public void thePolicySkillsUseTheSharedLimits() throws Exception {
        String[] required = {
                "build_structure", "build_platform", "build_wall", "smelt_items",
                "sort_chests", "gather_distant", "collect_drops", "mine_blocks",
                "harvest_crops", "carry_player"
        };
        List<String> offenders = new ArrayList<>();
        for (String skill : required) {
            Path file = SKILLS.resolve(skill).resolve("code.js");
            if (!Files.exists(file) || !read(file).contains("LIMITS.")) offenders.add(skill);
        }
        assertTrue("these skills own a Java-policy bound and must read LIMITS.*:\n"
                + String.join("\n", offenders), offenders.isEmpty());
    }

    private static List<Path> files() throws Exception {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(SKILLS)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        return files;
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
