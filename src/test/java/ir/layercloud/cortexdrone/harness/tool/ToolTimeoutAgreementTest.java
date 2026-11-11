package ir.layercloud.cortexdrone.harness.tool;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * Repeated tool wrapper timeouts have one owner.
 */
public class ToolTimeoutAgreementTest {
    private static final String ROOT = "src/main/java/ir/layercloud/cortexdrone/harness/tools/";

    private static String read(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get(ROOT + name)), StandardCharsets.UTF_8);
    }

    @Test
    public void blockAndInventoryActionsShareTheTimeout() throws Exception {
        assertTrue(read("BlockTools.java").contains("ToolTimeouts.BLOCK_ACTION_MS"));
        assertTrue(read("InventoryActionTools.java").contains("ToolTimeouts.BLOCK_ACTION_MS"));
    }

    @Test
    public void noInlineTimeoutLiteralsRemain() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Paths.get(ROOT))) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                try {
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        if (line.matches(".*\\.timeoutMs\\([0-9].*")) {
                            offenders.add(file.getFileName() + ": " + line.trim());
                        }
                    }
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
        }
        assertTrue("use ToolTimeouts instead of an inline timeout:\n"
                + String.join("\n", offenders), offenders.isEmpty());
    }

    @Test
    public void invokerDefaultsUseTheOwner() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/ir/layercloud/cortexdrone/harness/tool/ToolInvoker.java")),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("ToolTimeouts.DEFAULT_TOOL_MS"));
        assertTrue(source.contains("ToolTimeouts.LONG_MS"));
    }

    @Test
    public void navigationAndCodeTimeoutsHaveOwners() throws Exception {
        assertTrue(read("NavigationTools.java").contains("ToolTimeouts.NAVIGATION_MS"));
        assertTrue(read("CodeTools.java").contains("ToolTimeouts.CODE_EXECUTE_MS"));
    }
}
