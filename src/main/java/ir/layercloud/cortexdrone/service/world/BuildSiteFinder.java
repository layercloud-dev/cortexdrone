package ir.layercloud.cortexdrone.service.world;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonObject;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Flat-pad search for a build site.
 *
 * <p>Extracted from {@link StructureArchitect}. The search scores candidate pads
 * by slope, liquid, distance and elevation, and now reports failure explicitly
 * when no candidate passes, instead of returning the requested centre as if it
 * had been inspected.</p>
 */
public final class BuildSiteFinder {
    /**
     * Smallest and largest footprint the search accepts.
     *
     * <p>These are the plan limits the tool schema advertises; a wider internal
     * bound silently accepted pads the schema would have rejected.</p>
     */
    static final int MIN_SIDE = WorldBounds.MIN_PLAN_SIDE;
    static final int MAX_SIDE = WorldBounds.MAX_PLAN_SIDE;
    /**
     * Radius range the {@code find_build_site} schema advertises and this search
     * honours. There is no narrower internal range; these are the scan policy
     * values, not a second copy of them.
     */
    public static final int SCHEMA_MIN_RADIUS = ScanLimits.MIN_BUILD_SITE_RADIUS;
    public static final int SCHEMA_MAX_RADIUS = ScanLimits.MAX_RADIUS;
    /** Radius used when the caller names none. */
    public static final int DEFAULT_RADIUS = ScanLimits.DEFAULT_RADIUS;

    /**
     * Clamps a requested search radius to the range the schema advertises.
     *
     * <p>This must use {@link #SCHEMA_MIN_RADIUS}/{@link #SCHEMA_MAX_RADIUS}, not
     * a narrower internal bound: clamping to 8..40 while promising 4..64 made a
     * request for 64 silently search less, with no way to tell a cap from an
     * empty area.</p>
     */
    static int clampRadius(int requested) {
        return Math.max(SCHEMA_MIN_RADIUS, Math.min(SCHEMA_MAX_RADIUS, requested));
    }
    /** How far the player's look point may bias the preferred centre. */
    private static final double LOOK_BIAS_DISTANCE = 32.0;
    /** A look hit closer than this is the player's own block, not a target. */
    private static final double MIN_LOOK_DISTANCE = 4.0;
    /** Minimum clear distance between the pad and the player. */
    static final int PLAYER_CLEARANCE = 2;

    // Score weights: slope dominates, then liquid, then distance/elevation.
    private static final double SLOPE_WEIGHT = 12.0;
    private static final int LIQUID_PENALTY = 25;
    private static final double DISTANCE_WEIGHT = 1.4;
    private static final double ELEVATION_WEIGHT = 2.5;
    /** Candidate stride: every other block keeps the search cheap. */
    private static final int CANDIDATE_STRIDE = 2;

    private BuildSiteFinder() {
    }

    static JsonObject find(World world, EntityPlayerMP player, DroneController ctrl,
                           int width, int depth, int maxRadius, String anchor) {
        int w = Math.max(MIN_SIDE, Math.min(MAX_SIDE, width));
        int d = Math.max(MIN_SIDE, Math.min(MAX_SIDE, depth));
        int radius = clampRadius(maxRadius);

        boolean usePlayer = "player".equalsIgnoreCase(anchor) && player != null;
        double px = usePlayer ? player.posX : ctrl.drone.posX;
        double py = usePlayer ? player.posY : ctrl.drone.posY;
        double pz = usePlayer ? player.posZ : ctrl.drone.posZ;
        int refY = MathHelper.floor_double(py);

        int[] preferred = preferredCentre(world, player, usePlayer, px, pz, w, d);
        int prefCenterX = preferred[0];
        int prefCenterZ = preferred[1];

        Candidate best = null;
        for (int dx = -radius; dx <= radius; dx += CANDIDATE_STRIDE) {
            for (int dz = -radius; dz <= radius; dz += CANDIDATE_STRIDE) {
                int cx = prefCenterX + dx;
                int cz = prefCenterZ + dz;
                int x1 = cx - w / 2;
                int z1 = cz - d / 2;
                int x2 = x1 + w - 1;
                int z2 = z1 + d - 1;
                if (overlapsPlayer(px, pz, x1, z1, x2, z2)) continue;
                Candidate candidate = score(world, x1, z1, x2, z2, refY,
                        cx, cz, prefCenterX, prefCenterZ);
                if (candidate == null) continue;
                if (best == null || candidate.score < best.score) best = candidate;
            }
        }

        if (best == null) {
            return error("no_build_site",
                    "No flat, clear " + w + "x" + d + " pad within " + radius
                            + " blocks of the requested centre.",
                    "Survey the area with survey_build_site, move to flatter ground, "
                            + "or pass an explicit x/z for the site.");
        }
        return toJson(best, w, d, radius, maxRadius, px, pz);
    }

