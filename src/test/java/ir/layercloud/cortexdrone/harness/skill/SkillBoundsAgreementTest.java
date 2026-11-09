package ir.layercloud.cortexdrone.harness.skill;

import ir.layercloud.cortexdrone.service.navigation.impl.LandingScanner;
import ir.layercloud.cortexdrone.service.inventory.InventoryLimits;
import ir.layercloud.cortexdrone.service.navigation.NavigationLimits;
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
 * Shipped skills must not restate a Java-owned bound as a literal.
 *
 * <p>Regression: the LIMITS migration initially covered only four exact
 * {@code clamp} pairs, so 24 skill files still copied Java policy values —
 * fourteen used the scan floor {@code 4}, {@code mine_box}/{@code quarry} used
 * the site ceiling {@code 33}, and {@code transport_entity} used the landing
 * ceiling {@code 250}. Each copy could drift silently.</p>
 *
 * <p>This guard is semantic rather than a list of exact pairs: any clamp whose
 * expression names a radius/dimension must use the LIMITS entry for a bound the
 * Java policy owns.</p>
 */
public class SkillBoundsAgreementTest {
    private static final Path SKILLS = Paths.get("src/main/resources/skills");
    private static final Pattern CLAMP = Pattern.compile(
            "clamp\\(([^,]+),\\s*(\\d+|LIMITS\\.[A-Za-z0-9_]+)\\s*,\\s*(\\d+|LIMITS\\.[A-Za-z0-9_]+)\\s*\\)");

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static List<Path> files() throws Exception {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(SKILLS)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        return files;
    }

    private static boolean literal(String token, int value) {
        return String.valueOf(value).equals(token);
    }

