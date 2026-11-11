package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.tool.*;
import ir.layercloud.cortexdrone.service.world.BuildSiteFinder;
import ir.layercloud.cortexdrone.service.world.SiteSurvey;
import ir.layercloud.cortexdrone.service.world.StructureArchitect;
import ir.layercloud.cortexdrone.service.world.StructurePlan;
import li.cil.oc.common.entity.Drone;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.List;

/**
 * Documentation lookup and build planning: site search, site survey and
 * blueprint generation.
 *
 * <p>Every tool here is read-only. The planner emits data; the model (or a
 * skill) performs the placements.</p>
 */
public final class WorldBuildPlanTools implements ToolProvider {
    /**
     * Smallest footprint edge for a survey or a planned structure.
     */
    public static final int MIN_PLAN_SIDE = ir.layercloud.cortexdrone.service.world.WorldBounds.MIN_PLAN_SIDE;
    /**
     * Largest footprint edge for a survey or a planned structure.
     */
    public static final int MAX_SITE_SIDE = ir.layercloud.cortexdrone.service.world.WorldBounds.MAX_SITE_SIDE;
    /**
     * Largest footprint the blueprint planner will generate.
     */
    public static final int MAX_PLAN_SIDE = ir.layercloud.cortexdrone.service.world.WorldBounds.MAX_PLAN_SIDE;
    /**
     * Shortest wall the blueprint planner will generate.
     */
    public static final int MIN_PLAN_HEIGHT = ir.layercloud.cortexdrone.service.world.WorldBounds.MIN_PLAN_HEIGHT;
    /**
     * Tallest wall the blueprint planner will generate.
     */
    public static final int MAX_PLAN_HEIGHT = ir.layercloud.cortexdrone.service.world.WorldBounds.MAX_PLAN_HEIGHT;
    /**
     * Smallest headroom a site survey will check.
     */
    public static final int MIN_SURVEY_HEIGHT = ir.layercloud.cortexdrone.service.world.WorldBounds.MIN_SURVEY_HEIGHT;
    /**
     * Largest headroom a site survey will check.
     */
    public static final int MAX_SURVEY_HEIGHT = ir.layercloud.cortexdrone.service.world.WorldBounds.MAX_SURVEY_HEIGHT;

    private final AIDroneManager manager;
    private final StructureArchitect structureArchitect = new StructureArchitect();

