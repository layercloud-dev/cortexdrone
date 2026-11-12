package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.tool.*;
import ir.layercloud.cortexdrone.harness.tool.ScanLimits;
import ir.layercloud.cortexdrone.service.world.*;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.*;

/**
 * Area scans: room geometry, block filters, connected veins and distant
 * resource/biome search.
 *
 * <p>All scans are centred on the drone and bounded: a radius, a Y range and a
 * result limit. A scan that would need more work than one tick allows is
 * time-sliced by {@code ScanJobs} instead of running to completion here.</p>
 */
public final class WorldScanTools implements ToolProvider {
    /**
     * Clamp for a synchronous filter scan.
     */
    public static final int MAX_SCAN_RADIUS = ScanLimits.MAX_RADIUS;
    /**
     * Clamp for a connected-vein scan.
     */
    public static final int MAX_VEIN_RADIUS = ScanLimits.MAX_VEIN_RADIUS;
    /**
     * Clamp for a distant search, in blocks.
     */
    public static final int MAX_DISTANT_RADIUS = ScanLimits.MAX_DISTANT_RADIUS;
    /**
     * Result limit for a filter scan.
     */
    public static final int MAX_SCAN_RESULTS = ScanLimits.MAX_RESULTS;
    /**
     * Room scan radius bounds.
     */
    public static final int MAX_ROOM_RADIUS = RoomScanner.MAX_RADIUS;
    /**
     * @see ScanLimits#DEFAULT_RADIUS
     */
    private static final int DEFAULT_RADIUS =
            ScanLimits.DEFAULT_RADIUS;
    /**
     * @see ScanLimits#DEFAULT_RESULTS
     */
    private static final int DEFAULT_RESULTS = ScanLimits.DEFAULT_RESULTS;
    /**
     * Ore-dictionary tag cache key separator.
     */
    private static final String TAG_KEY_SEPARATOR = ":";

    private final WorldScanner worldScanner;