    @Test
    public void radiusClampsUseTheSharedScanBounds() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : files()) {
            for (String line : read(file).split("\n")) {
                Matcher matcher = CLAMP.matcher(line);
                while (matcher.find()) {
                    String expression = matcher.group(1).toLowerCase();
                    if (!expression.contains("radius") && !expression.contains("span")) continue;
                    String low = matcher.group(2);
                    String high = matcher.group(3);
                    if (literal(low, ScanLimits.MIN_RADIUS)) {
                        offenders.add(file + ": scan floor -> LIMITS.blockScanRadiusMin: " + line.trim());
                    }
                    if (literal(low, ScanLimits.MIN_BUILD_SITE_RADIUS)) {
                        offenders.add(file + ": build-site floor -> LIMITS.blockScanRadiusMin: " + line.trim());
                    }
                    if (literal(high, ScanLimits.MAX_RADIUS)) {
                        offenders.add(file + ": scan ceiling -> LIMITS.scanRadiusMax: " + line.trim());
                    }
                    if (literal(high, ScanLimits.TIME_SLICE_RADIUS)) {
                        offenders.add(file + ": small scan cap -> LIMITS.smallScanRadiusMax: " + line.trim());
                    }
                    if (literal(high, ScanLimits.MAX_VEIN_RADIUS)) {
                        offenders.add(file + ": medium cap -> LIMITS.mediumRadiusMax: " + line.trim());
                    }
                    if (literal(high, ScanLimits.MAX_DISTANT_RADIUS)) {
                        offenders.add(file + ": distant ceiling -> LIMITS.distantRadiusMax: " + line.trim());
                    }
                }
            }
        }
        assertTrue("use the shared scan bounds:\n" + String.join("\n", offenders), offenders.isEmpty());
    }

    @Test
    public void footprintClampsUseTheSharedPlanBounds() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : files()) {
            for (String line : read(file).split("\n")) {
                Matcher matcher = CLAMP.matcher(line);
                while (matcher.find()) {
                    String expression = matcher.group(1).toLowerCase();
                    if (!expression.contains("width") && !expression.contains("length")
                            && !expression.contains("depth") && !expression.contains("height")) {
                        continue;
                    }
                    String high = matcher.group(3);
                    if (literal(high, WorldBounds.MAX_SITE_SIDE)) {
                        offenders.add(file + ": site ceiling -> LIMITS.siteSideMax: " + line.trim());
                    }
                    if (literal(high, WorldBounds.MAX_PLAN_SIDE)) {
                        offenders.add(file + ": plan ceiling -> LIMITS.footprintMax: " + line.trim());
                    }
                }
            }
        }
        assertTrue("use the shared plan bounds:\n" + String.join("\n", offenders), offenders.isEmpty());
    }

    @Test
    public void navigationBoundsInSkillsUseTheSharedTable() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : files()) {
            for (String line : read(file).split("\n")) {
                String code = line.trim();
                java.util.regex.Matcher wait = java.util.regex.Pattern
                        .compile("max_wait_ms:\\s*(\\d+)").matcher(code);
                if (wait.find()
                        && Integer.parseInt(wait.group(1)) == NavigationLimits.MAX_WAIT_MS
                        && !code.contains("LIMITS.maxWaitMs")) {
                    offenders.add(file + ": max_wait_ms -> LIMITS.maxWaitMs: " + code);
                }
                java.util.regex.Matcher dig = java.util.regex.Pattern
                        .compile("max_dig_blocks:\\s*(\\d+)").matcher(code);
                if (dig.find()) {
                    int value = Integer.parseInt(dig.group(1));
                    if ((value == NavigationLimits.MAX_DIG_BLOCKS
                            || value == NavigationLimits.MIN_DIG_BLOCKS)
                            && !code.contains("LIMITS.")) {
                        offenders.add(file + ": max_dig_blocks -> LIMITS.min/maxDigBlocks: " + code);
                    }
                }
            }
        }
        assertTrue("use the shared navigation bounds:\n" + String.join("\n", offenders),
                offenders.isEmpty());
    }

    @Test
    public void javaOwnedDefaultsInSkillsUseTheSharedTable() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : files()) {
            for (String line : read(file).split("\n")) {
                String code = line.trim();
                if (code.startsWith("//")) continue;
                if (code.contains("recovery_attempts: "
                        + NavigationLimits.DEFAULT_RECOVERY_ATTEMPTS)
                        && !code.contains("LIMITS.recoveryAttempts")) {
                    offenders.add(file + ": recovery_attempts -> LIMITS.recoveryAttempts: " + code);
                }
                if (code.matches(".*radius\\s*(\\|\\||:)\\s*"
                        + InventoryLimits.DEFAULT_RADIUS + "\\b.*")
                        && !code.contains("LIMITS.")) {
                    offenders.add(file + ": radius default 32 -> LIMITS.inventoryRadiusDefault: " + code);
                }
                if (code.matches(".*radius\\s*(\\|\\||:)\\s*"
                        + InventoryLimits.DEFAULT_CHEST_RADIUS + "\\b.*")
                        && !code.contains("LIMITS.")) {
                    offenders.add(file + ": radius default 28 -> LIMITS.chestRadiusDefault: " + code);
                }
                if (code.matches(".*(max_)?radius\\s*(\\|\\||:)\\s*"
                        + ScanLimits.DEFAULT_ROOM_RADIUS + "\\b.*")
                        && !code.contains("LIMITS.")) {
                    offenders.add(file + ": radius default 18 -> LIMITS.roomRadiusDefault: " + code);
                }
                if (code.contains("findSafeLanding") && code.contains("radius: "
                        + LandingScanner.DEFAULT_RADIUS) && !code.contains("LIMITS.")) {
                    offenders.add(file + ": landing radius 12 -> LIMITS.landingRadiusDefault: " + code);
                }
            }
        }
        assertTrue("use the shared defaults:\n" + String.join("\n", offenders), offenders.isEmpty());
    }

    @Test
    public void javaOwnedSkillDefaultsUseTheSharedTable() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : files()) {
            for (String line : read(file).split("\n")) {
                String code = line.trim();
                if (code.startsWith("//")) continue;
                if (code.contains("cfg.count || " + InventoryLimits.DEFAULT_TRANSFER_COUNT)
                        && !code.contains("LIMITS.defaultTransferCount")) {
                    offenders.add(file + ": count default -> LIMITS.defaultTransferCount: " + code);
                }
                if (code.contains("cfg.max_blocks || " + NavigationLimits.DEFAULT_DIG_BLOCKS)
                        && !code.contains("LIMITS.defaultDigBlocks")) {
                    offenders.add(file + ": dig default -> LIMITS.defaultDigBlocks: " + code);
                }
            }
        }
        assertTrue("use the shared Java defaults:\n" + String.join("\n", offenders),
                offenders.isEmpty());
    }

    @Test
    public void dropCollectionCapUsesTheSharedLimit() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : files()) {
            for (String line : read(file).split("\n")) {
                if (line.contains("Math.min(16,") && !line.contains("LIMITS.collectRadiusMax")) {
                    offenders.add(file + ": " + line.trim());
                }
            }
        }
        assertTrue("use LIMITS.collectRadiusMax:\n" + String.join("\n", offenders),
                offenders.isEmpty());
    }

    @Test
    public void landingCeilingUsesTheSharedBound() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : files()) {
            for (String line : read(file).split("\n")) {
                if (!line.contains("Math.min(")) continue;
                if (!line.contains(String.valueOf(WorldBounds.MAX_LANDING_Y))) continue;
                if (!line.contains("LIMITS.landingY")) offenders.add(file + ": " + line.trim());
            }
        }
        assertTrue("use LIMITS.landingY:\n" + String.join("\n", offenders), offenders.isEmpty());
    }
}
