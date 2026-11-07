package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.harness.tools.view.BreadcrumbView;
import ir.layercloud.cortexdrone.harness.tools.view.NavigationView;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.Arrays;
import java.util.List;

/**
 * Goal-driven navigation: fly to a point or to a safe standoff beside a block.
 *
 * <p>The manager plans and recovers on its own: clear line, trail, A* flight,
 * then optional automatic tunnelling and detours. The model only asks for the
 * destination.</p>
 */
public final class NavigationTools implements ToolProvider {
    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("goto")
                    .description("Fly to a coordinate and resolve only on arrival or proven failure. "
                            + "Pass x/y/z for a point, or standoff_of:{x,y,z} to request a safe reachable "
                            + "position beside a block (useful before break/place/interact). Navigation is "
                            + "automatic: it tries a direct line, the recorded trail, A* flight, then "
                            + "automatic tunnelling (when enabled) and detours. It only fails after all "
                            + "recovery attempts are exhausted, so do not retry or dig manually on a single "
                            + "navigation_error.")
                    .parameters(ToolSchema.object()
                            .optionalNumber("x", "Target X (omit when using standoff_of).", null)
                            .optionalNumber("y", "Target Y.", null)
                            .optionalNumber("z", "Target Z.", null)
                            .optionalObject("standoff_of", "Block to stand beside: {x,y,z}. Resolves to a "
                                    + "reachable position within interaction range.")
                            .optionalInt("max_wait_ms", "Upper bound for arrival, 5000..180000.", 5000, 180_000, 120_000)
                            .optionalBoolean("allow_dig", "Compatibility field; navigation never digs. "
                                    + "Dig with break_block and retry.", false))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(180_000L)
                    .build(),
            ToolSpec.builder("stop")
                    .description("Cancel the active navigation session and hover in place. Does not cancel "
                            + "a running task or code execution.")
                    .parameters(ToolSchema.object())
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(5_000L)
                    .build(),
            ToolSpec.builder("get_breadcrumbs")
                    .description("Read the recorded recent trail (passable positions) and the last surface "
                            + "anchor. Useful to understand how the drone entered a cave before planning "
                            + "a backtrack or escape.")
                    .parameters(ToolSchema.object()
                            .optionalInt("limit", "Number of most recent trail points, 1..256.", 1, 256, 64))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(3_000L)
                    .build()
    );

    private final NavigationView navigation;
    private final BreadcrumbView breadcrumbs;

    public NavigationTools(AIDroneManager manager) {
        this.navigation = new NavigationView(manager.getNavigationManager());
        this.breadcrumbs = new BreadcrumbView(manager.getBreadcrumbService());
    }

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    @Override
    public void register(ToolRegistry registry) {
        registry.bind("goto", getClass().getSimpleName(),
                (context, arguments) -> this.navigation.handle(context.controller(), context.player(), "goto", arguments));
        registry.bind("stop", getClass().getSimpleName(),
                (context, arguments) -> this.navigation.handle(context.controller(), context.player(), "stop", arguments));
        registry.bind("get_breadcrumbs", getClass().getSimpleName(),
                (context, arguments) -> this.breadcrumbs.read(context.controller(), arguments));
    }
}