    public WorldScanTools(WorldScanner worldScanner) {
        this.worldScanner = worldScanner;
    }

    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("scan_room")
                    .description("Scan floor/walls/ceiling geometry around a point and report interior "
                            + "dimensions, materials and openings. Good before building inside a cave/base. "
                            + "Centred on the DRONE by default.")
                    .parameters(ToolSchema.object()
                            .optionalString("anchor", "drone (default) or player.", "drone")
                            .optionalInt("x", "Center X; defaults to the drone.")
                            .optionalInt("y", "Center Y; defaults to the drone.")
                            .optionalInt("z", "Center Z; defaults to the drone.")
                            .optionalInt("max_radius", "Maximum scan radius, " + ScanLimits.MIN_ROOM_RADIUS + ".." + MAX_ROOM_RADIUS + ".",
                                    ScanLimits.MIN_ROOM_RADIUS, MAX_ROOM_RADIUS,
                                    ScanLimits.DEFAULT_ROOM_RADIUS))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.FAST_MS).build(),
            ToolSpec.builder("scan_blocks")
                    .description("Find blocks matching a filter within a radius centred on the DRONE. "
                            + "filter accepts a block name, registry name, ore dictionary tag (bare 'oreTin' or "
                            + "'#oreTin'), or category ('ores', 'logs', 'stone', 'liquid', 'plants'...). "
                            + "A plain material word such as 'tin' resolves to its ore tags. Large radii (> " + ScanLimits.TIME_SLICE_RADIUS + ") are "
                            + "time-sliced: the result is pending:true with a job_id; poll with poll_scan "
                            + "until done:true. Inside execute, drone.scanBlocks(...) polls automatically.")
                    .parameters(ToolSchema.object()
                            .optionalString("filter", "Block name, registry name, ore tag (bare or #tag) or category.", "ores")
                            .optionalString("anchor", "drone (default) or player.", "drone")
                            .optionalInt("radius", "Search radius in blocks, " + ScanLimits.MIN_RADIUS + ".." + MAX_SCAN_RADIUS
                                    + " (capped at " + ScanLimits.TIME_SLICE_RADIUS
                                    + " for time-sliced scans).", ScanLimits.MIN_RADIUS, MAX_SCAN_RADIUS, DEFAULT_RADIUS)
                            .optionalEnum("shape", "Volume shape around the centre.", "cube",
                                    "cube", "sphere", "cylinder", "shell")
                            .optionalInt("x", "Center X; defaults to the drone.")
                            .optionalInt("y", "Center Y; defaults to the drone.")
                            .optionalInt("z", "Center Z; defaults to the drone.")
                            .optionalInt("min_y", "Lowest Y to scan, " + WorldBounds.MIN_SCAN_Y + ".." + WorldBounds.MAX_Y + ".")
                            .optionalInt("max_y", "Highest Y to scan, " + WorldBounds.MIN_SCAN_Y + ".." + WorldBounds.MAX_Y + ".")
                            .optionalInt("limit", "Maximum blocks returned, " + ScanLimits.MIN_RESULTS + ".." + MAX_SCAN_RESULTS + ".",
                                    ScanLimits.MIN_RESULTS, MAX_SCAN_RESULTS, DEFAULT_RESULTS)
                            .optionalBoolean("include_air", "Include air matches in the sample.", false))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.SCAN_MS)
                    .capabilities(ToolCapability.TIME_SLICED, ToolCapability.INLINE_WHEN_SMALL)
                    .build(),
            ToolSpec.builder("scan_veins")
                    .description("Find connected vein clusters for an ore/block filter and return each "
                            + "cluster's center, size and bounding box. More useful than scan_blocks when "
                            + "you need a mining plan. Centred on the DRONE by default.")
                    .parameters(ToolSchema.object()
                            .optionalString("filter", "Block name, registry name or ore tag (bare or #tag).", "ores")
                            .optionalString("anchor", "drone (default) or player.", "drone")
                            .optionalInt("radius", "Search radius, " + ScanLimits.MIN_VEIN_RADIUS + ".." + MAX_VEIN_RADIUS + ".",
                                    ScanLimits.MIN_VEIN_RADIUS, MAX_VEIN_RADIUS,
                                    ScanLimits.DEFAULT_VEIN_RADIUS)
                            .optionalInt("x", "Center X; defaults to the drone.")
                            .optionalInt("y", "Center Y; defaults to the drone.")
                            .optionalInt("z", "Center Z; defaults to the drone."))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.SCAN_MS).build(),
            ToolSpec.builder("find_distant")
                    .description("Search terrain farther than normal scan range for a resource block or "
                            + "biome name (e.g. 'jungle', 'desert', 'meteorite', 'oil_sand'). Returns the "
                            + "nearest hits and distance so you can plan a long flight. Searches outward "
                            + "from the DRONE, not the player. Loading distant chunks is navigation's job: "
                            + "this tool only inspects chunks that are already loaded, so combine it with "
                            + "goto (which force-loads a corridor) for far targets.")
                    .parameters(ToolSchema.object()
                            .requiredString("target", "Block/ore/biome name, ore tag or category to find.")
                            .optionalInt("max_radius", "Maximum search distance, " + ScanLimits.MIN_DISTANT_RADIUS + ".." + MAX_DISTANT_RADIUS + ".",
                                    ScanLimits.MIN_DISTANT_RADIUS, MAX_DISTANT_RADIUS,
                                    ScanLimits.DEFAULT_DISTANT_RADIUS))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.MAIN_MS).build()
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
            case "scan_room":
                return scanRoom(world, drone, player, args);
            case "scan_blocks":
                return scanBlocks(world, drone, player, args);
            case "scan_veins":
                return scanVeins(world, drone, player, args);
            case "find_distant":
                return findDistant(world, drone, args);
            default:
                return ToolJson.error("unknown_tool", "Unhandled scan tool: " + tool, null, false);
        }
    }

    private JsonObject scanRoom(World world, Drone drone, EntityPlayerMP player, JsonObject args) {
        int[] center = WorldToolSupport.scanCenter(drone, player, args);
        int radius = args.has("maxRadius") ? args.get("maxRadius").getAsInt()
                : ScanLimits.DEFAULT_ROOM_RADIUS;
        if (!ChunkAccess.isBlockLoaded(world, center[0], center[2])) {
            return WorldToolSupport.chunkNotLoaded("scan_room");
        }
        return this.worldScanner.scanRoom3D(world, center[0], center[1], center[2], radius);
    }

    private JsonObject scanBlocks(World world, Drone drone, EntityPlayerMP player, JsonObject args) {
        String filter = args.has("filter") ? args.get("filter").getAsString() : "ores";
        int radius = args.has("radius")
                ? ScanLimits.clampRadius(args.get("radius").getAsInt())
                : ScanLimits.DEFAULT_RADIUS;
        int[] center = WorldToolSupport.scanCenter(drone, player, args);
        int cx = center[0], cy = center[1], cz = center[2];
        int minY = args.has("minY") ? Math.max(WorldBounds.MIN_Y, args.get("minY").getAsInt())
                : (!args.has("y") && ItemClassifier.isLikelyUndergroundQuery(filter)
                ? WorldBounds.MIN_SCAN_Y : Math.max(WorldBounds.MIN_Y, cy - radius));
        int maxY = args.has("maxY") ? Math.min(WorldBounds.MAX_Y, args.get("maxY").getAsInt())
                : Math.min(WorldBounds.MAX_Y, cy + radius);
        boolean skipAir = !args.has("include_air") || !args.get("include_air").getAsBoolean();
        ScanShape shape = ScanShape.parse(args.has("shape") ? args.get("shape").getAsString() : "cube");
        int limit = args.has("limit")
                ? ScanLimits.clampResults(args.get("limit").getAsInt())
                : ScanLimits.DEFAULT_RESULTS;

        List<int[]> matched = new ArrayList<>();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    if (!ChunkAccess.isBlockLoaded(world, x, z)) continue;
                    if (!shape.contains(x - cx, y - cy, z - cz, radius)) continue;
                    boolean air = world.isAirBlock(x, y, z);
                    if (air && skipAir) continue;
                    Block block = world.getBlock(x, y, z);
                    int meta = world.getBlockMetadata(x, y, z);
                    boolean match = "liquid".equalsIgnoreCase(filter)
                            ? ItemClassifier.isLiquidBlock(block)
                            : ItemClassifier.matchesMineFilter(block, meta, filter);
                    if (match) {
                        int distSq = (x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz);
                        matched.add(new int[]{x, y, z, meta, distSq});
                    }
                }
            }
        }
        matched.sort(Comparator.comparingInt(a -> a[4]));

        JsonArray blocks = new JsonArray();
        int returned = Math.min(limit, matched.size());
        Map<String, JsonArray> tagCache = new HashMap<>();
        Set<String> tagless = new HashSet<>();
        for (int i = 0; i < returned; i++) {
            int[] point = matched.get(i);
            int x = point[0], y = point[1], z = point[2], meta = point[3];
            Block block = world.getBlock(x, y, z);
            JsonObject entry = WorldToolSupport.blockJson(world, x, y, z);
            entry.addProperty("dist", Math.round(Math.sqrt(point[4]) * 10.0) / 10.0);
            String registryName = block != null ? Block.blockRegistry.getNameForObject(block) : "";
            String tagKey = (registryName != null ? registryName : "") + TAG_KEY_SEPARATOR + meta;
            JsonArray cached = tagCache.get(tagKey);
            if (cached == null && !tagless.contains(tagKey)) {
                cached = ItemClassifier.blockOreDictJson(block, meta);
                if (cached != null) {
                    tagCache.put(tagKey, cached);
                } else {
                    tagless.add(tagKey);
                }
            }
            if (cached != null) entry.add("oreDict", cached);
            blocks.add(entry);
        }
        JsonObject out = ToolJson.ok();
        out.addProperty("count", matched.size());
        out.add("blocks", blocks);
        return out;
    }

    private JsonObject scanVeins(World world, Drone drone, EntityPlayerMP player, JsonObject args) {
        String filter = args.has("filter") ? args.get("filter").getAsString() : "ores";
        int radius = args.has("radius")
                ? Math.max(ScanLimits.MIN_VEIN_RADIUS,
                Math.min(MAX_VEIN_RADIUS, args.get("radius").getAsInt()))
                : ScanLimits.DEFAULT_VEIN_RADIUS;
        int[] center = WorldToolSupport.scanCenter(drone, player, args);
        if (!ChunkAccess.isBlockLoaded(world, center[0], center[2])) {
            return WorldToolSupport.chunkNotLoaded("scan_veins");
        }
        return this.worldScanner.scanConnectedVeins(world, center[0], center[1], center[2], radius, filter);
    }

    private JsonObject findDistant(World world, Drone drone, JsonObject args) {
        String target = args.has("target") ? args.get("target").getAsString() : "jungle";
        int maxRadius = args.has("maxRadius")
                ? Math.max(ScanLimits.MIN_DISTANT_RADIUS,
                Math.min(MAX_DISTANT_RADIUS, args.get("maxRadius").getAsInt()))
                : ScanLimits.DEFAULT_DISTANT_RADIUS;
        double startX = drone.posX;
        double startZ = drone.posZ;
        if (!ChunkAccess.isBlockLoaded(world, startX, startZ)) {
            return WorldToolSupport.chunkNotLoaded("find_distant");
        }
        List<int[]> found = this.worldScanner.findDistantResourceOrBiome(
                world, null, startX, startZ, target, maxRadius);
        JsonObject out = ToolJson.ok();
        out.addProperty("found", !found.isEmpty());
        out.addProperty("count", found.size());
        JsonArray blocks = new JsonArray();
        for (int[] point : found) {
            JsonArray pair = new JsonArray();
            pair.add(new JsonPrimitive(point[0]));
            pair.add(new JsonPrimitive(point[1]));
            pair.add(new JsonPrimitive(point[2]));
            blocks.add(pair);
        }
        out.add("blocks", blocks);
        if (!found.isEmpty()) {
            int[] first = found.get(0);
            out.addProperty("x", first[0]);
            out.addProperty("y", first[1]);
            out.addProperty("z", first[2]);
            out.addProperty("distance", Math.round(Math.hypot(first[0] - startX, first[2] - startZ)));
            BiomeGenBase biome = world.getBiomeGenForCoords(first[0], first[2]);
            out.addProperty("biome", biome != null && biome.biomeName != null ? biome.biomeName : "Unknown");
        }
        return out;
    }
}
