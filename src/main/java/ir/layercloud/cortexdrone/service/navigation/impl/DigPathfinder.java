package ir.layercloud.cortexdrone.service.navigation.impl;

import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Baritone-style dig-aware A*: the route may pass through breakable blocks at a
 * high cost instead of treating every solid voxel as a wall.
 *
 * <p>The no-dig {@link FlightPathfinder} answers "can I fly there?". When the
 * answer is no, this search answers the question a player actually asks:
 * "which rock do I break first to get there?". It is what powers
 * {@code TunnelRecovery} when the straight ray, vertical step and 3x3x3 shell
 * contain nothing useful — for example a drone hovering in a cave pocket whose
 * walls are more than {@link TunnelPlanner#MAX_LOOKAHEAD} blocks away from the
 * goal ray.</p>
 *
 * <p>Costs follow Baritone's {@code MovementHelper.getMiningDurationTicks}:
 * breaking is priced by hardness, liquids are priced by danger, and bedrock,
 * protected tile entities and other unbreakable blocks are walls. The search
 * is bounded by node count and wall-clock time because it runs on the server
 * thread.</p>
 */
public final class DigPathfinder {
    private static final int MAX_EXPANSIONS = 900;
    private static final long BUDGET_NANOS = 2_000_000L;
    /** Routes longer than this are re-planned from the new position anyway. */
    private static final double MAX_RADIUS = 40.0D;
    private static final double BREAK_BASE_COST = 6.0D;
    private static final double FALLING_COST = 18.0D;
    private static final double LAVA_COST = 8.0D;
    private static final double WATER_COST = 2.0D;

    private DigPathfinder() {
    }

    /** One accepted step plus how it was traversed. */
    private static final class Node implements Comparable<Node> {
        final int x;
        final int y;
        final int z;
        final double g;
        final double f;
        final boolean solid;
        final Node parent;

        Node(int x, int y, int z, double g, double f, boolean solid, Node parent) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.g = g;
            this.f = f;
            this.solid = solid;
            this.parent = parent;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(this.f, o.f);
        }
    }

    /** Result of one dig-aware search. */
    public static final class Result {
        /** First block on the route that must be broken, or {@code null}. */
        public final int[] breakBlock;
        public final int expanded;
        public final boolean reachedGoal;

        Result(int[] breakBlock, int expanded, boolean reachedGoal) {
            this.breakBlock = breakBlock;
            this.expanded = expanded;
            this.reachedGoal = reachedGoal;
        }
    }

    /**
     * Finds the first block to break on a dig-aware route from the drone to
     * {@code (gx, gy, gz)}.
     */
    public static Result firstBreak(World world, Drone drone,
                                    double gx, double gy, double gz,
                                    Set<Long> rejected) {
        if (world == null || drone == null) return new Result(null, 0, false);

        int startX = MathHelper.floor_double(drone.posX);
        int startY = MathHelper.floor_double(drone.posY);
        int startZ = MathHelper.floor_double(drone.posZ);
        Clamped goal = clampGoal(startX, startY, startZ, gx, gy, gz);
        if (goal == null) return new Result(null, 0, false);

        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<Long, Double> bestG = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        double h0 = heuristic(startX, startY, startZ, goal.x, goal.y, goal.z);
        Node start = new Node(startX, startY, startZ, 0.0D, h0, false, null);
        open.add(start);
        bestG.put(FlightPathfinder.packBlockPos(startX, startY, startZ), 0.0D);

        Node reached = null;
        Node closest = start;
        int expanded = 0;
        long deadline = System.nanoTime() + BUDGET_NANOS;

        while (!open.isEmpty() && expanded < MAX_EXPANSIONS && System.nanoTime() < deadline) {
            Node cur = open.poll();
            long key = FlightPathfinder.packBlockPos(cur.x, cur.y, cur.z);
            if (!closed.add(key)) continue;
            expanded++;
            if (heuristic(cur.x, cur.y, cur.z, goal.x, goal.y, goal.z)
                    < heuristic(closest.x, closest.y, closest.z, goal.x, goal.y, goal.z)) {
                closest = cur;
            }
            if (cur.x == goal.x && cur.y == goal.y && cur.z == goal.z) {
                reached = cur;
                break;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int nx = cur.x + dx;
                        int ny = cur.y + dy;
                        int nz = cur.z + dz;
                        if (ny <= 2 || ny >= 254) continue;
                        long nKey = FlightPathfinder.packBlockPos(nx, ny, nz);
                        if (closed.contains(nKey)) continue;
                        Step step = classify(world, nx, ny, nz, rejected);
                        if (step == null) continue;
                        // The heuristic is Euclidean, so the step cost must not
                        // be cheaper than the distance it covers or A* would
                        // under-price diagonals and stop being admissible.
                        int manhattan = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        double traversal = manhattan == 1 ? 1.0D
                                : (manhattan == 2 ? 1.414D : 1.732D);
                        double ng = cur.g + step.cost * traversal;
                        Double previous = bestG.get(nKey);
                        if (previous != null && ng >= previous) continue;
                        bestG.put(nKey, ng);
                        double f = ng + heuristic(nx, ny, nz, goal.x, goal.y, goal.z);
                        open.add(new Node(nx, ny, nz, ng, f, step.solid, cur));
                    }
                }
            }
        }

        int[] firstBreak = firstSolid(reached != null ? reached : closest);
        return new Result(firstBreak, expanded, reached != null);
    }

    /** A new open flight waypoint on the dig-aware route, or {@code null}. */
    public static double[] firstOpenStep(World world, Drone drone,
                                         double gx, double gy, double gz) {
        Result result = firstBreak(world, drone, gx, gy, gz, new HashSet<>());
        if (result.breakBlock == null) return null;
        return FlightPathfinder.findBlockStandoff(world, drone,
                result.breakBlock[0], result.breakBlock[1], result.breakBlock[2]);
    }

    private static final class Step {        final double cost;
        final boolean solid;

        Step(double cost, boolean solid) {
            this.cost = cost;
            this.solid = solid;
        }
    }

    /**
     * Classifies one candidate voxel: open (liquid included, priced), breakable
     * solid (priced by hardness) or a wall.
     *
     * <p>A node is only enterable when its <em>head</em> voxel is already open.
     * A route that needs two stacked breaks is not returned because recovery
     * re-plans after every single block: this keeps each returned step
     * immediately executable instead of producing an unreachable plan.</p>
     */
    private static Step classify(World world, int x, int y, int z, Set<Long> rejected) {
        if (!world.blockExists(x, y, z)) return null;
        if (!ItemClassifier.isPassableVoxel(world, x, y + 1, z)) return null;
        Block block = world.getBlock(x, y, z);
        if (block == null || block == net.minecraft.init.Blocks.air) return new Step(1.0D, false);
        if (ItemClassifier.isDangerousLiquid(block)) return new Step(LAVA_COST, false);
        if (ItemClassifier.isLiquidBlock(block)) return new Step(WATER_COST, false);
        if (!ItemClassifier.isMineableBlock(world, x, y, z)) return null;
        if (block.getBlockHardness(world, x, y, z) > 50.0F) return null;
        if (rejected != null && rejected.contains(FlightPathfinder.packBlockPos(x, y, z))) {
            return null;
        }
        double cost = BREAK_BASE_COST + block.getBlockHardness(world, x, y, z) * 2.0D;
        if (block instanceof net.minecraft.block.BlockFalling) cost += FALLING_COST;
        return new Step(cost, true);
    }

    private static int[] firstSolid(Node node) {
        // Walk back from the goal: the deepest solid node is the last one broken
        // before the goal, the shallowest solid node is the first one in the
        // direction of travel and the one the drone can reach right now.
        int[] first = null;
        for (Node cur = node; cur != null && cur.parent != null; cur = cur.parent) {
            if (cur.solid) first = new int[]{cur.x, cur.y, cur.z};
        }
        return first;
    }

    private static Clamped clampGoal(int sx, int sy, int sz, double gx, double gy, double gz) {
        double dx = gx - (sx + 0.5D);
        double dy = gy - (sy + 0.5D);
        double dz = gz - (sz + 0.5D);
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > MAX_RADIUS) {
            double scale = MAX_RADIUS / distance;
            dx *= scale;
            dy *= scale;
            dz *= scale;
        }
        int x = MathHelper.floor_double(sx + 0.5D + dx);
        int y = MathHelper.floor_double(sy + 0.5D + dy);
        int z = MathHelper.floor_double(sz + 0.5D + dz);
        return new Clamped(x, Math.max(3, Math.min(253, y)), z);
    }

    private static double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        double dx = gx - x;
        double dy = gy - y;
        double dz = gz - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static final class Clamped {
        final int x;
        final int y;
        final int z;

        Clamped(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
