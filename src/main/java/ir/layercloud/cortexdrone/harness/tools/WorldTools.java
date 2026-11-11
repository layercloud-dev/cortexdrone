package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.harness.tool.ScanShape;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.service.world.WorldScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * World query surface, split into one provider per concern.
 *
 * <p>This class stays as the single entry point the tool catalog knows about,
 * but it owns no query logic: block reads, scans, entity listings and build
 * planning each live in their own provider. Keeping the split visible here
 * means a new world tool has an obvious home instead of growing one dispatcher.</p>
 */
public final class WorldTools implements ToolProvider {
    private final WorldBlockReadTools blockReads;
    private final WorldScanTools scans;
    private final WorldEntityTools entities;
    private final WorldBuildPlanTools buildPlans;

    public WorldTools(AIDroneManager manager, WorldScanner worldScanner) {
        this.blockReads = new WorldBlockReadTools();
        this.scans = new WorldScanTools(worldScanner);
        this.entities = new WorldEntityTools();
        this.buildPlans = new WorldBuildPlanTools(manager);
    }

    /**
     * Every spec declared by the world providers.
     */
    public static List<ToolSpec> specs() {
        List<ToolSpec> specs = new ArrayList<>();
        specs.addAll(WorldBlockReadTools.specs());
        specs.addAll(WorldScanTools.specs());
        specs.addAll(WorldEntityTools.specs());
        specs.addAll(WorldBuildPlanTools.specs());
        return specs;
    }

    @Override
    public void register(ToolRegistry registry) {
        this.blockReads.register(registry);
        this.scans.register(registry);
        this.entities.register(registry);
        this.buildPlans.register(registry);
    }

    /**
     * Shape gate for {@code scan_blocks}; kept for callers and tests.
     */
    static boolean withinShape(String shape, int dx, int dy, int dz, int radius) {
        return ScanShape.parse(shape).contains(dx, dy, dz, radius);
    }
}
