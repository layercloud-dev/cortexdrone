package ir.layercloud.cortexdrone.service.world;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parametric architectural blueprint planner plus flat-pad search.
 *
 * <p>It produces a <em>plan</em>, never actions: a list of placements with an
 * explicit build phase, a clearing list, material totals and the door/window
 * openings. The {@code build_structure} skill verifies the site with
 * {@link SiteSurvey}, adapts materials and places the tasks in phase order.</p>
 *
 * <p>Supported styles: {@code house}, {@code hut}, {@code wall}, {@code tower},
 * {@code platform}, {@code bridge}. Roof styles: {@code flat}, {@code gable},
 * {@code hip}, {@code pyramid}. Every plan is safe by construction: no task is
 * placed inside a protected fixture, and clearing only targets mineable blocks.</p>
 */
public class StructureArchitect {
    private static final int MAX_TASKS = 6000;

    public enum Phase {
        FOUNDATION, FLOOR, CORNER, WALL, WINDOW, RAILING, ROOF, DETAIL
    }

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
        private final String style;
        private final String roofStyle;
        private final List<int[]> clearBlocks = new ArrayList<>();
        private final List<BuildTask> buildTasks = new ArrayList<>();
        private final Map<String, Integer> materialCounts = new LinkedHashMap<>();
        private final Map<String, Integer> phaseCounts = new LinkedHashMap<>();
        private int doorX;
        private int doorY;
        private int doorZ;

        public void addTask(int x, int y, int z, String blockFilter, int meta,
                            boolean replace, Phase phase) {
            if (this.buildTasks.size() >= MAX_TASKS) return;
            BuildTask task = new BuildTask(x, y, z, blockFilter, meta, replace,
                    phase.name().toLowerCase(Locale.ROOT));
            this.buildTasks.add(task);
            this.materialCounts.merge(task.getBlockFilter(), 1, Integer::sum);
            this.phaseCounts.merge(task.getPhase(), 1, Integer::sum);
        }

        public void setDoor(int x, int y, int z) {
            this.doorX = x;
            this.doorY = y;
            this.doorZ = z;
        }

        /** Placements ordered so lower phases and lower Y come first. */
        public List<BuildTask> orderedTasks() {
            List<BuildTask> ordered = new ArrayList<>(this.buildTasks);
            ordered.sort(Comparator
                    .comparingInt(BuildTask::phaseRank)
                    .thenComparingInt(BuildTask::getY)
                    .thenComparingInt(BuildTask::getX)
                    .thenComparingInt(BuildTask::getZ));
            return ordered;
        }

        public JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("x1", this.x1);
            out.addProperty("z1", this.z1);
            out.addProperty("x2", this.x2);
            out.addProperty("z2", this.z2);
            out.addProperty("width", this.x2 - this.x1 + 1);
            out.addProperty("depth", this.z2 - this.z1 + 1);
            out.addProperty("floorY", this.floorY);
            out.addProperty("maxY", this.maxY);
            out.addProperty("doorSide", this.doorSide);
            out.addProperty("style", this.style);
            out.addProperty("roofStyle", this.roofStyle);
            out.addProperty("clearCount", this.clearBlocks.size());
            out.addProperty("buildCount", this.buildTasks.size());

            JsonObject door = new JsonObject();
            door.addProperty("x", this.doorX);
            door.addProperty("y", this.doorY);
            door.addProperty("z", this.doorZ);
            door.addProperty("side", this.doorSide);
            out.add("door", door);

            JsonObject mats = new JsonObject();
            for (Map.Entry<String, Integer> entry : this.materialCounts.entrySet()) {
                mats.addProperty(entry.getKey(), entry.getValue());
            }
            out.add("materialsRequired", mats);
            JsonObject phases = new JsonObject();
            for (Map.Entry<String, Integer> entry : this.phaseCounts.entrySet()) {
                phases.addProperty(entry.getKey(), entry.getValue());
            }
            out.add("phases", phases);

            JsonArray clear = new JsonArray();
            for (int[] point : this.clearBlocks) {
                JsonObject block = new JsonObject();
                block.addProperty("x", point[0]);
                block.addProperty("y", point[1]);
                block.addProperty("z", point[2]);
                clear.add(block);
            }
            out.add("clear", clear);

