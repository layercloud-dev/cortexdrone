package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.tool.*;
import ir.layercloud.cortexdrone.service.world.ChunkAccess;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.WorldBounds;
import ir.layercloud.cortexdrone.service.world.WorldScanner;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct block reads: one block, an inclusive box, a surface height and the
 * player's crosshair.
 *
 * <p>Every read is bounded and refuses unloaded chunks, so a tool call cannot
 * silently return "air" for terrain that is simply not loaded.</p>
 */
public final class WorldBlockReadTools implements ToolProvider {
    private static final int DEFAULT_RAY_DISTANCE = 80;
    /**
     * Largest square box edge a single read may cover.
     */
    public static final int MAX_BOX_EDGE = 32;
    /**
     * Block descriptors returned by a box read before the sample is truncated.
     */
    public static final int MAX_BOX_SAMPLE = 2500;

    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("get_block")
                    .description("Read one block: display name, registry name, id, metadata, hardness, "
                            + "light, passability, liquid and tile-entity/machine description when present.")
                    .parameters(ToolSchema.object()
                            .requiredInt("x", "Block X.").requiredInt("y", "Block Y.").requiredInt("z", "Block Z."))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.FASTEST_MS).build(),
            ToolSpec.builder("get_blocks_in_box")
                    .description("Read every block in an inclusive box and return counts by type plus a "
                            + "bounded sample. Use include_air=false to skip air. Large boxes are capped.")
                    .parameters(ToolSchema.object()
                            .requiredInt("x1", "First corner X.").requiredInt("y1", "First corner Y.")
                            .requiredInt("z1", "First corner Z.").requiredInt("x2", "Second corner X.")
                            .requiredInt("y2", "Second corner Y.").requiredInt("z2", "Second corner Z.")
                            .optionalBoolean("include_air", "Include air blocks in the sample.", false))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.FAST_MS).build(),
            ToolSpec.builder("get_top_y")
                    .description("Surface height and surface block/biome at x/z. Uses the loaded world, so "
                            + "the column must be in a loaded chunk.")
                    .parameters(ToolSchema.object().requiredInt("x", "Column X.").requiredInt("z", "Column Z."))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.FASTEST_MS).build(),
            ToolSpec.builder("raycast_player")
                    .description("Raycast where the task player is looking and return the first block hit. "
                            + "Use it to resolve 'this block', 'the block I'm looking at' or a schematic spot.")
                    .parameters(ToolSchema.object()
                            .optionalInt("max_dist", "Ray length in blocks, "
                                            + ir.layercloud.cortexdrone.service.world.raytrace.VoxelRaycaster3D.MIN_DISTANCE
                                            + ".." + ir.layercloud.cortexdrone.service.world.raytrace.VoxelRaycaster3D.MAX_DISTANCE + ".",
                                    ir.layercloud.cortexdrone.service.world.raytrace.VoxelRaycaster3D.MIN_DISTANCE,
                                    ir.layercloud.cortexdrone.service.world.raytrace.VoxelRaycaster3D.MAX_DISTANCE,
                                    DEFAULT_RAY_DISTANCE))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.FASTEST_MS).build()
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
            case "get_block":
                return getBlock(world, drone, args);
            case "get_blocks_in_box":
                return getBox(world, args);
            case "get_top_y":
                return getTopY(world, args);
            case "raycast_player":
                return raycast(world, ctrl, player, args);
            default:
                return unknown(tool);
        }
    }

    private static JsonObject getBlock(World world, Drone drone, JsonObject args) {
        int x = args.has("x") ? args.get("x").getAsInt() : MathHelper.floor_double(drone.posX);
        int y = args.has("y") ? args.get("y").getAsInt() : MathHelper.floor_double(drone.posY);
        int z = args.has("z") ? args.get("z").getAsInt() : MathHelper.floor_double(drone.posZ);
        if (!ChunkAccess.isBlockLoaded(world, x, z)) {
            return WorldToolSupport.chunkNotLoaded("get_block");
        }
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        JsonObject out = WorldToolSupport.blockJson(world, x, y, z);
        out.addProperty("passable", ItemClassifier.isPassableVoxel(world, x, y, z));
        out.addProperty("hardness", block != null ? block.getBlockHardness(world, x, y, z) : 0.0f);
        out.addProperty("light", world.getBlockLightValue(x, y, z));
        WorldToolSupport.addOreDict(out, block, meta, world.isAirBlock(x, y, z));
        return out;
    }

    private static JsonObject getBox(World world, JsonObject args) {
        int x1 = args.get("x1").getAsInt(), y1 = args.get("y1").getAsInt(), z1 = args.get("z1").getAsInt();
        int x2 = args.get("x2").getAsInt(), y2 = args.get("y2").getAsInt(), z2 = args.get("z2").getAsInt();
        boolean includeAir = args.has("includeAir") && args.get("includeAir").getAsBoolean();
        int minX = Math.min(x1, x2), maxX = Math.min(minX + MAX_BOX_EDGE, Math.max(x1, x2));
        int minY = Math.max(WorldBounds.MIN_Y, Math.min(y1, y2));
        int maxY = Math.min(WorldBounds.MAX_Y, Math.max(y1, y2));
        int minZ = Math.min(z1, z2), maxZ = Math.min(minZ + MAX_BOX_EDGE, Math.max(z1, z2));
        for (int cx = (minX >> 4); cx <= (maxX >> 4); cx++) {
            for (int cz = (minZ >> 4); cz <= (maxZ >> 4); cz++) {
                if (!ChunkAccess.isLoaded(world, cx, cz)) {
                    return WorldToolSupport.chunkNotLoaded("get_blocks_in_box");
                }
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        JsonArray blocks = new JsonArray();
        int totalVoxels = 0;
        int nonAirCount = 0;
        for (int y = maxY; y >= minY; y--) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    totalVoxels++;
                    boolean air = world.isAirBlock(x, y, z);
                    if (!air) nonAirCount++;
                    if (air && !includeAir) continue;
                    JsonObject entry = WorldToolSupport.blockJson(world, x, y, z);
                    counts.merge(entry.get("name").getAsString(), 1, Integer::sum);
                    if (blocks.size() < MAX_BOX_SAMPLE) {
                        Block block = world.getBlock(x, y, z);
                        WorldToolSupport.addOreDict(entry, block, block != null
                                ? world.getBlockMetadata(x, y, z) : 0, air);
                        blocks.add(entry);
                    }
                }
            }
        }
        JsonObject countsJson = new JsonObject();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            countsJson.addProperty(entry.getKey(), entry.getValue());
        }
        JsonObject out = ToolJson.ok();
        out.addProperty("totalVoxels", totalVoxels);
        out.addProperty("nonAirCount", nonAirCount);
        out.addProperty("count", nonAirCount);
        out.add("countsByType", countsJson);
        out.add("blocks", blocks);
        return out;
    }

    private static JsonObject getTopY(World world, JsonObject args) {
        int x = args.get("x").getAsInt();
        int z = args.get("z").getAsInt();
        if (!ChunkAccess.isBlockLoaded(world, x, z)) {
            return WorldToolSupport.chunkNotLoaded("get_top_y");
        }
        int topY = world.getHeightValue(x, z);
        int surfaceY = Math.max(WorldBounds.MIN_Y, topY - 1);
        Block surface = world.getBlock(x, surfaceY, z);
        BiomeGenBase biome = world.getBiomeGenForCoords(x, z);
        JsonObject out = ToolJson.ok();
        out.addProperty("x", x);
        out.addProperty("z", z);
        out.addProperty("topY", topY);
        out.addProperty("surfaceBlock", surface != null
                ? ItemClassifier.getBlockDisplayName(surface, world.getBlockMetadata(x, surfaceY, z))
                : "Air");
        out.addProperty("biome", biome != null && biome.biomeName != null ? biome.biomeName : "Unknown");
        return out;
    }

    private static JsonObject raycast(World world, DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        EntityPlayerMP target = player != null ? player
                : CortexDroneMod.findPlayer(ctrl.ownerName);
        double maxDist = args.has("maxDist") ? args.get("maxDist").getAsDouble()
                : (args.has("distance") ? args.get("distance").getAsDouble() : 80.0);
        JsonObject hit = WorldScanner.raycastPlayerLook(world, target, maxDist);
        JsonObject out = ToolJson.ok();
        out.addProperty("found", hit != null);
        if (hit != null) {
            out.add("hit", hit);
            for (Map.Entry<String, JsonElement> entry : hit.entrySet()) {
                out.add(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private static JsonObject unknown(String tool) {
        return ToolJson.error("unknown_tool", "Unhandled block-read tool: " + tool, null, false);
    }
}
