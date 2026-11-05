package ir.layercloud.cortexdrone.behavior;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.FlightPathfinder;
import ir.layercloud.cortexdrone.navigation.TspRouteOptimizer;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import ir.layercloud.cortexdrone.world.WorldScanner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Spatial excavation planner for box mining, rectangular volume clearing,
 * filtered resource/vein harvesting, and 26-connected neighbor propagation.
 */
public class ExcavationPlanner {
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final WorldScanner worldScanner;

    public ExcavationPlanner(AIDroneManager manager, DroneLogger logger, WorldScanner worldScanner) {
        this.manager = manager;
        this.logger = logger;
        this.worldScanner = worldScanner;
    }

    public static boolean hasPassableNeighbor(World world, int x, int y, int z) {
        return ItemClassifier.isPassableVoxel(world, x + 1, y, z)
            || ItemClassifier.isPassableVoxel(world, x - 1, y, z)
            || ItemClassifier.isPassableVoxel(world, x, y + 1, z)
            || ItemClassifier.isPassableVoxel(world, x, y - 1, z)
            || ItemClassifier.isPassableVoxel(world, x, y, z + 1)
            || ItemClassifier.isPassableVoxel(world, x, y, z - 1);
    }

    public int startBoxMiningTask(DroneController ctrl, EntityPlayerMP player, String anchor, int widthX, int heightY, int lengthZ) {
        Drone d = ctrl.drone;
        World world = d.field_70170_p;
        if (ctrl.currentMineTarget != null) {
            world.func_147443_d(d.func_145782_y(), ctrl.currentMineTarget[0], ctrl.currentMineTarget[1], ctrl.currentMineTarget[2], -1);
        }
        int wx = Math.min(13, Math.max(1, widthX));
        int hy = Math.min(13, Math.max(1, heightY));
        int lz = Math.min(20, Math.max(1, lengthZ));

        ctrl.mineFilter = "box_" + wx + "x" + hy + "x" + lz;
        ctrl.mineExactQueueOnly = true;
        ctrl.replantAfterHarvest = false;
        ctrl.returnToOwnerAfterMine = false;
        ctrl.mineQueue.clear();
        ctrl.currentMineTarget = null;
        ctrl.currentMineStandoff = null;
        ctrl.mineProgressTicks = 0;
        ctrl.minedCount = 0;

        int px = MathHelper.func_76128_c(player != null ? player.field_70165_t : d.field_70165_t);
        int py = MathHelper.func_76128_c(player != null ? player.field_70163_u : d.field_70163_u);
        int pz = MathHelper.func_76128_c(player != null ? player.field_70161_v : d.field_70161_v);

        String anc = anchor != null ? anchor.toLowerCase() : "under_player";
        if (anc.contains("front") || anc.contains("tunnel")) {
            float yaw = player != null ? player.field_70177_z : 0.0f;
            int facing = MathHelper.func_76128_c((double) (yaw * 4.0f / 360.0f) + 0.5D) & 3;
            int dirX = facing == 1 ? -1 : (facing == 3 ? 1 : 0);
            int dirZ = facing == 0 ? 1 : (facing == 2 ? -1 : 0);
            int perpX = -dirZ;
            int perpZ = dirX;
            int halfW = wx / 2;
            for (int step = 1; step <= lz; step++) {
                for (int y = py + hy - 1; y >= py; y--) {
                    for (int w = -halfW; w < -halfW + wx; w++) {
                        int bx = px + dirX * step + perpX * w;
                        int bz = pz + dirZ * step + perpZ * w;
                        if (ItemClassifier.isMineableBlock(world, bx, y, bz)) {
                            ctrl.mineQueue.addLast(new int[]{bx, y, bz});
                        }
                    }
                }
            }
        } else {
            int topY = anc.contains("around") ? (py + hy - 1) : (py - 1);
            int halfX = wx / 2;
            int halfZ = lz / 2;
            for (int y = topY; y >= topY - hy + 1; y--) {
                for (int dx = -halfX; dx < -halfX + wx; dx++) {
                    for (int dz = -halfZ; dz < -halfZ + lz; dz++) {
                        int bx = px + dx;
                        int bz = pz + dz;
                        if (ItemClassifier.isMineableBlock(world, bx, y, bz)) {
                            ctrl.mineQueue.addLast(new int[]{bx, y, bz});
                        }
                    }
                }
            }
        }

        if (!ctrl.mineQueue.isEmpty()) {
            this.manager.setDroneMode(ctrl, Mode.MINE, String.format("Box mine %dx%dx%d (%s), %d blocks", wx, hy, lz, anc, ctrl.mineQueue.size()));
            d.lightColor_$eq(0x00FF66);
            d.statusText_$eq("Mining\n" + wx + "x" + hy + "x" + lz);
        }
        return ctrl.mineQueue.size();
    }

