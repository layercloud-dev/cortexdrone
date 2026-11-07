package ir.layercloud.cortexdrone.service.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only build-site survey.
 *
 * <p>Before a structure is planned the drone needs the truth about the ground:
 * per-column surface height, slope, existing obstacles, liquids, protected
 * fixtures (chests, machines, OC blocks), headroom and the materials that would
 * have to be cleared. {@code StructureArchitect} uses the same data to pick a
 * pad; the {@code build_structure} skill calls this tool and reasons about the
 * returned numbers before placing a single block.</p>
 */
public final class SiteSurvey {
    private static final int MAX_SIDE = 33;

    private SiteSurvey() {
    }

    public static JsonObject survey(World world, EntityPlayerMP player, int x1, int z1, int x2, int z2,
                                    Integer explicitY, int height) {
        int minX = Math.min(x1, x2);
        int maxX = Math.min(minX + MAX_SIDE - 1, Math.max(x1, x2));
        int minZ = Math.min(z1, z2);
        int maxZ = Math.min(minZ + MAX_SIDE - 1, Math.max(z1, z2));
        int wallHeight = Math.max(1, Math.min(24, height));

        int refY = explicitY != null ? explicitY
                : (player != null ? MathHelper.floor_double(player.posY) : 64);

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("x1", minX);
        out.addProperty("z1", minZ);
        out.addProperty("x2", maxX);
        out.addProperty("z2", maxZ);
        out.addProperty("width", maxX - minX + 1);
        out.addProperty("depth", maxZ - minZ + 1);
        out.addProperty("referenceY", refY);

        JsonArray columns = new JsonArray();
        Map<String, Integer> clearMaterials = new LinkedHashMap<>();
        Map<String, Integer> groundMaterials = new LinkedHashMap<>();
        int minGround = Integer.MAX_VALUE;
        int maxGround = Integer.MIN_VALUE;
        long sumGround = 0L;
        int liquidColumns = 0;
        int protectedColumns = 0;
        int obstructedColumns = 0;
        int treeColumns = 0;
        int unloadedColumns = 0;
        int columnCount = 0;
        int airGapColumns = 0;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!ChunkAccess.isBlockLoaded(world, x, z)) {
                    unloadedColumns++;
                    continue;
                }
                int ground = groundY(world, x, z, refY);
                int top = Math.min(254, ground + wallHeight + 1);
                int centralObstacles = 0;
                int headroom = 0;
                boolean liquid = false;
                boolean protectedFixture = false;
                boolean tree = false;

                for (int y = ground + 1; y <= top; y++) {
                    if (y <= 0 || y >= 255) continue;
                    Block block = world.getBlock(x, y, z);
                    if (block == null || block == Blocks.air) {
                        if (centralObstacles == 0) headroom++;
                        continue;
                    }
                    if (ItemClassifier.isLiquidBlock(block)) {
                        liquid = true;
                        continue;
                    }
                    if (ItemClassifier.isProtectedFixture(world, x, y, z)) {
                        protectedFixture = true;
                        continue;
                    }
                    if (ItemClassifier.isLeafBlock(block) || ItemClassifier.isLogBlock(block)) {
                        tree = true;
                    }
                    centralObstacles++;
                    String name = ItemClassifier.getBlockDisplayName(block, world.getBlockMetadata(x, y, z));
                    clearMaterials.put(name, clearMaterials.getOrDefault(name, 0) + 1);
                }

                String groundName = world.isAirBlock(x, ground, z) ? "Air"
                        : ItemClassifier.getBlockDisplayName(world.getBlock(x, ground, z),
                        world.getBlockMetadata(x, ground, z));
                groundMaterials.put(groundName, groundMaterials.getOrDefault(groundName, 0) + 1);

                if (ground < minGround) minGround = ground;
                if (ground > maxGround) maxGround = ground;
                sumGround += ground;
                columnCount++;
                if (liquid) liquidColumns++;
                if (protectedFixture) protectedColumns++;
                if (centralObstacles > 0) obstructedColumns++;
                if (tree) treeColumns++;
                if (headroom <= 0) airGapColumns++;

