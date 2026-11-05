package ir.layercloud.cortexdrone.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.model.BuildTask;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.navigation.FlightPathfinder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Intelligent 3D terrain analyzer and parametric architectural blueprint planner.
 * Finds flat build pads near the player/look-point and generates complete, terrain-adapted
 * 3D building plans (foundation, floor, corners, walls, doorway, windows, roof).
 */
public class StructureArchitect {

    @Getter
    @RequiredArgsConstructor
    public static class StructurePlan {
        private final int x1;
        private final int z1;
        private final int x2;
        private final int z2;
        private final int floorY;
        private final int maxY;
        private final String doorSide;
        private final List<int[]> clearBlocks = new ArrayList<int[]>();
        private final List<BuildTask> buildTasks = new ArrayList<BuildTask>();
        private final Map<String, Integer> materialCounts = new LinkedHashMap<String, Integer>();

        public void addTask(int x, int y, int z, String blockFilter, int meta, boolean replace) {
            this.buildTasks.add(new BuildTask(x, y, z, blockFilter, meta, replace, null));
            Integer prev = this.materialCounts.get(blockFilter);
            this.materialCounts.put(blockFilter, (prev != null ? prev.intValue() : 0) + 1);
        }

        public JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("x1", this.x1);
            out.addProperty("z1", this.z1);
            out.addProperty("x2", this.x2);
            out.addProperty("z2", this.z2);
            out.addProperty("floorY", this.floorY);
            out.addProperty("maxY", this.maxY);
            out.addProperty("doorSide", this.doorSide);
            out.addProperty("clearCount", this.clearBlocks.size());
            out.addProperty("buildCount", this.buildTasks.size());
            JsonObject mats = new JsonObject();
            for (Map.Entry<String, Integer> e : this.materialCounts.entrySet()) {
                mats.addProperty(e.getKey(), e.getValue());
            }
            out.add("materialsRequired", mats);
            return out;
        }
    }

    private static int getSolidGroundY(World world, int x, int z, int refY) {
        FlightPathfinder.ensureChunkLoaded(world, x, z);
        int top = Math.min(250, Math.max(refY + 14, world.func_72976_f(x, z) + 2));
        int minY = Math.max(4, refY - 16);
        for (int y = top; y >= minY; y--) {
            if (world.func_147437_c(x, y, z)) continue;
            Block b = world.func_147439_a(x, y, z);
            if (b == null) continue;
            if (ItemClassifier.isLeafBlock(b) || ItemClassifier.isLogBlock(b) || ItemClassifier.isCropOrReedBlock(b)) {
                continue;
            }
            if (ItemClassifier.isReplaceableBlock(world, x, y, z) && !ItemClassifier.isLiquidBlock(b)) {
                continue;
            }
            return y;
        }
        return Math.max(4, world.func_72976_f(x, z) - 1);
    }

    /**
     * Scans around the player's crosshair look point or facing direction to find the flattest,
     * unobstructed WxD build pad.
     */
    public JsonObject findBestBuildSite(World world, EntityPlayerMP player, DroneController ctrl, int width, int depth, int maxRadius) {
        int w = Math.max(3, Math.min(21, width));
        int d = Math.max(3, Math.min(21, depth));
        int r = Math.max(8, Math.min(40, maxRadius));

        double px = player != null ? player.field_70165_t : ctrl.drone.field_70165_t;
        double py = player != null ? player.field_70163_u : ctrl.drone.field_70163_u;
        double pz = player != null ? player.field_70161_v : ctrl.drone.field_70161_v;
        int refY = MathHelper.func_76128_c(py);

        int prefCenterX = MathHelper.func_76128_c(px);
        int prefCenterZ = MathHelper.func_76128_c(pz);
        if (player != null) {
            JsonObject look = WorldScanner.raycastPlayerLook(world, player, 32.0);
            if (look != null && look.has("x") && look.has("z") && look.get("distance").getAsDouble() >= 4.0) {
                prefCenterX = look.get("x").getAsInt();
                prefCenterZ = look.get("z").getAsInt();
            } else {
                double yawRad = Math.toRadians(player.field_70177_z);
                prefCenterX = MathHelper.func_76128_c(px - Math.sin(yawRad) * (Math.max(w, d) / 2.0 + 4.0));
                prefCenterZ = MathHelper.func_76128_c(pz + Math.cos(yawRad) * (Math.max(w, d) / 2.0 + 4.0));
            }
        }

        int bestX1 = prefCenterX - w / 2;
        int bestZ1 = prefCenterZ - d / 2;
        int bestGroundY = refY - 1;
        int bestMinY = refY - 1;
        int bestMaxY = refY - 1;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx += 2) {
            for (int dz = -r; dz <= r; dz += 2) {
                int cx = prefCenterX + dx;
                int cz = prefCenterZ + dz;
                int x1 = cx - w / 2;
                int z1 = cz - d / 2;
                int x2 = x1 + w - 1;
                int z2 = z1 + d - 1;

                // Keep at least 2 blocks away from the player's standing position
                if (px >= x1 - 1 && px <= x2 + 2 && pz >= z1 - 1 && pz <= z2 + 2) {
                    continue;
                }

                int minY = Integer.MAX_VALUE;
                int maxY = Integer.MIN_VALUE;
                int sumY = 0;
                int count = 0;
                int liquidPenalty = 0;
                boolean touchesProtected = false;

                for (int x = x1; x <= x2 && !touchesProtected; x++) {
                    for (int z = z1; z <= z2; z++) {
                        int gy = getSolidGroundY(world, x, z, refY);
                        if (ItemClassifier.isProtectedFixture(world, x, gy, z) || ItemClassifier.isProtectedFixture(world, x, gy + 1, z)) {
                            touchesProtected = true;
                            break;
                        }
                        Block gb = world.func_147439_a(x, gy, z);
                        if (ItemClassifier.isLiquidBlock(gb)) {
                            liquidPenalty += 25;
                        }
                        if (gy < minY) minY = gy;
                        if (gy > maxY) maxY = gy;
                        sumY += gy;
                        count++;
                    }
                }
                if (touchesProtected || count == 0) continue;

                int slope = maxY - minY;
                int avgY = Math.round((float) sumY / (float) count);
                double distFromPref = Math.hypot(cx - prefCenterX, cz - prefCenterZ);
                double elevDiff = Math.abs(avgY - (refY - 1));
                double score = slope * 12.0 + liquidPenalty + distFromPref * 1.4 + elevDiff * 2.5;

                if (score < bestScore) {
                    bestScore = score;
                    bestX1 = x1;
                    bestZ1 = z1;
                    bestGroundY = avgY;
                    bestMinY = minY;
                    bestMaxY = maxY;
                }
            }
        }

        int bestX2 = bestX1 + w - 1;
        int bestZ2 = bestZ1 + d - 1;
        double centerX = (bestX1 + bestX2) * 0.5;
        double centerZ = (bestZ1 + bestZ2) * 0.5;
        String doorSide = resolveDoorSideTowardPlayer(px, pz, centerX, centerZ);

        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        res.addProperty("x1", bestX1);
        res.addProperty("z1", bestZ1);
        res.addProperty("x2", bestX2);
        res.addProperty("z2", bestZ2);
        res.addProperty("width", w);
        res.addProperty("depth", d);
        res.addProperty("centerX", MathHelper.func_76128_c(centerX));
        res.addProperty("centerZ", MathHelper.func_76128_c(centerZ));
        res.addProperty("groundY", bestGroundY);
        res.addProperty("floorY", bestGroundY);
        res.addProperty("wallBaseY", bestGroundY + 1);
        res.addProperty("minTopY", bestMinY);
        res.addProperty("maxTopY", bestMaxY);
        res.addProperty("slope", bestMaxY - bestMinY);
        res.addProperty("doorSide", doorSide);
        return res;
    }

    public static String resolveDoorSideTowardPlayer(double px, double pz, double centerX, double centerZ) {
        double dx = px - centerX;
        double dz = pz - centerZ;
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? "east" : "west";
        }
        return dz > 0 ? "south" : "north";
    }

    /**
     * Generates a complete 3D StructurePlan (terrain clearing + foundation + floor + walls + corners + doorway + roof).
     */
    public StructurePlan generateStructureBlueprint(World world, EntityPlayerMP player, DroneController ctrl, JsonObject spec) {
        int width = spec.has("width") ? Math.max(3, Math.min(19, spec.get("width").getAsInt())) : 5;
        int depth = spec.has("depth") ? Math.max(3, Math.min(19, spec.get("depth").getAsInt())) : 5;
        int wallHeight = spec.has("height") ? Math.max(2, Math.min(12, spec.get("height").getAsInt())) : 3;
        String style = spec.has("style") ? spec.get("style").getAsString().toLowerCase() : "house";
        String roofStyle = spec.has("roofStyle") ? spec.get("roofStyle").getAsString().toLowerCase() : "flat";
        boolean includeWindows = !spec.has("windows") || spec.get("windows").getAsBoolean();

        String floorBlock = spec.has("floor") ? spec.get("floor").getAsString()
            : (spec.has("wall") ? spec.get("wall").getAsString()
            : (spec.has("block") ? spec.get("block").getAsString() : "cobblestone"));
        String wallBlock = spec.has("walls") ? spec.get("walls").getAsString()
            : (spec.has("wall") ? spec.get("wall").getAsString()
            : (spec.has("block") ? spec.get("block").getAsString() : "cobblestone"));
        String cornerBlock = spec.has("corners") ? spec.get("corners").getAsString()
            : (spec.has("corner") ? spec.get("corner").getAsString() : wallBlock);
        String roofBlock = spec.has("roof") ? spec.get("roof").getAsString() : cornerBlock;

        int x1, z1, x2, z2, floorY;
        String doorSide;
        if (spec.has("x1") && spec.has("z1") && spec.has("x2") && spec.has("z2")) {
            x1 = Math.min(spec.get("x1").getAsInt(), spec.get("x2").getAsInt());
            x2 = Math.max(spec.get("x1").getAsInt(), spec.get("x2").getAsInt());
            z1 = Math.min(spec.get("z1").getAsInt(), spec.get("z2").getAsInt());
            z2 = Math.max(spec.get("z1").getAsInt(), spec.get("z2").getAsInt());
            width = x2 - x1 + 1;
            depth = z2 - z1 + 1;
            int refY = player != null ? MathHelper.func_76128_c(player.field_70163_u) : MathHelper.func_76128_c(ctrl.drone.field_70163_u);
            floorY = spec.has("y") ? spec.get("y").getAsInt()
                : (spec.has("floorY") ? spec.get("floorY").getAsInt() : getSolidGroundY(world, (x1 + x2) / 2, (z1 + z2) / 2, refY));
            double px = player != null ? player.field_70165_t : ctrl.drone.field_70165_t;
            double pz = player != null ? player.field_70161_v : ctrl.drone.field_70161_v;
            doorSide = spec.has("doorSide") ? spec.get("doorSide").getAsString().toLowerCase()
                : resolveDoorSideTowardPlayer(px, pz, (x1 + x2) * 0.5, (z1 + z2) * 0.5);
        } else if (spec.has("x") && spec.has("z")) {
            int cx = spec.get("x").getAsInt();
            int cz = spec.get("z").getAsInt();
            x1 = cx - width / 2;
            z1 = cz - depth / 2;
            x2 = x1 + width - 1;
            z2 = z1 + depth - 1;
            int refY = player != null ? MathHelper.func_76128_c(player.field_70163_u) : MathHelper.func_76128_c(ctrl.drone.field_70163_u);
            floorY = spec.has("y") ? spec.get("y").getAsInt()
                : (spec.has("floorY") ? spec.get("floorY").getAsInt() : getSolidGroundY(world, cx, cz, refY));
            double px = player != null ? player.field_70165_t : ctrl.drone.field_70165_t;
            double pz = player != null ? player.field_70161_v : ctrl.drone.field_70161_v;
            doorSide = spec.has("doorSide") ? spec.get("doorSide").getAsString().toLowerCase()
                : resolveDoorSideTowardPlayer(px, pz, cx, cz);
        } else {
            JsonObject site = findBestBuildSite(world, player, ctrl, width, depth, 22);
            x1 = site.get("x1").getAsInt();
            z1 = site.get("z1").getAsInt();
            x2 = site.get("x2").getAsInt();
            z2 = site.get("z2").getAsInt();
            floorY = site.get("floorY").getAsInt();
            doorSide = spec.has("doorSide") ? spec.get("doorSide").getAsString().toLowerCase() : site.get("doorSide").getAsString();
        }

        int roofBaseY = floorY + wallHeight + 1;
        StructurePlan plan = new StructurePlan(x1, z1, x2, z2, floorY, roofBaseY + 2, doorSide);

        // 1) Collect obstacles in interior + above floor that need clearing
        for (int y = roofBaseY + 2; y >= floorY + 1; y--) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    if (ItemClassifier.isMineableBlock(world, x, y, z)) {
                        plan.clearBlocks.add(new int[]{x, y, z});
                    }
                }
            }
        }

        // 2) Foundation & Floor at y = floorY (fills dips below floorY on perimeter as well)
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int fy = floorY - 2; fy < floorY; fy++) {
                    if (fy > 2 && (x == x1 || x == x2 || z == z1 || z == z2) && ItemClassifier.isReplaceableBlock(world, x, fy, z)) {
                        plan.addTask(x, fy, z, floorBlock, -1, false);
                    }
                }
                plan.addTask(x, floorY, z, floorBlock, -1, true);
            }
        }

        if ("platform".equals(style) || "pad".equals(style) || "floor".equals(style)) {
            return plan;
        }

        int midX = (x1 + x2) / 2;
        int midZ = (z1 + z2) / 2;

        // 3) Walls, Corners, Doorway & Window Openings
        for (int y = floorY + 1; y <= floorY + wallHeight; y++) {
            int relY = y - floorY;
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    boolean isPerim = (x == x1 || x == x2 || z == z1 || z == z2);
                    if (!isPerim) continue;

                    boolean isCorner = (x == x1 || x == x2) && (z == z1 || z == z2);
                    if (isCorner) {
                        plan.addTask(x, y, z, cornerBlock, -1, true);
                        continue;
                    }

                    // 1x2 Doorway on chosen doorSide
                    boolean isDoor = false;
                    if (relY <= 2) {
                        if ("north".equals(doorSide) && z == z1 && x == midX) isDoor = true;
                        else if ("south".equals(doorSide) && z == z2 && x == midX) isDoor = true;
                        else if ("west".equals(doorSide) && x == x1 && z == midZ) isDoor = true;
                        else if ("east".equals(doorSide) && x == x2 && z == midZ) isDoor = true;
                    }
                    if (isDoor) continue;

                    // Optional window openings at eye level (relY == 2) on non-door wall centers for footprints >= 5
                    if (includeWindows && relY == 2 && wallHeight >= 3) {
                        boolean isWin = (!"north".equals(doorSide) && z == z1 && x == midX && width >= 5)
                            || (!"south".equals(doorSide) && z == z2 && x == midX && width >= 5)
                            || (!"west".equals(doorSide) && x == x1 && z == midZ && depth >= 5)
                            || (!"east".equals(doorSide) && x == x2 && z == midZ && depth >= 5);
                        if (isWin) continue;
                    }

                    plan.addTask(x, y, z, wallBlock, -1, true);
                }
            }
        }

        // 4) Roof (flat, pyramid, or gable)
        if ("pyramid".equals(roofStyle) || "pitched".equals(roofStyle)) {
            int maxLayer = Math.min(width, depth) / 2;
            for (int layer = 0; layer <= maxLayer; layer++) {
                int ry = roofBaseY + layer;
                int rx1 = x1 + layer, rx2 = x2 - layer;
                int rz1 = z1 + layer, rz2 = z2 - layer;
                for (int x = rx1; x <= rx2; x++) {
                    for (int z = rz1; z <= rz2; z++) {
                        if (layer == maxLayer || x == rx1 || x == rx2 || z == rz1 || z == rz2) {
                            plan.addTask(x, ry, z, roofBlock, -1, true);
                        }
                    }
                }
            }
        } else {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    plan.addTask(x, roofBaseY, z, roofBlock, -1, true);
                }
            }
        }

        return plan;
    }
}