    public int startRectMiningTask(DroneController ctrl, EntityPlayerMP player, int x1, int y1, int z1, int x2, int y2, int z2, String filter) {
        final Drone d = ctrl.drone;
        final World world = d.field_70170_p;
        if (ctrl.currentMineTarget != null) {
            world.func_147443_d(d.func_145782_y(), ctrl.currentMineTarget[0], ctrl.currentMineTarget[1], ctrl.currentMineTarget[2], -1);
        }
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.max(2, Math.min(y1, y2)), maxY = Math.min(254, Math.max(y1, y2));
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        String f = (filter == null || filter.trim().isEmpty()) ? "all" : filter.trim();
        boolean matchAll = f.equalsIgnoreCase("all") || f.equalsIgnoreCase("any") || f.equals("*");

        ctrl.mineFilter = String.format("rect_%d_%d_%d_to_%d_%d_%d", minX, minY, minZ, maxX, maxY, maxZ);
        ctrl.mineExactQueueOnly = true;
        ctrl.replantAfterHarvest = false;
        ctrl.returnToOwnerAfterMine = false;
        ctrl.mineQueue.clear();
        ctrl.currentMineTarget = null;
        ctrl.currentMineStandoff = null;
        ctrl.mineProgressTicks = 0;
        ctrl.minedCount = 0;

        for (int cx = (minX >> 4); cx <= (maxX >> 4); cx++) {
            for (int cz = (minZ >> 4); cz <= (maxZ >> 4); cz++) {
                FlightPathfinder.ensureChunkLoaded(world, (cx << 4) + 8, (cz << 4) + 8);
            }
        }

        for (int y = maxY; y >= minY; y--) {
            List<int[]> layer = new ArrayList<int[]>();
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (ItemClassifier.isMineableBlock(world, x, y, z)) {
                        if (!matchAll) {
                            Block b = world.func_147439_a(x, y, z);
                            int meta = world.func_72805_g(x, y, z);
                            if (!ItemClassifier.matchesMineFilter(b, meta, f)) continue;
                        }
                        layer.add(new int[]{x, y, z});
                    }
                }
            }
            List<int[]> layerTour = TspRouteOptimizer.optimizeBlockTour(layer, d.field_70165_t, d.field_70163_u, d.field_70161_v);
            for (int[] pos : layerTour) {
                ctrl.mineQueue.addLast(pos);
            }
        }

        if (!ctrl.mineQueue.isEmpty()) {
            this.manager.setDroneMode(ctrl, Mode.MINE, String.format("Rect mine (%d,%d,%d)->(%d,%d,%d), %d blocks",
                minX, minY, minZ, maxX, maxY, maxZ, ctrl.mineQueue.size()));
            d.lightColor_$eq(0x00FF66);
            d.statusText_$eq("Mine Rect\n" + ctrl.mineQueue.size() + " blk");
        }
        return ctrl.mineQueue.size();
    }

    public int startMiningTask(DroneController ctrl, EntityPlayerMP refPlayer, String rawTarget, int maxCount, boolean untilFull) {
        Drone d = ctrl.drone;
        final World world = d.field_70170_p;
        String target = (rawTarget == null || rawTarget.trim().isEmpty())
            ? "ores"
            : ItemClassifier.resolveHeldFilter(refPlayer, rawTarget).trim().toLowerCase();
        if (ctrl.currentMineTarget != null) {
            world.func_147443_d(d.func_145782_y(), ctrl.currentMineTarget[0], ctrl.currentMineTarget[1], ctrl.currentMineTarget[2], -1);
        }
        ctrl.mineFilter = target;
        ctrl.mineExactQueueOnly = false;
        ctrl.replantAfterHarvest = false;
        ctrl.returnToOwnerAfterMine = untilFull || maxCount > 0;
        ctrl.mineUntilFull = untilFull;
        ctrl.mineTargetCount = Math.max(0, maxCount);
        ctrl.mineQueue.clear();
        ctrl.currentMineTarget = null;
        ctrl.currentMineStandoff = null;
        ctrl.mineProgressTicks = 0;
        ctrl.minedCount = 0;

        if (MiningService.isNumericFilter(target)) {
            int size = Math.min(11, Math.max(1, Integer.parseInt(target)));
            return startBoxMiningTask(ctrl, refPlayer, "under_player", size, size, size);
        }

        final double cx = refPlayer != null && refPlayer.field_70170_p == world ? refPlayer.field_70165_t : d.field_70165_t;
        final double cy = refPlayer != null && refPlayer.field_70170_p == world ? refPlayer.field_70163_u : d.field_70163_u;
        final double cz = refPlayer != null && refPlayer.field_70170_p == world ? refPlayer.field_70161_v : d.field_70161_v;
        int ix = MathHelper.func_76128_c(cx);
        int iy = MathHelper.func_76128_c(cy);
        int iz = MathHelper.func_76128_c(cz);

        List<int[]> found = new ArrayList<int[]>();
        int radius = 24;
        int minY = ItemClassifier.isLikelyUndergroundQuery(target) ? 2 : Math.max(2, iy - radius);
        int maxY = Math.min(254, iy + radius);
        for (int x = ix - radius; x <= ix + radius; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = iz - radius; z <= iz + radius; z++) {
                    if (!ItemClassifier.isMineableBlock(world, x, y, z)) continue;
                    Block b = world.func_147439_a(x, y, z);
                    int meta = world.func_72805_g(x, y, z);
                    if (ItemClassifier.matchesMineFilter(b, meta, target)) {
                        found.add(new int[]{x, y, z});
                    }
                }
            }
        }

        if (found.isEmpty()) {
            List<int[]> distant = this.worldScanner.findDistantResourceOrBiome(world, ctrl, cx, cy, cz, target, 1200);
            if (!distant.isEmpty()) {
                found.addAll(distant);
                ctrl.returnToOwnerAfterMine = true;
                int[] first = distant.get(0);
                int distM = (int) Math.round(Math.hypot(first[0] - cx, first[2] - cz));
                if (refPlayer != null) {
                    this.manager.droneSay(refPlayer, ctrl, String.format(
                        "Located %d '%s' block(s) at (%d, %d, %d) [%dm away]! Flying there at high speed to harvest and deliver back to you.",
                        distant.size(), target, first[0], first[1], first[2], distM
                    ));
                }
            }
        }

        Collections.sort(found, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                boolean expA = hasPassableNeighbor(world, a[0], a[1], a[2]);
                boolean expB = hasPassableNeighbor(world, b[0], b[1], b[2]);
                if (expA != expB) {
                    return expA ? -1 : 1;
                }
                double da = (a[0] + 0.5 - cx) * (a[0] + 0.5 - cx)
                    + (a[1] + 0.5 - cy) * (a[1] + 0.5 - cy)
                    + (a[2] + 0.5 - cz) * (a[2] + 0.5 - cz);
                double db = (b[0] + 0.5 - cx) * (b[0] + 0.5 - cx)
                    + (b[1] + 0.5 - cy) * (b[1] + 0.5 - cy)
                    + (b[2] + 0.5 - cz) * (b[2] + 0.5 - cz);
                return Double.compare(da, db);
            }
        });

        int queueCap = maxCount > 0 ? Math.max(64, maxCount * 3) : 256;
        int limit = Math.min(found.size(), queueCap);
        List<int[]> slice = new ArrayList<int[]>(limit);
        for (int i = 0; i < limit; i++) {
            slice.add(found.get(i));
        }
        List<int[]> tour = TspRouteOptimizer.optimizeBlockTour(slice, d.field_70165_t, d.field_70163_u, d.field_70161_v);
        for (int[] pt : tour) {
            ctrl.mineQueue.addLast(pt);
        }
        if (!ctrl.mineQueue.isEmpty()) {
            this.manager.setDroneMode(ctrl, Mode.MINE, "Queued " + ctrl.mineQueue.size() + " blocks for filter '" + target + "'");
            d.lightColor_$eq(0x00FF66);
            d.statusText_$eq("Mining\n" + AIDroneManager.trimStatus(target));
        } else {
            this.logger.log("MINE", ctrl, "No matching blocks found for filter '" + target + "'", "radius=1200m");
        }
        return ctrl.mineQueue.size();
    }

    public static void enqueueMatchingNeighbors(World world, DroneController ctrl, int bx, int by, int bz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    int nx = bx + dx;
                    int ny = by + dy;
                    int nz = bz + dz;
                    if (!ItemClassifier.isMineableBlock(world, nx, ny, nz)) continue;
                    Block b = world.func_147439_a(nx, ny, nz);
                    int meta = world.func_72805_g(nx, ny, nz);
                    if (ItemClassifier.matchesMineFilter(b, meta, ctrl.mineFilter)) {
                        if (FarmingService.isVerticalColumnPlant(world, nx, ny, nz, b) && world.func_147439_a(nx, ny - 1, nz) != b) {
                            continue;
                        }
                        boolean exists = false;
                        for (int[] q : ctrl.mineQueue) {
                            if (q[0] == nx && q[1] == ny && q[2] == nz) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            ctrl.mineQueue.addFirst(new int[]{nx, ny, nz});
                        }
                    }
                }
            }
        }
    }
}
