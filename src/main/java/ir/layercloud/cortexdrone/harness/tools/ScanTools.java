package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.harness.tool.ScanJobs;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;

import java.util.Collections;
import java.util.List;

/**
 * Transport tool for time-sliced scans. Large {@code scan_blocks} calls return
 * {@code pending:true} + job_id; poll here until {@code done:true}. The
 * JavaScript helper {@code drone.scanBlocks} polls automatically.
 */
public final class ScanTools implements ToolProvider {
    private static final List<ToolSpec> SPECS = Collections.singletonList(
            ToolSpec.builder("poll_scan")
                    .description("Poll a time-sliced block scan started by scan_blocks. Omit job_id to poll "
                            + "the active job. Returns pending:true until the scan finishes; when done it "
                            + "contains the sorted blocks, count, scanned_voxels and truncated flag.")
                    .parameters(ToolSchema.object()
                            .optionalString("job_id", "Job id returned by scan_blocks; omit for the active job."))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(3_000L)
                    .build()
    );

    private final ScanJobs scans;

    public ScanTools(ScanJobs scans) {
        this.scans = scans;
    }

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    @Override
    public void register(ToolRegistry registry) {
        registry.bind("poll_scan", getClass().getSimpleName(), (context, arguments) ->
                this.scans.poll(arguments.has("job_id") ? arguments.get("job_id").getAsString() : null));
    }
}
