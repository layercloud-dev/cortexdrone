package ir.layercloud.cortexdrone.harness.js;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.ModConfig;
import net.minecraft.entity.player.EntityPlayerMP;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import ir.layercloud.cortexdrone.harness.tool.ScanJobs;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolInvoker;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * A time-sliced scan that never finishes must be reported as a failure, not as
 * a successful empty scan.
 *
 * <p>Regression: the auto-poll loop returned the last pending envelope, which
 * carried {@code ok:true, count:0}. {@code mine_blocks} then reported "no ore
 * within Nm" while the scan was merely slower than the 45 s budget.</p>
 */
public class PendingScanTest {
    private static File tempRoot() throws Exception {
        File dir = Files.createTempDirectory("cortexdrone-pending").toFile();
        dir.deleteOnExit();
        return dir;
    }

    @Test
    public void anUnfinishedScanIsReportedAsAFailure() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.declare(ToolSpec.builder("scan_blocks")
                .description("Start a scan.")
                .parameters(ToolSchema.object().optionalInt("radius", "Radius.", 1, 64, 24))
                .affinity(ToolAffinity.ASYNC)
                .capability(ir.layercloud.cortexdrone.harness.tool.ToolCapability.TIME_SLICED)
                .build());
        registry.declare(ToolSpec.builder("poll_scan")
                .description("Poll a scan.")
                .parameters(ToolSchema.object().requiredString("job_id", "Job id."))
                .affinity(ToolAffinity.ASYNC)
                .build());

        assertTrue("the fixture must declare the time-sliced capability",
                registry.spec("scan_blocks").has(
                        ir.layercloud.cortexdrone.harness.tool.ToolCapability.TIME_SLICED));

        AtomicInteger polls = new AtomicInteger();
        registry.bind("scan_blocks", "test", (context, args) -> {
            JsonObject job = new JsonObject();
            job.addProperty("ok", true);
            job.addProperty("pending", true);
            job.addProperty("job_id", "scan-test");
            job.addProperty("count", 0);
            return job;
        });
        registry.bind("poll_scan", "test", (context, args) -> {
            polls.incrementAndGet();
            JsonObject pending = new JsonObject();
            pending.addProperty("ok", true);
            pending.addProperty("pending", true);
            pending.addProperty("done", false);
            pending.addProperty("job_id", "scan-test");
            pending.addProperty("count", 0);
            return pending;
        });

        ScanJobs pendingJobs = new ScanJobs(null) {
            @Override
            public JsonObject submit(DroneController controller, EntityPlayerMP player, JsonObject args) {
                JsonObject job = new JsonObject();
                job.addProperty("ok", true);
                job.addProperty("pending", true);
                job.addProperty("job_id", "scan-test");
                job.addProperty("count", 0);
                return job;
            }
        };
        JsEngine engine = new JsEngine(new ToolInvoker(null, registry, pendingJobs),
                registry, new ModConfig(), new SkillLibrary(tempRoot()));

        // The script must see a thrown ToolError, not a successful result.
        JsonObject result = engine.execute(null,
                "try { const r = await drone.scanBlocks({ radius: 24 }); "
                        + "return { ok: true, count: r.count }; } "
                        + "catch (e) { return { ok: false, code: e.name, message: e.message }; }",
                120_000L);

        JsonObject value = result.getAsJsonObject("result");
        assertNotNull("the script must return a value: " + result, value);
        assertFalse("a pending scan must not look successful: " + value,
                value.get("ok").getAsBoolean());
        assertEquals("ToolError", value.get("code").getAsString());
        assertTrue("the poll loop must have run", polls.get() > 0);
    }
}
