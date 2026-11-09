package ir.layercloud.cortexdrone.harness;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * The last localized literals must read an owner.
 *
 * <p>Covers the code-execute bounds, scan-job floors, schema prose ranges,
 * the LLM read timeout and the journal config path.</p>
 */
public class RemainingHardcodeGuardTest {
    private static final String ROOT = "src/main/java/ir/layercloud/cortexdrone/";

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(ROOT + path)), StandardCharsets.UTF_8);
    }

    @Test
    public void codeAndScanOwnersAreRead() throws Exception {
        assertTrue(read("harness/tools/CodeTools.java").contains("CodeLimits.MIN_TIMEOUT_MS"));
        assertTrue(read("harness/tools/CodeTools.java").contains("CodeLimits.MAX_TIMEOUT_MS"));
        assertTrue(read("harness/tools/CodeTools.java").contains("CodeLimits.DEFAULT_TIMEOUT_MS"));
        assertTrue(read("harness/tool/ScanJobs.java").contains("ScanLimits.MIN_RADIUS"));
        assertTrue(read("harness/tool/ScanJobs.java").contains("ScanLimits.MIN_RESULTS"));
    }

    @Test
    public void schemaProseIsBuiltFromConstants() throws Exception {
        assertTrue(read("harness/tools/SkillTools.java").contains("MAX_SEARCH_LIMIT"));
        assertTrue(read("harness/tools/VisualsTools.java").contains("VisualLimits.MAX_PARTICLES"));
        assertTrue(read("harness/tools/WebTools.java").contains("MAX_RESULTS"));
        assertTrue(read("harness/tools/MemoryTools.java").contains("MAX_RESULTS"));
        assertTrue(read("harness/tools/OcTools.java").contains("MAX_LIMIT"));
        assertTrue(read("harness/tools/LessonTools.java").contains("MAX_RESULTS"));
    }

    @Test
    public void transferDefaultsAndLastProseRangesUseOwners() throws Exception {
        assertTrue(read("action/inventory/impl/MoveSlotAction.java")
                .contains("InventoryLimits.DEFAULT_TRANSFER_COUNT"));
        assertTrue(read("action/inventory/impl/WithdrawChestAction.java")
                .contains("InventoryLimits.DEFAULT_TRANSFER_COUNT"));
        assertTrue(read("harness/tools/NavigationTools.java").contains("MAX_BREADCRUMBS"));
        assertTrue(read("harness/tools/OreDictTools.java").contains("MIN_LIMIT"));
        assertTrue(read("harness/tools/WorldBlockReadTools.java").contains("DEFAULT_RAY_DISTANCE"));
        assertTrue(read("harness/tools/VisualsTools.java").contains("VisualLimits.MAX_COLOR"));
    }

    @Test
    public void sharedSearchAndQueryBoundsUseOwners() throws Exception {
        assertTrue(read("harness/memory/MemoryStore.java").contains("RankedSearch."));
        assertTrue(read("harness/lesson/LessonStore.java").contains("RankedSearch."));
        assertTrue(read("harness/tools/WebTools.java").contains("MAX_FETCH_CHARS"));
        assertTrue(read("harness/tools/CombatTools.java").contains("MAX_SLOT"));
        assertTrue(read("harness/tools/InteractionTools.java").contains("MAX_SIDE"));
        assertTrue(read("service/world/SiteSurvey.java").contains("MAX_SURVEY_COLUMNS"));
        assertTrue(read("harness/js/JsEngine.java").contains("ToolTimeouts.ACTION_MS"));
        assertTrue(read("harness/tool/ToolSpec.java").contains("DEFAULT_TOOL_MS"));
        assertTrue(read("harness/session/SessionIndex.java").contains("MAX_HISTORY"));
        assertTrue(read("service/world/WebLookupService.java").contains("MAX_FETCH_CHARS"));
        assertTrue(read("harness/tools/view/VisualsView.java").contains("VisualLimits.MAX_COLOR"));
        assertTrue(read("action/visuals/impl/SetLedAction.java").contains("VisualLimits.MAX_COLOR"));
    }

    @Test
    public void deadMirrorAndDocAreFixed() throws Exception {
        String bootstrap = new String(Files.readAllBytes(
                Paths.get("src/main/resources/js/bootstrap.js")), StandardCharsets.UTF_8);
        assertFalse("LIMITS.transferCount is a dead mirror", bootstrap.contains("transferCount:"));
        String architecture = new String(Files.readAllBytes(
                Paths.get("ARCHITECTURE.md")), StandardCharsets.UTF_8);
        assertTrue("the inventory owner must be the service class",
                architecture.contains("service/inventory/InventoryLimits"));
    }

    @Test
    public void webAndAgentDeadlinesUseOwners() throws Exception {
        assertTrue(read("service/world/DocLookupService.java").contains("WebLimits."));
        assertTrue(read("service/world/WebLookupService.java").contains("WebLimits."));
        assertFalse("the fetched-text cap must not be restated",
                read("service/world/WebLookupService.java").contains("> 20_000"));
        assertTrue(read("harness/tools/WebTools.java").contains("WebLimits.MIN_RESULTS"));
        assertTrue(read("harness/tools/WebTools.java").contains("WebLimits.MAX_RESULTS"));
        assertTrue(read("service/world/WebLookupService.java").contains("WebLimits.MIN_RESULTS"));
        assertTrue(read("service/world/WebLookupService.java").contains("WebLimits.MAX_BYTES"));
        assertTrue(read("service/world/WebLookupService.java").contains("WebLimits.CACHE_SIZE"));
        assertFalse("the search floor must not be restated",
                read("service/world/WebLookupService.java").contains("Math.max(1, Math.min(WebLimits.MAX_RESULTS"));
        assertTrue(read("harness/js/JsEngine.java").contains("CodeLimits.MAX_INSTRUCTIONS"));
        assertTrue(read("service/world/DocLookupService.java").contains("WebLimits.MAX_RESULTS"));
        assertTrue(read("service/world/DocLookupService.java").contains("WebLimits.MAX_MATCHES_PER_FILE"));
        assertTrue(read("harness/tools/MemoryTools.java").contains("RankedSearch.MAX_RESULTS"));
        assertTrue(read("harness/tools/LessonTools.java").contains("RankedSearch.MAX_RESULTS"));
        assertTrue(read("harness/agent/LlmClient.java").contains("CONNECT_TIMEOUT_MS"));
        assertTrue(read("harness/agent/AgentService.java").contains("MIN_TASK_DEADLINE_SECONDS"));
    }

    @Test
    public void configAndLlmOwnersAreRead() throws Exception {
        assertTrue(read("harness/agent/LlmClient.java").contains("READ_TIMEOUT_MS"));
        assertTrue(read("harness/session/SessionJournal.java").contains("ConfigDirectory.resolve"));
    }
}