    public WorldBuildPlanTools(AIDroneManager manager) {
        this.manager = manager;
    }

    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("doc_lookup")
                    .description("Look up a live item/block/recipe in the loaded Forge registries, ore "
                            + "dictionary, vanilla crafting and local machine recipe configs. Use this "
                            + "before guessing an item name or recipe. mod_id narrows to one mod prefix.")
                    .parameters(ToolSchema.object()
                            .requiredString("query", "Item/block/machine/recipe name or phrase.")
                            .optionalString("mod_id", "Restrict to a mod id, e.g. 'hbm', 'minecraft'.")
                            .optionalBoolean("include_web", "Also query the public wiki when local "
                                    + "registries have no match.", false))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.SCAN_MS).build(),
            ToolSpec.builder("find_build_site")
                    .description("Find a flat, clear build pad centred on the DRONE and return its corners, "
                            + "floor Y and door side. anchor=player biases the preferred spot toward where "
                            + "the player is looking. Feed the result into survey_build_site and "
                            + "plan_structure.")
                    .parameters(ToolSchema.object()
                            .optionalString("anchor", "drone (default) or player.", "drone")
                            .optionalInt("width", "Desired width, " + MIN_PLAN_SIDE + ".." + MAX_PLAN_SIDE + ".", MIN_PLAN_SIDE, MAX_PLAN_SIDE, 5)
                            .optionalInt("depth", "Desired depth, " + MIN_PLAN_SIDE + ".." + MAX_PLAN_SIDE + ".", MIN_PLAN_SIDE, MAX_PLAN_SIDE, 5)
                            .optionalInt("max_radius", "Search radius, " + BuildSiteFinder.SCHEMA_MIN_RADIUS + ".." + BuildSiteFinder.SCHEMA_MAX_RADIUS + ".",
                                    BuildSiteFinder.SCHEMA_MIN_RADIUS,
                                    BuildSiteFinder.SCHEMA_MAX_RADIUS,
                                    BuildSiteFinder.DEFAULT_RADIUS))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.SCAN_MS).build(),
            ToolSpec.builder("survey_build_site")
                    .description("Read-only build-site survey: per-column ground height, slope, headroom, "
                            + "obstacles, liquids, trees and protected fixtures (chests, machines, "
                            + "OpenComputers blocks), plus the exact materials that would need clearing. "
                            + "Always call this before planning or building, and never build on a site with "
                            + "protected columns or liquid columns. Returns columns (sample), warnings and "
                            + "groundYMin/Max/Avg plus suggestedFloorY.")
                    .parameters(ToolSchema.object()
                            .optionalInt("x1", "Site min X (with z1/x2/z2).")
                            .optionalInt("z1", "Site min Z.")
                            .optionalInt("x2", "Site max X.")
                            .optionalInt("z2", "Site max Z.")
                            .optionalInt("x", "Center X instead of a box.")
                            .optionalInt("z", "Center Z instead of a box.")
                            .optionalInt("width", "Width when using a center, " + MIN_PLAN_SIDE + ".." + MAX_SITE_SIDE + ".", MIN_PLAN_SIDE, MAX_SITE_SIDE, 5)
                            .optionalInt("depth", "Depth when using a center, " + MIN_PLAN_SIDE + ".." + MAX_SITE_SIDE + ".", MIN_PLAN_SIDE, MAX_SITE_SIDE, 5)
                            .optionalInt("height", "Headroom to check above the ground, " + MIN_SURVEY_HEIGHT + ".." + MAX_SURVEY_HEIGHT + ".",
                                    MIN_SURVEY_HEIGHT, MAX_SURVEY_HEIGHT, 4)
                            .optionalInt("y", "Reference floor Y; defaults to the player's Y."))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.SURVEY_MS).build(),
            ToolSpec.builder("plan_structure")
                    .description("Generate a detailed structure blueprint: ordered build tasks with an "
                            + "explicit phase (foundation, floor, corner, wall, window, railing, roof), "
                            + "the blocks that must be cleared, door coordinates and exact material counts. "
                            + "styles: house, hut, wall, tower, platform, bridge. roofs: flat, gable, hip, "
                            + "pyramid. Either pass x/z (center) or x1/z1/x2/z2 (surveyed site box); y is the "
                            + "floor level. This is a plan, not an action: placing is done by the "
                            + "build_structure / place_build_plan skills.")
                    .parameters(ToolSchema.object()
                            .optionalString("style", "house, hut, wall, tower, platform, bridge.", "house")
                            .optionalInt("width", "Width, " + MIN_PLAN_SIDE + ".." + MAX_PLAN_SIDE + ".", MIN_PLAN_SIDE, MAX_PLAN_SIDE, 5)
                            .optionalInt("depth", "Depth, " + MIN_PLAN_SIDE + ".." + MAX_PLAN_SIDE + ".", MIN_PLAN_SIDE, MAX_PLAN_SIDE, 5)
                            .optionalInt("height", "Wall height, " + MIN_PLAN_HEIGHT + ".." + MAX_PLAN_HEIGHT + ".", MIN_PLAN_HEIGHT, MAX_PLAN_HEIGHT, 3)
                            .optionalEnum("roof_style", "Roof shape.", "flat",
                                    "flat", "gable", "hip", "pyramid")
                            .optionalBoolean("windows", "Include window openings.", true)
                            .optionalString("floor", "Floor block.", "cobblestone")
                            .optionalString("walls", "Wall block.", "cobblestone")
                            .optionalString("corners", "Corner block.", "cobblestone")
                            .optionalString("roof", "Roof block.", "cobblestone")
                            .optionalString("foundation", "Foundation block.", "cobblestone")
                            .optionalString("window_block", "Window block.", "glass")
                            .optionalInt("x", "Center X.")
                            .optionalInt("z", "Center Z.")
                            .optionalInt("y", "Floor Y.")
                            .optionalInt("x1", "Site min X.")
                            .optionalInt("z1", "Site min Z.")
                            .optionalInt("x2", "Site max X.")
                            .optionalInt("z2", "Site max Z.")
                            .optionalString("door_side", "north, south, east, west.", "south"))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.SURVEY_MS).build()
    );

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    @Override
    public void register(ToolRegistry registry) {
        for (ToolSpec spec : SPECS) {
            final String name = spec.getName();
            registry.bind(name, getClass().getSimpleName(),
                    (context, arguments) -> handle(context.controller(), context.player(), name, arguments));
        }
    }

    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String tool, JsonObject args) {
        Drone drone = ctrl != null ? ctrl.drone : null;
        if (drone == null || drone.worldObj == null) {
            return ToolJson.error("no_drone", "This tool needs a linked drone.", "Spawn or link a drone first.", false);
        }
        World world = drone.worldObj;
        switch (tool) {
            case "doc_lookup":
                return docLookup(args);
            case "find_build_site":
                return findBuildSite(world, ctrl, player, args);
            case "survey_build_site":
                return survey(world, drone, player, args);
            case "plan_structure":
                return plan(world, ctrl, player, args);
            default:
                return ToolJson.error("unknown_tool", "Unhandled planning tool: " + tool, null, false);
        }
    }

    private JsonObject docLookup(JsonObject args) {
        String query = args.has("query") ? args.get("query").getAsString()
                : (args.has("item") ? args.get("item").getAsString() : "");
        String modId = args.has("modId") && !args.get("modId").isJsonNull()
                ? args.get("modId").getAsString() : null;
        boolean includeWeb = args.has("includeWeb") && args.get("includeWeb").getAsBoolean();
        return this.manager.getDocLookupService().lookup(query, modId, includeWeb);
    }

    private JsonObject findBuildSite(World world, DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        int width = args.has("width") ? args.get("width").getAsInt() : 5;
        int depth = args.has("depth") ? args.get("depth").getAsInt() : width;
        int maxRadius = args.has("maxRadius") ? args.get("maxRadius").getAsInt()
                : BuildSiteFinder.DEFAULT_RADIUS;
        String anchor = args.has("anchor") ? args.get("anchor").getAsString() : "drone";
        return this.structureArchitect.findBestBuildSite(world, player, ctrl, width, depth, maxRadius, anchor);
    }

    private JsonObject survey(World world, Drone drone, EntityPlayerMP player, JsonObject args) {
        int height = args.has("height")
                ? Math.max(MIN_SURVEY_HEIGHT, Math.min(MAX_SURVEY_HEIGHT, args.get("height").getAsInt())) : 4;
        Integer explicitY = args.has("y") ? args.get("y").getAsInt() : null;
        if (args.has("x1") && args.has("z1") && args.has("x2") && args.has("z2")) {
            return SiteSurvey.survey(world, player,
                    args.get("x1").getAsInt(), args.get("z1").getAsInt(),
                    args.get("x2").getAsInt(), args.get("z2").getAsInt(), explicitY, height,
                    MathHelper.floor_double(drone.posY));
        }
        int width = args.has("width")
                ? Math.max(MIN_PLAN_SIDE, Math.min(MAX_SITE_SIDE, args.get("width").getAsInt())) : 5;
        int depth = args.has("depth")
                ? Math.max(MIN_PLAN_SIDE, Math.min(MAX_SITE_SIDE, args.get("depth").getAsInt())) : width;
        int[] center = WorldToolSupport.scanCenter(drone, player, args);
        int centerX = center[0];
        int centerZ = center[2];
        return SiteSurvey.survey(world, player,
                centerX - width / 2, centerZ - depth / 2,
                centerX - width / 2 + width - 1, centerZ - depth / 2 + depth - 1,
                explicitY, height, center[1]);
    }

    private JsonObject plan(World world, DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        StructurePlan plan =
                this.structureArchitect.generateStructureBlueprint(world, player, ctrl, args);
        return plan.toJson();
    }
}
