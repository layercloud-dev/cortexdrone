package ir.layercloud.cortexdrone.service.navigation;

import ir.layercloud.cortexdrone.service.world.WorldBounds;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Block-centre and world-height arithmetic has one source each.
 *
 * <p>Regression risk: {@code CELL_CENTER}, {@code AIM_OFFSET} and the world
 * height envelope were centralised in {@link DroneGeometry} and
 * {@code WorldBounds}, but call sites kept writing the raw literals, so the
 * constants could drift from the code that was supposed to use them.</p>
 *
 * <p>These are structural guards. They deliberately have <em>no</em> per-file
 * exemptions: an exemption is how the previous version of this guard missed the
 * very sites it was written to protect.</p>
 */
public class GeometryLiteralConsistencyTest {
    private static final Path MAIN = Paths.get("src/main/java/ir/layercloud/cortexdrone");

    /**
     * The exact declarations that are allowed to contain the raw literal.
     *
     * <p>An exemption is scoped to a line, not a file. A whole-file exemption is
     * how the previous version of this guard missed the very sites it existed to
     * protect.</p>
     */
    private static final String[] DEFINITION_LINES = {
            "public static final double CELL_CENTER = 0.5D;",
            "public static final double AIM_OFFSET = 0.35D;",
            "public static final double HOVER_OFFSET = 0.65D;",
            "public static final int MAX_FLIGHT_Y = MAX_Y - 1;",
            "public static final int MAX_Y = 254;",
            "public static final int MAX_LANDING_Y = 250;"
    };

    private static List<String> javaFiles() throws Exception {
        List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toString()));
        }
        return files;
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /**
     * Lines of code, without comments.
     */
    private static List<String> codeLines(String file) throws Exception {
        List<String> out = new ArrayList<>();
        for (String line : read(file).split("\n")) {
            String code = line.trim();
            if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) continue;
            out.add(code);
        }
        return out;
    }

    /**
     * True when this line is one of the constant declarations itself.
     */
    private static boolean isDefinitionLine(String code) {
        for (String declaration : DEFINITION_LINES) {
            if (declaration.equals(code)) return true;
        }
        return false;
    }

    /**
     * A raw half-block added to something that is a block coordinate or a
     * collection index. Entity/player positions and facing rounding are not block
     * coordinates and are allowed to keep their own offsets.
     */
    private static final java.util.regex.Pattern RAW_HALF_BLOCK = java.util.regex.Pattern.compile(
            ".*(xCoord|yCoord|zCoord|targetBlock\\[|standoff\\[|open\\[|candidate\\[|"
                    + "\\bptr\\.x|\\bptr\\.y|\\bptr\\.z|\\bcur\\.x|\\bcur\\.y|\\bcur\\.z|"
                    + "\\ba\\.xCoord|\\ba\\.yCoord|\\ba\\.zCoord|\\bb\\.xCoord|\\bb\\.yCoord|\\bb\\.zCoord|"
                    + "openAbove\\[|openAbove\\.|\\bte\\.xCoord|\\bte\\.yCoord|\\bte\\.zCoord|"
                    + "\\bgoalX|\\bgoalZ|\\bcurBx|\\bcurBz|\\bgetStandoff[XYZ]|\\bgetX\\(\\)|\\bgetZ\\(\\)|"
                    // A bare int local inside a coordinate tuple: new double[]{x + 0.5D, ...}
                    + "new double\\[\\]\\{\\s*[a-z][A-Za-z0-9_]* \\+)"
                    + ".*[+] 0[.]5(D)?\\b.*");

    @Test
    public void cellCentreIsUsedInsteadOfTheRawHalfBlock() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String file : javaFiles()) {
            for (String code : codeLines(file)) {
                if (isDefinitionLine(code)) continue;
                if (RAW_HALF_BLOCK.matcher(code).matches()) offenders.add(file + ": " + code);
            }
        }
        assertTrue("use DroneGeometry.CELL_CENTER instead of + 0.5:\n"
                + String.join("\n", offenders), offenders.isEmpty());
    }

    @Test
    public void standoffAimOffsetIsUsedInsteadOfTheRawLiteral() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String file : javaFiles()) {
            for (String code : codeLines(file)) {
                if (isDefinitionLine(code)) continue;
                if (code.contains("AIM_OFFSET") || code.contains("NO_GROUND_PENALTY")
                        || code.contains("MAX_SUBSTEP")) {
                    continue;
                }
                if (code.matches(".*[+] 0[.]35D?\\b.*")) offenders.add(file + ": " + code);
            }
        }
        assertTrue("use DroneGeometry.AIM_OFFSET instead of + 0.35:\n"
                + String.join("\n", offenders), offenders.isEmpty());
    }

    @Test
    public void theFlightEnvelopeIsUsedInsteadOfRawHeightLiterals() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String file : javaFiles()) {
            for (String code : codeLines(file)) {
                if (isDefinitionLine(code)) continue;
                // A hard ceiling at or near the build limit, written out.
                // Integer and decimal forms both count.
                if (code.matches(".*Math[.]min\\(\\s*(250|253|254)\\b.*")
                        || code.matches(".*Math[.]max\\(\\s*[23]\\b.*Math[.]min\\(\\s*(253|254)\\b.*")) {
                    offenders.add(file + ": " + code);
                }
            }
        }
        assertTrue("use WorldBounds instead of a raw build-limit clamp:\n"
                + String.join("\n", offenders), offenders.isEmpty());
    }

    @Test
    public void theDeclaredConstantsExist() {
        assertEquals(0.5D, DroneGeometry.CELL_CENTER, 0.0);
        assertEquals(0.35D, DroneGeometry.AIM_OFFSET, 0.0);
        assertEquals(0.65D, DroneGeometry.HOVER_OFFSET, 0.0);
        assertTrue(DroneGeometry.AIM_OFFSET < DroneGeometry.HOVER_OFFSET);
        assertTrue(WorldBounds.MIN_FLIGHT_Y < WorldBounds.MAX_FLIGHT_Y);
        assertEquals(WorldBounds.MAX_FLIGHT_Y, (int) WorldBounds.clampFlightY(9999));
        assertEquals(WorldBounds.MIN_FLIGHT_Y, (int) WorldBounds.clampFlightY(-9999));
    }
}