                if (columns.size() < 1200) {
                    JsonObject column = new JsonObject();
                    column.addProperty("x", x);
                    column.addProperty("z", z);
                    column.addProperty("groundY", ground);
                    column.addProperty("headroom", headroom);
                    column.addProperty("obstacles", centralObstacles);
                    if (liquid) column.addProperty("liquid", true);
                    if (protectedFixture) column.addProperty("protected", true);
                    columns.add(column);
                }
            }
        }

        if (columnCount == 0) {
            out.addProperty("ok", false);
            out.addProperty("error", "No loaded chunks in the requested site. Move closer and retry.");
            out.addProperty("unloadedColumns", unloadedColumns);
            return out;
        }

        int avgGround = (int) Math.round((double) sumGround / (double) columnCount);
        int slope = maxGround - minGround;
        out.addProperty("groundYMin", minGround);
        out.addProperty("groundYMax", maxGround);
        out.addProperty("groundYAvg", avgGround);
        out.addProperty("slope", slope);
        out.addProperty("suggestedFloorY", maxGround);
        out.addProperty("clearHeightNeeded", wallHeight);
        out.addProperty("liquidColumns", liquidColumns);
        out.addProperty("protectedColumns", protectedColumns);
        out.addProperty("obstructedColumns", obstructedColumns);
        out.addProperty("treeColumns", treeColumns);
        out.addProperty("lowHeadroomColumns", airGapColumns);
        out.addProperty("unloadedColumns", unloadedColumns);
        out.addProperty("columnCount", columnCount);
        out.addProperty("suitable", protectedColumns == 0 && liquidColumns == 0
                && slope <= 3 && unloadedColumns == 0);
        out.add("groundMaterials", mapToJson(groundMaterials));
        out.add("clearMaterials", mapToJson(clearMaterials));
        out.add("columns", columns);

        JsonArray warnings = new JsonArray();
        if (slope > 3) {
            warnings.add(new com.google.gson.JsonPrimitive("Ground slope is " + slope
                    + " blocks; a foundation is required and the floor should sit at y=" + maxGround + "."));
        }
        if (liquidColumns > 0) {
            warnings.add(new com.google.gson.JsonPrimitive(liquidColumns + " columns contain liquid; drain or bridge them before building."));
        }
        if (protectedColumns > 0) {
            warnings.add(new com.google.gson.JsonPrimitive(protectedColumns
                    + " columns contain a protected fixture (chest, machine or OpenComputers block); "
                    + "do not clear those blocks."));
        }
        if (treeColumns > 0) {
            warnings.add(new com.google.gson.JsonPrimitive(treeColumns + " columns contain logs/leaves that must be cleared first."));
        }
        out.add("warnings", warnings);
        return out;
    }

    /**
     * First non-replaceable, non-plant surface block below the reference level.
     * Trees and plants are skipped so a forest floor is not mistaken for a roof.
     */
    static int groundY(World world, int x, int z, int refY) {
        if (!ChunkAccess.isBlockLoaded(world, x, z)) return refY;
        int top = Math.min(250, Math.max(refY + 20, world.getHeightValue(x, z) + 2));
        int minY = Math.max(3, refY - 20);
        for (int y = top; y >= minY; y--) {
            if (world.isAirBlock(x, y, z)) continue;
            Block block = world.getBlock(x, y, z);
            if (block == null) continue;
            if (ItemClassifier.isLeafBlock(block) || ItemClassifier.isLogBlock(block)
                    || ItemClassifier.isCropOrReedBlock(block)) {
                continue;
            }
            if (ItemClassifier.isReplaceableBlock(world, x, y, z) && !ItemClassifier.isLiquidBlock(block)) {
                continue;
            }
            return y;
        }
        return Math.max(4, world.getHeightValue(x, z) - 1);
    }

    static JsonObject mapToJson(Map<String, Integer> map) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            json.addProperty(entry.getKey(), entry.getValue());
        }
        return json;
    }

    /** Column heights only, used by pads that need a quick flatness check. */
    public static List<int[]> heights(World world, int x1, int z1, int x2, int z2, int refY) {
        List<int[]> heights = new ArrayList<>();
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                if (!ChunkAccess.isBlockLoaded(world, x, z)) continue;
                heights.add(new int[]{x, z, groundY(world, x, z, refY)});
            }
        }
        return heights;
    }
}
