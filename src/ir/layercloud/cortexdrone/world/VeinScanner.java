package ir.layercloud.cortexdrone.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.navigation.ChunkCacheContext;
import ir.layercloud.cortexdrone.navigation.FlightPathfinder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

/**
 * 3D 26-connected ore and resource vein clustering scanner backed by ChunkCacheContext.
 */
public final class VeinScanner {
    private VeinScanner() {
    }

    public static JsonObject scanConnectedVeins(World world, final int cx, final int cy, final int cz, int radius, String rawFilter) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        int r = Math.min(48, Math.max(4, radius));
        String filter = (rawFilter == null || rawFilter.trim().isEmpty()) ? "ores" : rawFilter.trim();
        boolean deepScan = ItemClassifier.isLikelyUndergroundQuery(filter) || filter.equalsIgnoreCase("ores") || filter.equalsIgnoreCase("all");
        int minY = deepScan ? 2 : Math.max(2, cy - r);
        int maxY = Math.min(254, cy + r);

        ChunkCacheContext bsi = new ChunkCacheContext(world, cx, cz, (r >> 4) + 2);
        Map<Long, int[]> matched = new LinkedHashMap<Long, int[]>();
        Map<String, Integer> histogram = new LinkedHashMap<String, Integer>();

        for (int x = cx - r; x <= cx + r; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    Block b = bsi.getBlock(x, y, z);
                    if (b == null || b == Blocks.field_150350_a || b == Blocks.field_150357_h) continue;
                    int meta = world.func_72805_g(x, y, z);
                    if (ItemClassifier.matchesMineFilter(b, meta, filter)) {
                        int id = Block.func_149682_b(b);
                        matched.put(FlightPathfinder.packBlockPos(x, y, z), new int[]{x, y, z, id, meta});
                        String disp = ItemClassifier.getBlockDisplayName(b, meta);
                        histogram.put(disp, (histogram.containsKey(disp) ? histogram.get(disp) : 0) + 1);
                    }
                }
            }
        }

        Set<Long> visited = new HashSet<Long>();
        List<JsonObject> veins = new ArrayList<JsonObject>();

        for (Map.Entry<Long, int[]> entry : matched.entrySet()) {
            long startKey = entry.getKey();
            if (!visited.add(startKey)) continue;
            int[] root = entry.getValue();
            int targetId = root[3];
            int targetMeta = root[4];
            Block rootBlock = Block.func_149729_e(targetId);
            String veinName = ItemClassifier.getBlockDisplayName(rootBlock, targetMeta);
            String reg = rootBlock != null ? Block.field_149771_c.func_148750_c(rootBlock) : "";

            List<int[]> cluster = new ArrayList<int[]>();
            Deque<int[]> q = new ArrayDeque<int[]>();
            q.add(root);
            boolean exposed = false;
            double sumX = 0, sumY = 0, sumZ = 0;

            while (!q.isEmpty() && cluster.size() < 256) {
                int[] cur = q.pollFirst();
                cluster.add(cur);
                sumX += cur[0];
                sumY += cur[1];
                sumZ += cur[2];
                if (!exposed) {
                    if (bsi.isPassable(cur[0] + 1, cur[1], cur[2])
                        || bsi.isPassable(cur[0] - 1, cur[1], cur[2])
                        || bsi.isPassable(cur[0], cur[1] + 1, cur[2])
                        || bsi.isPassable(cur[0], cur[1] - 1, cur[2])
                        || bsi.isPassable(cur[0], cur[1], cur[2] + 1)
                        || bsi.isPassable(cur[0], cur[1], cur[2] - 1)) {
                        exposed = true;
                    }
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            long nk = FlightPathfinder.packBlockPos(cur[0] + dx, cur[1] + dy, cur[2] + dz);
                            if (visited.contains(nk)) continue;
                            int[] nb = matched.get(nk);
                            if (nb != null && nb[3] == targetId && nb[4] == targetMeta) {
                                visited.add(nk);
                                q.addLast(nb);
                            }
                        }
                    }
                }
            }

            int n = cluster.size();
            int vx = (int) Math.round(sumX / n);
            int vy = (int) Math.round(sumY / n);
            int vz = (int) Math.round(sumZ / n);
            double dist = Math.sqrt((vx - cx) * (vx - cx) + (vy - cy) * (vy - cy) + (vz - cz) * (vz - cz));

            JsonObject vo = new JsonObject();
            vo.addProperty("name", veinName);
            vo.addProperty("registryName", reg != null ? reg : "");
            vo.addProperty("id", targetId);
            vo.addProperty("meta", targetMeta);
            vo.addProperty("count", n);
            vo.addProperty("exposed", exposed);
            vo.addProperty("centerX", vx);
            vo.addProperty("centerY", vy);
            vo.addProperty("centerZ", vz);
            vo.addProperty("dist", Math.round(dist * 10.0) / 10.0);
            vo.add("blocks", RoomScanner.coordsToJsonArray(cluster));
            veins.add(vo);
        }

        Collections.sort(veins, new Comparator<JsonObject>() {
            @Override
            public int compare(JsonObject a, JsonObject b) {
                boolean expA = a.get("exposed").getAsBoolean();
                boolean expB = b.get("exposed").getAsBoolean();
                if (expA != expB) return expA ? -1 : 1;
                return Double.compare(a.get("dist").getAsDouble(), b.get("dist").getAsDouble());
            }
        });

        JsonArray veinsArr = new JsonArray();
        int limit = Math.min(80, veins.size());
        for (int i = 0; i < limit; i++) {
            veinsArr.add(veins.get(i));
        }
        res.addProperty("totalBlocks", matched.size());
        res.addProperty("veinCount", veins.size());
        res.add("histogram", RoomScanner.mapToJson(histogram));
        res.add("veins", veinsArr);
        return res;
    }
}
