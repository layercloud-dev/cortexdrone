package ir.layercloud.cortexdrone.service.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import lombok.experimental.UtilityClass;
import net.minecraft.block.Block;
import net.minecraft.world.World;

import java.util.*;

/**
 * 3D interior flood-fill room scanner that identifies enclosed air volumes
 * and classifies surrounding floor, ceiling, and directional wall surfaces.
 */
@UtilityClass
public final class RoomScanner {
    private static final int[][] CARDINAL_DIRS = new int[][]{
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private static final int[][] HORIZ_DIRS = new int[][]{
            {0, -1}, {0, 1}, {-1, 0}, {1, 0}
    };

    public static JsonObject scanRoom3D(World world, int startX, int startY, int startZ, int maxRadius) {
        int r = Math.min(24, Math.max(4, maxRadius));
        int sx = startX;
        int sy = startY;
        int sz = startZ;

        if (!ItemClassifier.isPassableVoxel(world, sx, sy, sz)) {
            int[] open = FlightPathfinder.findNearestPassableVoxel(world, sx, sy, sz, 3);
            if (open != null) {
                sx = open[0];
                sy = open[1];
                sz = open[2];
            }
        }

        Set<Long> airSet = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{sx, sy, sz});
        airSet.add(FlightPathfinder.packBlockPos(sx, sy, sz));

        int minX = sx, maxX = sx;
        int minY = sy, maxY = sy;
        int minZ = sz, maxZ = sz;

        while (!queue.isEmpty() && airSet.size() < 4500) {
            int[] cur = queue.pollFirst();
            int cx = cur[0], cy = cur[1], cz = cur[2];
            if (cx < minX) minX = cx;
            if (cx > maxX) maxX = cx;
            if (cy < minY) minY = cy;
            if (cy > maxY) maxY = cy;
            if (cz < minZ) minZ = cz;
            if (cz > maxZ) maxZ = cz;

            for (int[] d : CARDINAL_DIRS) {
                int nx = cx + d[0];
                int ny = cy + d[1];
                int nz = cz + d[2];
                if (Math.abs(nx - sx) > r || Math.abs(ny - sy) > r || Math.abs(nz - sz) > r) continue;
                if (ny <= 1 || ny >= 254) continue;
                long key = FlightPathfinder.packBlockPos(nx, ny, nz);
                if (airSet.contains(key)) continue;
                if (ItemClassifier.isPassableVoxel(world, nx, ny, nz)) {
                    airSet.add(key);
                    queue.addLast(new int[]{nx, ny, nz});
                }
            }
        }

        List<int[]> floorList = new ArrayList<>();
        List<int[]> ceilingList = new ArrayList<>();
        List<int[]> northWall = new ArrayList<>();
        List<int[]> southWall = new ArrayList<>();
        List<int[]> westWall = new ArrayList<>();
        List<int[]> eastWall = new ArrayList<>();
        List<int[]> allWalls = new ArrayList<>();

        Set<Long> seenFloor = new HashSet<>();
        Set<Long> seenCeil = new HashSet<>();
        Set<Long> seenWall = new HashSet<>();

        Map<String, Integer> floorMats = new LinkedHashMap<>();
        Map<String, Integer> wallMats = new LinkedHashMap<>();
        Map<String, Integer> ceilMats = new LinkedHashMap<>();

        for (Long airKey : airSet) {
            int ax = (int) (airKey >> 38);
            if ((ax & 0x2000000) != 0) ax |= 0xFC000000;
            int az = (int) ((airKey >> 12) & 0x3FFFFFFL);
            if ((az & 0x2000000) != 0) az |= 0xFC000000;
            int ay = (int) (airKey & 0xFFFL);

            int fy = ay - 1;
            if (fy >= 1 && !ItemClassifier.isPassableVoxel(world, ax, fy, az) && !ItemClassifier.isProtectedFixture(world, ax, fy, az)) {
                long fk = FlightPathfinder.packBlockPos(ax, fy, az);
                if (seenFloor.add(fk)) {
                    floorList.add(new int[]{ax, fy, az});
                    Block fb = world.getBlock(ax, fy, az);
                    String fn = ItemClassifier.getBlockDisplayName(fb, world.getBlockMetadata(ax, fy, az));
                    floorMats.put(fn, (floorMats.getOrDefault(fn, 0)) + 1);
                }
            }

            int cy = ay + 1;
            if (cy <= 254 && !ItemClassifier.isPassableVoxel(world, ax, cy, az) && !ItemClassifier.isProtectedFixture(world, ax, cy, az)) {
                long ck = FlightPathfinder.packBlockPos(ax, cy, az);
                if (seenCeil.add(ck)) {
                    ceilingList.add(new int[]{ax, cy, az});
                    Block cb = world.getBlock(ax, cy, az);
                    String cn = ItemClassifier.getBlockDisplayName(cb, world.getBlockMetadata(ax, cy, az));
                    ceilMats.put(cn, (ceilMats.getOrDefault(cn, 0)) + 1);
                }
            }

            for (int dirIdx = 0; dirIdx < 4; dirIdx++) {
                int wx = ax + HORIZ_DIRS[dirIdx][0];
                int wz = az + HORIZ_DIRS[dirIdx][1];
                if (!ItemClassifier.isPassableVoxel(world, wx, ay, wz) && !ItemClassifier.isProtectedFixture(world, wx, ay, wz)) {
                    long wk = FlightPathfinder.packBlockPos(wx, ay, wz);
                    if (seenWall.add(wk)) {
                        int[] pt = new int[]{wx, ay, wz};
                        allWalls.add(pt);
                        if (dirIdx == 0) northWall.add(pt);
                        else if (dirIdx == 1) southWall.add(pt);
                        else if (dirIdx == 2) westWall.add(pt);
                        else eastWall.add(pt);
                        Block wb = world.getBlock(wx, ay, wz);
                        String wn = ItemClassifier.getBlockDisplayName(wb, world.getBlockMetadata(wx, ay, wz));
                        wallMats.put(wn, (wallMats.getOrDefault(wn, 0)) + 1);
                    }
                }
            }
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("interiorAirCount", airSet.size());
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minX", minX);
        bounds.addProperty("maxX", maxX);
        bounds.addProperty("minY", minY - 1);
        bounds.addProperty("maxY", maxY + 1);
        bounds.addProperty("minZ", minZ);
        bounds.addProperty("maxZ", maxZ);
        out.add("bounds", bounds);

        out.addProperty("floorCount", floorList.size());
        out.addProperty("ceilingCount", ceilingList.size());
        out.addProperty("wallsCount", allWalls.size());
        out.addProperty("northWallCount", northWall.size());
        out.addProperty("southWallCount", southWall.size());
        out.addProperty("westWallCount", westWall.size());
        out.addProperty("eastWallCount", eastWall.size());

        out.add("floorMaterials", mapToJson(floorMats));
        out.add("wallMaterials", mapToJson(wallMats));
        out.add("ceilingMaterials", mapToJson(ceilMats));

        out.add("floor", coordsToJsonArray(floorList));
        out.add("ceiling", coordsToJsonArray(ceilingList));
        out.add("walls", coordsToJsonArray(allWalls));
        out.add("northWall", coordsToJsonArray(northWall));
        out.add("southWall", coordsToJsonArray(southWall));
        out.add("westWall", coordsToJsonArray(westWall));
        out.add("eastWall", coordsToJsonArray(eastWall));
        return out;
    }

    static JsonObject mapToJson(Map<String, Integer> map) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            o.addProperty(e.getKey(), e.getValue());
        }
        return o;
    }

    static JsonArray coordsToJsonArray(List<int[]> list) {
        JsonArray arr = new JsonArray();
        for (int[] p : list) {
            JsonArray pt = new JsonArray();
            pt.add(new JsonPrimitive(p[0]));
            pt.add(new JsonPrimitive(p[1]));
            pt.add(new JsonPrimitive(p[2]));
            arr.add(pt);
        }
        return arr;
    }
}
