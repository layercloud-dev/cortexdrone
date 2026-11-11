package ir.layercloud.cortexdrone.service.world;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.DroneController;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

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
    // ------------------------------------------------------------------
    // Site search
    // ------------------------------------------------------------------

    /**
     * Scans around the player's crosshair look point or facing direction to find
     * the flattest, unobstructed WxD build pad.
     */
    public JsonObject findBestBuildSite(World world, EntityPlayerMP player, DroneController ctrl,
                                        int width, int depth, int maxRadius) {
        return BuildSiteFinder.find(world, player, ctrl, width, depth, maxRadius, "drone");
    }

    public JsonObject findBestBuildSite(World world, EntityPlayerMP player, DroneController ctrl,
                                        int width, int depth, int maxRadius, String anchor) {
        return BuildSiteFinder.find(world, player, ctrl, width, depth, maxRadius, anchor);
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
        BlueprintSpec requirements = BlueprintSpec.parse(this, world, player, ctrl, spec);
        int effectiveHeight = requirements.style.equals("tower")
                ? Math.max(requirements.wallHeight,
                Math.min(WorldBounds.MAX_PLAN_SIDE,
                        Math.max(requirements.width, requirements.depth) + 2))
                : requirements.wallHeight;
        int roofBaseY = requirements.floorY + effectiveHeight + 1;
        boolean flat = isFlat(requirements.style);
        boolean bridge = requirements.style.equals("bridge");
        int maxY = flat || bridge ? requirements.floorY
                : roofBaseY + roofLayers(requirements.width, requirements.depth,
                requirements.roofStyle);

        StructurePlan plan = new StructurePlan(requirements.x1, requirements.z1,
                requirements.x2, requirements.z2, requirements.floorY, maxY,
                requirements.doorSide, requirements.style, requirements.roofStyle);

        clearFootprint(world, plan, requirements, maxY);
        layFoundation(plan, requirements);
        if (flat) {
            addRailings(plan, requirements.x1, requirements.z1, requirements.x2,
                    requirements.z2, requirements.floorY + 1, requirements.wallBlock,
                    requirements.doorSide);
            return plan;
        }
        if (bridge) {
            addPiers(plan, requirements.x1, requirements.z1, requirements.x2,
                    requirements.z2, requirements.floorY, requirements.foundationBlock);
            addRailings(plan, requirements.x1, requirements.z1, requirements.x2,
                    requirements.z2, requirements.floorY + 1, requirements.wallBlock,
                    requirements.doorSide);
            return plan;
        }
        buildWalls(plan, requirements, effectiveHeight);
        if (requirements.style.equals("wall") || requirements.style.equals("fence")) return plan;
        addRoof(plan, requirements.x1, requirements.z1, requirements.x2, requirements.z2,
                roofBaseY, requirements.roofStyle, requirements.roofBlock);
        return plan;
    }

    /**
     * A platform/pad/floor has no walls or roof.
     */
    private static boolean isFlat(String style) {
        return style.equals("platform") || style.equals("pad") || style.equals("floor");
    }

    /**
     * Pass 1: clear everything from the roof down to the floor, top-down.
     */
    private static void clearFootprint(World world, StructurePlan plan,
                                       BlueprintSpec spec, int maxY) {
        for (int y = maxY; y >= spec.floorY; y--) {
            for (int x = spec.x1; x <= spec.x2; x++) {
                for (int z = spec.z1; z <= spec.z2; z++) {
                    if (ItemClassifier.isProtectedFixture(world, x, y, z)) continue;
                    if (ItemClassifier.isMineableBlock(world, x, y, z)) plan.addClearBlock(x, y, z);
                }
            }
        }
    }

    /**
     * Pass 2: foundation below the floor, then the floor layer itself.
     */
    private static void layFoundation(StructurePlan plan, BlueprintSpec spec) {
        for (int x = spec.x1; x <= spec.x2; x++) {
            for (int z = spec.z1; z <= spec.z2; z++) {
                for (int fy = spec.floorY - 1; fy >= spec.floorY - 2; fy--) {
                    boolean perimeter = x == spec.x1 || x == spec.x2
                            || z == spec.z1 || z == spec.z2;
                    if (perimeter || fy == spec.floorY - 1) {
                        plan.addTask(x, fy, z, spec.foundationBlock, -1, false,
                                StructurePlan.Phase.FOUNDATION);
                    }
                }
                plan.addTask(x, spec.floorY, z, spec.floorBlock, -1, true,
                        StructurePlan.Phase.FLOOR);
            }
        }
    }

    /**
     * Pass 3: corners, door opening, windows and wall fills.
     */
    private static void buildWalls(StructurePlan plan, BlueprintSpec spec, int effectiveHeight) {
        int midX = (spec.x1 + spec.x2) / 2;
        int midZ = (spec.z1 + spec.z2) / 2;
        for (int y = spec.floorY + 1; y <= spec.floorY + effectiveHeight; y++) {
            int relativeY = y - spec.floorY;
            for (int x = spec.x1; x <= spec.x2; x++) {
                for (int z = spec.z1; z <= spec.z2; z++) {
                    boolean perimeter = x == spec.x1 || x == spec.x2
                            || z == spec.z1 || z == spec.z2;
                    if (!perimeter) continue;
                    boolean corner = (x == spec.x1 || x == spec.x2)
                            && (z == spec.z1 || z == spec.z2);
                    if (corner) {
                        plan.addTask(x, y, z, spec.cornerBlock, -1, true,
                                StructurePlan.Phase.CORNER);
                        continue;
                    }
                    boolean door = isDoorCell(x, z, midX, midZ, spec.doorSide,
                            spec.x1, spec.x2, spec.z1, spec.z2) && relativeY <= 2;
                    if (door) {
                        if (relativeY == 1) plan.setDoor(x, y, z);
                        continue;
                    }
                    boolean window = spec.includeWindows && effectiveHeight >= 3 && relativeY == 2
                            && isWindowCell(x, z, midX, midZ, spec.doorSide,
                            spec.x1, spec.x2, spec.z1, spec.z2, spec.width, spec.depth);
                    plan.addTask(x, y, z, window ? spec.windowBlock : spec.wallBlock, -1, true,
                            window ? StructurePlan.Phase.WINDOW : StructurePlan.Phase.WALL);
                }
            }
        }
    }

    /**
     * Test seam: the roof builder is pure geometry and must be unit-testable.
     */
    static void addRoofForTest(StructurePlan plan, int x1, int z1, int x2, int z2,
                               int roofBaseY, String roofStyle, String roofBlock) {
        addRoof(plan, x1, z1, x2, z2, roofBaseY, roofStyle, roofBlock);
    }

    /**
     * Test seam for the layer count that drives {@code maxY}.
     */
    static int roofLayersForTest(int width, int depth, String roofStyle) {
        return roofLayers(width, depth, roofStyle);
    }

    private static void addRoof(StructurePlan plan, int x1, int z1, int x2, int z2,
                                int roofBaseY, String roofStyle, String roofBlock) {
        RoofBuilder.build(plan, x1, z1, x2, z2, roofBaseY, roofStyle, roofBlock);
    }

    private static int roofLayers(int width, int depth, String roofStyle) {
        return RoofBuilder.layerCount(width, depth, roofStyle);
    }

    private static void addPiers(StructurePlan plan, int x1, int z1, int x2, int z2,
                                 int floorY, String block) {
        int[][] corners = new int[][]{
                {x1, z1}, {x2, z1}, {x1, z2}, {x2, z2}
        };
        for (int[] corner : corners) {
            for (int y = floorY - 1; y >= floorY - 3; y--) {
                plan.addTask(corner[0], y, corner[1], block, -1, false, StructurePlan.Phase.FOUNDATION);
            }
        }
        // Mid-span support for long bridges.
        int midX = (x1 + x2) / 2;
        int midZ = (z1 + z2) / 2;
        if (x2 - x1 > 8) {
            for (int y = floorY - 1; y >= floorY - 3; y--) {
                plan.addTask(midX, y, z1, block, -1, false, StructurePlan.Phase.FOUNDATION);
                plan.addTask(midX, y, z2, block, -1, false, StructurePlan.Phase.FOUNDATION);
            }
        }
        if (z2 - z1 > 8) {
            for (int y = floorY - 1; y >= floorY - 3; y--) {
                plan.addTask(x1, y, midZ, block, -1, false, StructurePlan.Phase.FOUNDATION);
                plan.addTask(x2, y, midZ, block, -1, false, StructurePlan.Phase.FOUNDATION);
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
                plan.addTask(x, y, z, block, -1, true, StructurePlan.Phase.RAILING);
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

    static int highestGround(World world, int x1, int z1, int x2, int z2, int fallback) {
        int highest = fallback;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                int y = SiteSurvey.groundY(world, x, z, fallback);
                if (y > highest) highest = y;
            }
        }
        return highest;
    }

}