    /** Bias the preferred centre toward where the player is looking. */
    private static int[] preferredCentre(World world, EntityPlayerMP player, boolean usePlayer,
                                         double px, double pz, int w, int d) {
        int centerX = MathHelper.floor_double(px);
        int centerZ = MathHelper.floor_double(pz);
        if (!usePlayer) return new int[]{centerX, centerZ};
        JsonObject look = WorldScanner.raycastPlayerLook(world, player, LOOK_BIAS_DISTANCE);
        if (look != null && look.has("x") && look.has("z")
                && look.get("distance").getAsDouble() >= MIN_LOOK_DISTANCE) {
            return new int[]{look.get("x").getAsInt(), look.get("z").getAsInt()};
        }
        double yaw = Math.toRadians(player.rotationYaw);
        double reach = Math.max(w, d) / 2.0 + 4.0;
        return new int[]{
                MathHelper.floor_double(px - Math.sin(yaw) * reach),
                MathHelper.floor_double(pz + Math.cos(yaw) * reach)
        };
    }

    /** True when the pad is closer than {@link #PLAYER_CLEARANCE} to the player. */
    static boolean overlapsPlayer(double px, double pz, int x1, int z1, int x2, int z2) {
        return px >= x1 - PLAYER_CLEARANCE && px <= x2 + PLAYER_CLEARANCE
                && pz >= z1 - PLAYER_CLEARANCE && pz <= z2 + PLAYER_CLEARANCE;
    }

    /** Scores one pad, or returns {@code null} when it is unusable. */
    private static Candidate score(World world, int x1, int z1, int x2, int z2, int refY,
                                   int cx, int cz, int prefCenterX, int prefCenterZ) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int sumY = 0;
        int count = 0;
        int liquidPenalty = 0;

        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                int groundY = SiteSurvey.groundY(world, x, z, refY);
                if (ItemClassifier.isProtectedFixture(world, x, groundY, z)
                        || ItemClassifier.isProtectedFixture(world, x, groundY + 1, z)) {
                    return null;
                }
                if (ItemClassifier.isLiquidBlock(world.getBlock(x, groundY, z))) liquidPenalty += LIQUID_PENALTY;
                if (groundY < minY) minY = groundY;
                if (groundY > maxY) maxY = groundY;
                sumY += groundY;
                count++;
            }
        }
        if (count == 0) return null;

        int slope = maxY - minY;
        int averageY = Math.round((float) sumY / (float) count);
        double distance = Math.hypot(cx - prefCenterX, cz - prefCenterZ);
        double elevation = Math.abs(averageY - (refY - 1));
        double score = slope * SLOPE_WEIGHT + liquidPenalty
                + distance * DISTANCE_WEIGHT + elevation * ELEVATION_WEIGHT;
        return new Candidate(x1, z1, x2, z2, averageY, minY, maxY, score);
    }

    private static JsonObject toJson(Candidate best, int w, int d, int radius,
                                     int requestedRadius, double px, double pz) {
        double centerX = (best.x1 + best.x2) * 0.5;
        double centerZ = (best.z1 + best.z2) * 0.5;
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("x1", best.x1);
        result.addProperty("z1", best.z1);
        result.addProperty("x2", best.x2);
        result.addProperty("z2", best.z2);
        result.addProperty("width", w);
        result.addProperty("depth", d);
        result.addProperty("centerX", MathHelper.floor_double(centerX));
        result.addProperty("centerZ", MathHelper.floor_double(centerZ));
        result.addProperty("groundY", best.averageY);
        result.addProperty("floorY", best.maxY);
        result.addProperty("wallBaseY", best.maxY + 1);
        result.addProperty("minTopY", best.minY);
        result.addProperty("maxTopY", best.maxY);
        result.addProperty("slope", best.maxY - best.minY);
        result.addProperty("radius", radius);
        result.addProperty("requested_radius", requestedRadius);
        result.addProperty("radius_was_capped", radius != requestedRadius);
        result.addProperty("doorSide", StructureArchitect.resolveDoorSideTowardPlayer(
                px, pz, centerX, centerZ));
        return result;
    }

    private static JsonObject error(String code, String message, String hint) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("code", code);
        result.addProperty("error", message);
        result.addProperty("hint", hint);
        result.addProperty("retryable", true);
        return result;
    }

    /** One scored candidate pad. */
    private static final class Candidate {
        final int x1;
        final int z1;
        final int x2;
        final int z2;
        final int averageY;
        final int minY;
        final int maxY;
        final double score;

        Candidate(int x1, int z1, int x2, int z2, int averageY, int minY, int maxY, double score) {
            this.x1 = x1;
            this.z1 = z1;
            this.x2 = x2;
            this.z2 = z2;
            this.averageY = averageY;
            this.minY = minY;
            this.maxY = maxY;
            this.score = score;
        }
    }
}
