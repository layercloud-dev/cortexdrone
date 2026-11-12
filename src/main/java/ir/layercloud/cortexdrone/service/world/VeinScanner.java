package ir.layercloud.cortexdrone.service.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import ir.layercloud.cortexdrone.service.world.raytrace.ChunkCacheContext;
import lombok.experimental.UtilityClass;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 3D 26-connected ore and resource vein clustering scanner backed by
 * ChunkCacheContext.
 *
 * <p>World reads happen here; the connected-component logic lives in
 * {@link VeinClustering} so it can be tested without a live world.</p>
 */
@UtilityClass
@SuppressWarnings("unused")
public final class VeinScanner {
    /** Ring-search radius bounds for a vein scan. */
    /**
     * @see ScanLimits#MIN_VEIN_RADIUS
     */
    public static final int MIN_RADIUS =
            ScanLimits.MIN_VEIN_RADIUS;
    /**
     * @see ScanLimits#MAX_VEIN_RADIUS
     */
    public static final int MAX_RADIUS =
            ScanLimits.MAX_VEIN_RADIUS;
    /**
     * Largest number of veins returned in one response.
     */
    public static final int MAX_VEINS_RETURNED = 80;

    private static final VeinClustering.PositionKey BLOCK_KEYS = FlightPathfinder::packBlockPos;

    public static JsonObject scanConnectedVeins(World world, final int cx, final int cy,
                                                final int cz, int radius, String rawFilter) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        int r = Math.min(MAX_RADIUS, Math.max(MIN_RADIUS, radius));
        String filter = (rawFilter == null || rawFilter.trim().isEmpty()) ? "ores" : rawFilter.trim();
        boolean deepScan = ItemClassifier.isLikelyUndergroundQuery(filter)
                || filter.equalsIgnoreCase("ores") || filter.equalsIgnoreCase("all");
        int minY = deepScan ? WorldBounds.MIN_SCAN_Y : Math.max(WorldBounds.MIN_SCAN_Y, cy - r);
        int maxY = Math.min(WorldBounds.MAX_Y, cy + r);

        ChunkCacheContext bsi = new ChunkCacheContext(world, cx, cz, (r >> 4) + 2);
        Map<Long, int[]> matched = new LinkedHashMap<>();
        Map<String, Integer> histogram = new LinkedHashMap<>();

        for (int x = cx - r; x <= cx + r; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    Block block = bsi.getBlock(x, y, z);
                    if (block == null || block == Blocks.air || block == Blocks.bedrock) continue;
                    int meta = world.getBlockMetadata(x, y, z);
                    if (!ItemClassifier.matchesMineFilter(block, meta, filter)) continue;
                    int id = Block.getIdFromBlock(block);
                    matched.put(FlightPathfinder.packBlockPos(x, y, z), new int[]{x, y, z, id, meta});
                    String display = ItemClassifier.getBlockDisplayName(block, meta);
                    histogram.merge(display, 1, Integer::sum);
                }
            }
        }

        List<VeinClustering.Cluster> clusters = VeinClustering.cluster(matched, BLOCK_KEYS);
        List<JsonObject> veins = new ArrayList<>(clusters.size());
        for (VeinClustering.Cluster cluster : clusters) {
            veins.add(describe(cluster, matched, cx, cy, cz, bsi));
        }

        veins.sort((a, b) -> {
            boolean exposedA = a.get("exposed").getAsBoolean();
            boolean exposedB = b.get("exposed").getAsBoolean();
            if (exposedA != exposedB) return exposedA ? -1 : 1;
            return Double.compare(a.get("dist").getAsDouble(), b.get("dist").getAsDouble());
        });

        JsonArray veinsArray = new JsonArray();
        int limit = Math.min(MAX_VEINS_RETURNED, veins.size());
        for (int i = 0; i < limit; i++) veinsArray.add(veins.get(i));

        res.addProperty("totalBlocks", matched.size());
        res.addProperty("veinCount", veins.size());
        res.add("histogram", RoomScanner.mapToJson(histogram));
        res.add("veins", veinsArray);
        return res;
    }

    private static JsonObject describe(VeinClustering.Cluster cluster, Map<Long, int[]> matched,
                                       int cx, int cy, int cz, ChunkCacheContext bsi) {
        int[] center = cluster.center();
        JsonObject vein = new JsonObject();
        String registryName = "";
        String veinName = "Unknown";
        int id = 0;
        int meta = 0;
        for (int[] block : cluster.blocks()) {
            id = block[3];
            meta = block[4];
            Block rootBlock = Block.getBlockById(id);
            veinName = ItemClassifier.getBlockDisplayName(rootBlock, meta);
            registryName = rootBlock != null
                    ? Block.blockRegistry.getNameForObject(rootBlock) : "";
            break;
        }
        vein.addProperty("name", veinName);
        vein.addProperty("registryName", registryName != null ? registryName : "");
        JsonArray tags = ItemClassifier.blockOreDictJson(Block.getBlockById(id), meta);
        if (tags != null) vein.add("oreDict", tags);
        vein.addProperty("id", id);
        vein.addProperty("meta", meta);
        vein.addProperty("count", cluster.totalCount());
        vein.addProperty("truncated", cluster.truncated());
        vein.addProperty("exposed", isExposed(cluster, bsi));
        vein.addProperty("centerX", center[0]);
        vein.addProperty("centerY", center[1]);
        vein.addProperty("centerZ", center[2]);
        double distance = Math.sqrt((center[0] - cx) * (center[0] - cx)
                + (center[1] - cy) * (center[1] - cy)
                + (center[2] - cz) * (center[2] - cz));
        vein.addProperty("dist", Math.round(distance * 10.0) / 10.0);
        vein.add("blocks", RoomScanner.coordsToJsonArray(cluster.blocks()));
        return vein;
    }

    private static boolean isExposed(VeinClustering.Cluster cluster, ChunkCacheContext bsi) {
        for (int[] block : cluster.blocks()) {
            if (bsi.isPassable(block[0] + 1, block[1], block[2])
                    || bsi.isPassable(block[0] - 1, block[1], block[2])
                    || bsi.isPassable(block[0], block[1] + 1, block[2])
                    || bsi.isPassable(block[0], block[1] - 1, block[2])
                    || bsi.isPassable(block[0], block[1], block[2] + 1)
                    || bsi.isPassable(block[0], block[1], block[2] - 1)) {
                return true;
            }
        }
        return false;
    }
}