            JsonArray tasks = new JsonArray();
            for (BuildTask task : orderedTasks()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("x", task.getX());
                entry.addProperty("y", task.getY());
                entry.addProperty("z", task.getZ());
                entry.addProperty("block", task.getBlockFilter());
                entry.addProperty("phase", task.getPhase());
                if (task.getMeta() >= 0) entry.addProperty("meta", task.getMeta());
                tasks.add(entry);
            }
            out.add("tasks", tasks);
            return out;
        }
    }

    // ------------------------------------------------------------------
    // Site search
    // ------------------------------------------------------------------

    /**
     * Scans around the player's crosshair look point or facing direction to find
     * the flattest, unobstructed WxD build pad.
     */
    public JsonObject findBestBuildSite(World world, EntityPlayerMP player, DroneController ctrl,
                                        int width, int depth, int maxRadius) {
        return findBestBuildSite(world, player, ctrl, width, depth, maxRadius, "drone");
    }

    public JsonObject findBestBuildSite(World world, EntityPlayerMP player, DroneController ctrl,
                                        int width, int depth, int maxRadius, String anchor) {
        int w = Math.max(3, Math.min(21, width));
        int d = Math.max(3, Math.min(21, depth));
        int r = Math.max(8, Math.min(40, maxRadius));

        // The search radius is always the drone's; only the preferred spot may
        // be biased by where the player is looking.
        boolean usePlayer = "player".equalsIgnoreCase(anchor) && player != null;
        double px = usePlayer ? player.posX : ctrl.drone.posX;
        double py = usePlayer ? player.posY : ctrl.drone.posY;
        double pz = usePlayer ? player.posZ : ctrl.drone.posZ;
        int refY = MathHelper.floor_double(py);

        int prefCenterX = MathHelper.floor_double(px);
        int prefCenterZ = MathHelper.floor_double(pz);
        if (usePlayer) {
            JsonObject look = WorldScanner.raycastPlayerLook(world, player, 32.0);
            if (look != null && look.has("x") && look.has("z")
                    && look.get("distance").getAsDouble() >= 4.0) {
                prefCenterX = look.get("x").getAsInt();
                prefCenterZ = look.get("z").getAsInt();
            } else {
                double yawRad = Math.toRadians(player.rotationYaw);
                prefCenterX = MathHelper.floor_double(px - Math.sin(yawRad) * (Math.max(w, d) / 2.0 + 4.0));
                prefCenterZ = MathHelper.floor_double(pz + Math.cos(yawRad) * (Math.max(w, d) / 2.0 + 4.0));
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

                // Keep at least 2 blocks away from the player's standing position.
                if (px >= x1 - 1 && px <= x2 + 2 && pz >= z1 - 1 && pz <= z2 + 2) continue;

                int minY = Integer.MAX_VALUE;
                int maxY = Integer.MIN_VALUE;
                int sumY = 0;
                int count = 0;
                int liquidPenalty = 0;
                boolean touchesProtected = false;

                for (int x = x1; x <= x2 && !touchesProtected; x++) {
                    for (int z = z1; z <= z2; z++) {
                        int gy = SiteSurvey.groundY(world, x, z, refY);
                        if (ItemClassifier.isProtectedFixture(world, x, gy, z)
                                || ItemClassifier.isProtectedFixture(world, x, gy + 1, z)) {
                            touchesProtected = true;
                            break;
                        }
                        if (ItemClassifier.isLiquidBlock(world.getBlock(x, gy, z))) liquidPenalty += 25;
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
        res.addProperty("centerX", MathHelper.floor_double(centerX));
        res.addProperty("centerZ", MathHelper.floor_double(centerZ));
        res.addProperty("groundY", bestGroundY);
        res.addProperty("floorY", bestMaxY);
        res.addProperty("wallBaseY", bestMaxY + 1);
        res.addProperty("minTopY", bestMinY);
        res.addProperty("maxTopY", bestMaxY);
        res.addProperty("slope", bestMaxY - bestMinY);
        res.addProperty("doorSide", doorSide);
        return res;
    }

    public static String resolveDoorSideTowardPlayer(double px, double pz,
                                                     double centerX, double centerZ) {
        double dx = px - centerX;
        double dz = pz - centerZ;
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? "east" : "west";
        return dz > 0 ? "south" : "north";
    }

    // ------------------------------------------------------------------
    // Blueprint
    // ------------------------------------------------------------------

    public StructurePlan generateStructureBlueprint(World world, EntityPlayerMP player,
                                                    DroneController ctrl, JsonObject spec) {
        int width = spec.has("width") ? Math.max(3, Math.min(19, spec.get("width").getAsInt())) : 5;
        int depth = spec.has("depth") ? Math.max(3, Math.min(19, spec.get("depth").getAsInt())) : width;
        int wallHeight = spec.has("height") ? Math.max(2, Math.min(12, spec.get("height").getAsInt())) : 3;
        String style = spec.has("style")
                ? spec.get("style").getAsString().toLowerCase(Locale.ROOT) : "house";
        String roofStyle = spec.has("roofStyle")
                ? spec.get("roofStyle").getAsString().toLowerCase(Locale.ROOT) : "flat";
        boolean includeWindows = !spec.has("windows") || spec.get("windows").getAsBoolean();

        String fallback = spec.has("block") ? spec.get("block").getAsString() : "cobblestone";
        String floorBlock = string(spec, "floor", fallback);
        String wallBlock = string(spec, "walls", fallback);
        String cornerBlock = string(spec, "corners", wallBlock);
        String roofBlock = string(spec, "roof", cornerBlock);
        String windowBlock = string(spec, "windowBlock", "glass");
        String foundationBlock = string(spec, "foundation", "cobblestone");

        int x1;
        int z1;
        int x2;
        int z2;
        int floorY;
        String doorSide;

        if (spec.has("x1") && spec.has("z1") && spec.has("x2") && spec.has("z2")) {
            x1 = Math.min(spec.get("x1").getAsInt(), spec.get("x2").getAsInt());
            x2 = Math.max(spec.get("x1").getAsInt(), spec.get("x2").getAsInt());
            z1 = Math.min(spec.get("z1").getAsInt(), spec.get("z2").getAsInt());
            z2 = Math.max(spec.get("z1").getAsInt(), spec.get("z2").getAsInt());
            width = x2 - x1 + 1;
            depth = z2 - z1 + 1;
            int refY = player != null && player.worldObj == world
                    ? MathHelper.floor_double(player.posY) : MathHelper.floor_double(ctrl.drone.posY);
            floorY = spec.has("y") ? spec.get("y").getAsInt()
                    : (spec.has("floorY") ? spec.get("floorY").getAsInt()
                    : SiteSurvey.groundY(world, (x1 + x2) / 2, (z1 + z2) / 2, refY));
            if (!spec.has("y") && !spec.has("floorY")) {
                floorY = highestGround(world, x1, z1, x2, z2, floorY);
            }
            doorSide = spec.has("doorSide") ? spec.get("doorSide").getAsString().toLowerCase(Locale.ROOT)
                    : resolveDoorSideTowardPlayer(player != null ? player.posX : ctrl.drone.posX,
                    player != null ? player.posZ : ctrl.drone.posZ, (x1 + x2) * 0.5, (z1 + z2) * 0.5);
        } else if (spec.has("x") && spec.has("z")) {
            int cx = spec.get("x").getAsInt();
            int cz = spec.get("z").getAsInt();
            x1 = cx - width / 2;
            z1 = cz - depth / 2;
            x2 = x1 + width - 1;
            z2 = z1 + depth - 1;
            int refY = player != null && player.worldObj == world
                    ? MathHelper.floor_double(player.posY) : MathHelper.floor_double(ctrl.drone.posY);
            floorY = spec.has("y") ? spec.get("y").getAsInt()
                    : (spec.has("floorY") ? spec.get("floorY").getAsInt()
                    : highestGround(world, x1, z1, x2, z2,
                    SiteSurvey.groundY(world, cx, cz, refY)));
            doorSide = spec.has("doorSide") ? spec.get("doorSide").getAsString().toLowerCase(Locale.ROOT)
                    : resolveDoorSideTowardPlayer(player != null ? player.posX : ctrl.drone.posX,
                    player != null ? player.posZ : ctrl.drone.posZ, cx, cz);
        } else {
            JsonObject site = findBestBuildSite(world, player, ctrl, width, depth, 22);
            x1 = site.get("x1").getAsInt();
            z1 = site.get("z1").getAsInt();
            x2 = site.get("x2").getAsInt();
            z2 = site.get("z2").getAsInt();
            floorY = site.get("floorY").getAsInt();
            doorSide = spec.has("doorSide") ? spec.get("doorSide").getAsString().toLowerCase(Locale.ROOT)
                    : site.get("doorSide").getAsString();
        }

        boolean platform = style.equals("platform") || style.equals("pad") || style.equals("floor");
        boolean wallOnly = style.equals("wall") || style.equals("fence");
        boolean bridge = style.equals("bridge");
        boolean tower = style.equals("tower");
        int effectiveHeight = tower ? Math.max(wallHeight, Math.min(19, Math.max(width, depth) + 2))
                : wallHeight;
        int roofBaseY = floorY + effectiveHeight + 1;
        int maxY = platform || bridge ? floorY : roofBaseY + roofLayers(width, depth, roofStyle);

        StructurePlan plan = new StructurePlan(x1, z1, x2, z2, floorY, maxY, doorSide,
                style, roofStyle);

        // 1) Clear everything above and at the floor inside the footprint, top-down.
        // The floor layer itself is cleared so the new floor replaces dirt/grass
        // instead of silently failing on an occupied target.
        for (int y = maxY; y >= floorY; y--) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    if (ItemClassifier.isProtectedFixture(world, x, y, z)) continue;
                    if (ItemClassifier.isMineableBlock(world, x, y, z)) {
                        plan.clearBlocks.add(new int[]{x, y, z});
                    }
                }
            }
        }

        // 2) Foundation + floor.
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int fy = floorY - 1; fy >= floorY - 2; fy--) {
                    if (!ItemClassifier.isReplaceableBlock(world, x, fy, z)) continue;
                    boolean perimeter = x == x1 || x == x2 || z == z1 || z == z2;
                    if (perimeter || fy == floorY - 1) {
                        plan.addTask(x, fy, z, foundationBlock, -1, false, Phase.FOUNDATION);
                    }
                }
                plan.addTask(x, floorY, z, floorBlock, -1, true, Phase.FLOOR);
            }
        }

        if (platform) {
            addRailings(plan, x1, z1, x2, z2, floorY + 1, wallBlock, doorSide);
            return plan;
        }

        if (bridge) {
            addPiers(plan, x1, z1, x2, z2, floorY, foundationBlock);
            addRailings(plan, x1, z1, x2, z2, floorY + 1, wallBlock, doorSide);
            return plan;
        }

        int midX = (x1 + x2) / 2;
        int midZ = (z1 + z2) / 2;

        // 3) Walls, corners, door opening and windows.
        for (int y = floorY + 1; y <= floorY + effectiveHeight; y++) {
            int relY = y - floorY;
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    boolean perimeter = x == x1 || x == x2 || z == z1 || z == z2;
                    if (!perimeter) continue;

                    boolean corner = (x == x1 || x == x2) && (z == z1 || z == z2);
                    if (corner) {
                        plan.addTask(x, y, z, cornerBlock, -1, true, Phase.CORNER);
                        continue;
                    }

                    boolean doorOpening = isDoorCell(x, z, midX, midZ, doorSide, x1, x2, z1, z2)
                            && relY <= 2;
                    if (doorOpening) {
                        if (relY == 1) plan.setDoor(x, y, z);
                        continue;
                    }

                    boolean window = includeWindows && effectiveHeight >= 3 && relY == 2
                            && isWindowCell(x, z, midX, midZ, doorSide, x1, x2, z1, z2, width, depth);
                    plan.addTask(x, y, z, window ? windowBlock : wallBlock, -1, true,
                            window ? Phase.WINDOW : Phase.WALL);
                }
            }
        }

        if (wallOnly) {
            // A wall is four enclosing sides with no roof; the gate is the door gap.
            return plan;
        }

        // 4) Roof.
        addRoof(plan, x1, z1, x2, z2, roofBaseY, roofStyle, roofBlock);
        return plan;
    }

    private static void addRoof(StructurePlan plan, int x1, int z1, int x2, int z2,
                                int roofBaseY, String roofStyle, String roofBlock) {
        String style = roofStyle != null ? roofStyle : "flat";
        if (style.equals("gable") || style.equals("pitched") || style.equals("peak")) {
            boolean alongX = (x2 - x1) >= (z2 - z1);
            int span = alongX ? (z2 - z1) : (x2 - x1);
            int start = alongX ? z1 : x1;
            int end = alongX ? z2 : x2;
            int layers = (span + 1) / 2;
            for (int layer = 0; layer < layers; layer++) {
                int y = roofBaseY + layer;
                int low = start + layer;
                int high = end - layer;
                if (low > high) break;
                for (int x = x1; x <= x2; x++) {
                    for (int z = z1; z <= z2; z++) {
                        boolean edge = alongX ? (z == low || z == high) : (x == low || x == high);
                        boolean ridge = low == high;
                        if (edge || ridge) {
                            plan.addTask(x, y, z, roofBlock, -1, true, Phase.ROOF);
                        }
                    }
                }
            }
            return;
        }
        if (style.equals("hip") || style.equals("pyramid") || style.equals("pitched")) {
            int layers = Math.min(x2 - x1, z2 - z1) / 2 + 1;
            for (int layer = 0; layer < layers; layer++) {
                int y = roofBaseY + layer;
                int rx1 = x1 + layer;
                int rx2 = x2 - layer;
                int rz1 = z1 + layer;
                int rz2 = z2 - layer;
                if (rx1 > rx2 || rz1 > rz2) break;
                for (int x = rx1; x <= rx2; x++) {
                    for (int z = rz1; z <= rz2; z++) {
                        boolean edge = x == rx1 || x == rx2 || z == rz1 || z == rz2;
                        boolean cap = rx1 == rx2 || rz1 == rz2;
                        if (edge || cap) {
                            plan.addTask(x, y, z, roofBlock, -1, true, Phase.ROOF);
                        }
                    }
                }
            }
            return;
        }
        // Flat roof with a one-block lip so it reads as a roof, not a floating slab.
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                plan.addTask(x, roofBaseY, z, roofBlock, -1, true, Phase.ROOF);
            }
        }
    }

    private static int roofLayers(int width, int depth, String roofStyle) {
        String style = roofStyle != null ? roofStyle : "flat";
        if (style.equals("gable") || style.equals("pitched") || style.equals("peak")) {
            int span = Math.max(width, depth) - 1;
            return Math.max(1, (span + 1) / 2);
        }
        if (style.equals("hip") || style.equals("pyramid")) {
            return Math.max(1, Math.min(width, depth) / 2 + 1);
        }
        return 1;
    }

    private static void addPiers(StructurePlan plan, int x1, int z1, int x2, int z2,
                                 int floorY, String block) {
        int[][] corners = new int[][]{
                {x1, z1}, {x2, z1}, {x1, z2}, {x2, z2}
        };
        for (int[] corner : corners) {
            for (int y = floorY - 1; y >= floorY - 3; y--) {
                plan.addTask(corner[0], y, corner[1], block, -1, false, Phase.FOUNDATION);
            }
        }
        // Mid-span support for long bridges.
        int midX = (x1 + x2) / 2;
        int midZ = (z1 + z2) / 2;
        if (x2 - x1 > 8) {
            for (int y = floorY - 1; y >= floorY - 3; y--) {
                plan.addTask(midX, y, z1, block, -1, false, Phase.FOUNDATION);
                plan.addTask(midX, y, z2, block, -1, false, Phase.FOUNDATION);
            }
        }
        if (z2 - z1 > 8) {
            for (int y = floorY - 1; y >= floorY - 3; y--) {
                plan.addTask(x1, y, midZ, block, -1, false, Phase.FOUNDATION);
                plan.addTask(x2, y, midZ, block, -1, false, Phase.FOUNDATION);
            }
        }
    }

    private static void addRailings(StructurePlan plan, int x1, int z1, int x2, int z2,
                                    int y, String block, String doorSide) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean perimeter = x == x1 || x == x2 || z == z1 || z == z2;
                if (!perimeter) continue;
                boolean corner = (x == x1 || x == x2) && (z == z1 || z == z2);
                if (!corner && ((x + z) % 3 != 0)) continue;
                boolean doorGap = isDoorCell(x, z, (x1 + x2) / 2, (z1 + z2) / 2,
                        doorSide, x1, x2, z1, z2);
                if (doorGap) continue;
                plan.addTask(x, y, z, block, -1, true, Phase.RAILING);
            }
        }
    }

    private static boolean isDoorCell(int x, int z, int midX, int midZ, String side,
                                      int x1, int x2, int z1, int z2) {
        if (side == null) return false;
        switch (side) {
            case "north":
                return z == z1 && x == midX;
            case "south":
                return z == z2 && x == midX;
            case "west":
                return x == x1 && z == midZ;
            case "east":
                return x == x2 && z == midZ;
            default:
                return false;
        }
    }

    private static boolean isWindowCell(int x, int z, int midX, int midZ, String doorSide,
                                        int x1, int x2, int z1, int z2, int width, int depth) {
        if (width >= 5 && z == z1 && x == midX && !"north".equals(doorSide)) return true;
        if (width >= 5 && z == z2 && x == midX && !"south".equals(doorSide)) return true;
        if (depth >= 5 && x == x1 && z == midZ && !"west".equals(doorSide)) return true;
        if (depth >= 5 && x == x2 && z == midZ && !"east".equals(doorSide)) return true;
        return false;
    }

    private static int highestGround(World world, int x1, int z1, int x2, int z2, int fallback) {
        int highest = fallback;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                int y = SiteSurvey.groundY(world, x, z, fallback);
                if (y > highest) highest = y;
            }
        }
        return highest;
    }

    private static String string(JsonObject spec, String key, String fallback) {
        if (spec == null || !spec.has(key) || spec.get(key).isJsonNull()) return fallback;
        String value = spec.get(key).getAsString();
        return value != null && !value.trim().isEmpty() ? value.trim() : fallback;
    }
}
