package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.harness.tool.*;
import ir.layercloud.cortexdrone.harness.tools.view.BreadcrumbView;
import ir.layercloud.cortexdrone.harness.tools.view.LandingView;
import ir.layercloud.cortexdrone.harness.tools.view.NavigationView;
import ir.layercloud.cortexdrone.service.navigation.NavigationLimits;
import ir.layercloud.cortexdrone.service.navigation.impl.LandingScanner;

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
    private static final int MIN_BREADCRUMBS = 1;
    private static final int MAX_BREADCRUMBS = 256;
    private static final int DEFAULT_BREADCRUMBS = 64;
    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("goto")
                    .description("Fly to a coordinate and resolve only on arrival or proven failure. "
                            + "Pass x/y/z for a point, or standoff_of:{x,y,z} to request a safe reachable "
                            + "position beside a block (useful before break/place/interact). Navigation is "
                            + "automatic: it tries a direct line, the recorded trail, A* flight, then "
                            + "detours, and only then tunnelling (when allowed), including a vertical "
                            + "step for goals far above or below. It never digs through containers or "
                            + "machines. It only fails after all recovery attempts are exhausted, so do "
                            + "not retry or dig manually on a single navigation_error. Pass allow_dig:false "
                            + "when the player forbids damaging terrain, or tune max_dig_blocks / "
                            + "recovery_attempts per call. Every block it does break is journalled for "
                            + "repair_trail.")
                    .parameters(ToolSchema.object()
                            .optionalNumber("x", "Target X (omit when using standoff_of).", null)
                            .optionalNumber("y", "Target Y.", null)
                            .optionalNumber("z", "Target Z.", null)
                            .optionalObject("standoff_of", "Block to stand beside: {x,y,z}. Resolves to a "
                                    + "reachable position within interaction range.")
                            .optionalInt("max_wait_ms", "Upper bound for arrival, "
                                            + NavigationLimits.MIN_WAIT_MS + ".." + NavigationLimits.MAX_WAIT_MS + ".",
                                    NavigationLimits.MIN_WAIT_MS, NavigationLimits.MAX_WAIT_MS,
                                    NavigationLimits.DEFAULT_WAIT_MS)
                            .optionalBoolean("allow_dig", "Allow this call to tunnel through obstructing "
                                    + "blocks when no no-dig route exists. Default true; set false for "
                                    + "non-destructive travel.", true)
                            .optionalInt("max_dig_blocks", "Maximum blocks this call may clear, "
                                            + NavigationLimits.MIN_DIG_BLOCKS + ".." + NavigationLimits.MAX_DIG_BLOCKS + ".",
                                    NavigationLimits.MIN_DIG_BLOCKS, NavigationLimits.MAX_DIG_BLOCKS,
                                    NavigationLimits.DEFAULT_DIG_BLOCKS)
                            .optionalInt("recovery_attempts", "Maximum dig/detour attempts for this call, "
                                            + NavigationLimits.MIN_RECOVERY_ATTEMPTS + ".."
                                            + NavigationLimits.MAX_RECOVERY_ATTEMPTS + ".",
                                    NavigationLimits.MIN_RECOVERY_ATTEMPTS,
                                    NavigationLimits.MAX_RECOVERY_ATTEMPTS,
                                    NavigationLimits.DEFAULT_RECOVERY_ATTEMPTS)
                            .optionalNumber("speed", "Flight-speed multiplier for this call: 1 = normal, "
                                    + "the ceiling is 5. Default uses the configured value. A carried "
                                    + "passenger is always flown at normal speed.", null))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.NAVIGATION_MS)
                    .capabilities(ToolCapability.MUTATES_WORLD, ToolCapability.AWAITS_COMPLETION,
                            ToolCapability.AWAITS_NAVIGATION)
                    .build(),
            ToolSpec.builder("stop")
                    .description("Cancel the active navigation session and hover in place. Does not cancel "
                            + "a running task or code execution.")
                    .parameters(ToolSchema.object())
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.READ_MS)
                    .capabilities(ToolCapability.PRESENTATION)
                    .build(),
            ToolSpec.builder("get_breadcrumbs")
                    .description("Read the recorded recent trail (passable positions) and the last surface "
                            + "anchor. Useful to understand how the drone entered a cave before planning "
                            + "a backtrack or escape.")
                    .parameters(ToolSchema.object()
                            .optionalInt("limit", "Number of most recent trail points, "
                                            + MIN_BREADCRUMBS + ".." + MAX_BREADCRUMBS + ".",
                                    MIN_BREADCRUMBS, MAX_BREADCRUMBS, DEFAULT_BREADCRUMBS))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.FASTEST_MS)
                    .build(),
            ToolSpec.builder("find_safe_landing")
                    .description("Read-only landing check. Returns whether a passenger can be set down "
                            + "safely at/around a point: solid floor, a clear "
                            + LandingScanner.MAX_SAFE_DROP
                            + "-block pocket, no water or lava, no tree canopy, no falling block overhead, "
                            + "and at most a "
                            + LandingScanner.MAX_SAFE_DROP
                            + "-block drop. "
                            + "Use it before unpick_entity, and always after a goto that carries a player: "
                            + "never dismount over a mountain slope, a tree, a roof or a cave void. The "
                            + "result includes a nearby safe spot with exact hover coordinates when the "
                            + "requested point itself is unsafe.")
                    .parameters(ToolSchema.object()
                            .optionalInt("x", "Centre X; defaults to the drone.")
                            .optionalInt("y", "Reference Y; defaults to the drone's Y.")
                            .optionalInt("z", "Centre Z; defaults to the drone.")
                            .optionalInt("radius", "Horizontal search radius, "
                                            + LandingScanner.MIN_RADIUS
                                            + ".." + LandingScanner.MAX_RADIUS + ".",
                                    LandingScanner.MIN_RADIUS,
                                    LandingScanner.MAX_RADIUS,
                                    LandingScanner.DEFAULT_RADIUS)
                            .optionalBoolean("covered_ok", "Also accept a covered pocket such as a cave "
                                    + "floor. Default false: a carried passenger is only flown into open "
                                    + "sky, because entering a cave crushes them.", false))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.FAST_MS)
                    .build()
    );

    private final NavigationView navigation;
    private final BreadcrumbView breadcrumbs;
    private final LandingView landing;

    public NavigationTools(AIDroneManager manager) {
        this.navigation = new NavigationView(manager.getNavigationManager());
        this.breadcrumbs = new BreadcrumbView(manager.getBreadcrumbService());
        this.landing = new LandingView();
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
        registry.bind("find_safe_landing", getClass().getSimpleName(),
                (context, arguments) -> this.landing.handle(context.controller(), arguments));
    }
}
