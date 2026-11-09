package ir.layercloud.cortexdrone.harness.skill;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * Repeated task-local skill caps live in one table.
 *
 * <p>Regression: farming/utility skills repeated the same 96/128 scan caps,
 * 400/800 request limits and the 2..16 light radius in their own bodies.</p>
 */
public class SkillTuningAgreementTest {
    private static final Path SKILLS = Paths.get("src/main/resources/skills");

    private static List<Path> files() throws Exception {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(SKILLS)) {
            walk.filter(p -> p.toString().endsWith("code.js")).forEach(files::add);
        }
        return files;
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    public void repeatedSkillCapsUseTheSharedTable() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : files()) {
            for (String line : read(file).split("\n")) {
                String code = line.trim();
                if (code.startsWith("//")) continue;
                if (code.contains("max_clusters: 10")
                        || code.contains("cfg.duration_s || 120, 10, 240")
                        || code.contains("cfg.duration_s || 120, 15, 240")
                        || code.contains("cfg.duration_s || 120, 5, 240")
                        || code.contains("-30000000")
                        || code.contains("clamp(cfg.max_kills || 4, 1, 32)")
                        || code.contains("clamp(cfg.max_blocks || 256, 1, 512)")
                        || code.contains("clamp(cfg.max_blocks || 256, 1, 1600)")
                        || code.contains("wanted / 256")
                        || code.contains("Math.min(256,")
                        || code.contains("clamp(plan.buildCount, 8, 128)")
                        || code.contains("clamp(cfg.limit || 64, 1, 192)")
                        || code.contains("cfg.guard_radius || 12")
                        || code.contains(", 1, 96)")
                        || code.contains(", 1, 128)")
                        || code.contains("limit: 800")
                        || code.contains("limit: 400")
                        || code.contains("clamp(cfg.radius || 8, 2, 16)")) {
                    offenders.add(file + ": " + code);
                }
            }
        }
        assertTrue("use SKILL_LIMITS instead of a repeated cap:\n"
                + String.join("\n", offenders), offenders.isEmpty());
    }

    @Test
    public void everyNumericClampBoundUsesTheTables() throws Exception {
        java.util.regex.Pattern numeric = java.util.regex.Pattern.compile(
                "clamp\\([^,]+,\\s*\\d+\\s*,\\s*\\d+\\s*\\)");
        List<String> offenders = new ArrayList<>();
        for (Path file : files()) {
            for (String line : read(file).split("\n")) {
                String code = line.trim();
                if (code.startsWith("//")) continue;
                if (numeric.matcher(code).find()) offenders.add(file + ": " + code);
            }
        }
        assertTrue("use LIMITS/SKILL_LIMITS for clamp bounds:\n"
                + String.join("\n", offenders), offenders.isEmpty());
    }

    @Test
    public void theSkillsUseTheTable() throws Exception {
        int users = 0;
        for (Path file : files()) {
            if (read(file).contains("SKILL_LIMITS.")) users++;
        }
        assertTrue("the tuned skills must read SKILL_LIMITS", users > 0);
    }
}
