package ir.layercloud.cortexdrone.service.world;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.DroneController;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.Locale;

/**
 * The parsed, resolved inputs to a structure plan.
 *
 * <p>Extracted from {@code StructureArchitect.generateStructureBlueprint}. Three
 * footprint forms are accepted, in priority order: an explicit surveyed box
 * ({@code x1/z1/x2/z2}), a centre ({@code x/z}), or a fresh site search. Resolving
 * them here keeps the emitter a linear sequence of build phases.</p>
 */
final class BlueprintSpec {
    /** Default footprint edge. */
    private static final int DEFAULT_SIDE = 5;
    /** Default wall height. */
    private static final int DEFAULT_HEIGHT = 3;
    /** Default material when the caller names none. */
    private static final String DEFAULT_BLOCK = "cobblestone";
    /** Default window material. */
    private static final String DEFAULT_WINDOW = "glass";
    /** Search radius used when no footprint is given. */
    private static final int SITE_SEARCH_RADIUS = 22;

    final int x1;
    final int z1;
    final int x2;
    final int z2;
    final int width;
    final int depth;
    final int wallHeight;
    final int floorY;
    final String doorSide;
    final String style;
    final String roofStyle;
    final boolean includeWindows;
    final String floorBlock;
    final String wallBlock;
    final String cornerBlock;
    final String roofBlock;
    final String windowBlock;
    final String foundationBlock;

    private BlueprintSpec(JsonObject spec, int x1, int z1, int x2, int z2, int width, int depth,
                          int wallHeight, int floorY, String doorSide, String style,
                          String roofStyle, boolean includeWindows, String fallback) {
        this.x1 = x1;
        this.z1 = z1;
        this.x2 = x2;
        this.z2 = z2;
        this.width = width;
        this.depth = depth;
        this.wallHeight = wallHeight;
        this.floorY = floorY;
        this.doorSide = doorSide;
        this.style = style;
        this.roofStyle = roofStyle;
        this.includeWindows = includeWindows;
        this.floorBlock = string(spec, "floor", fallback);
        this.wallBlock = string(spec, "walls", fallback);
        this.cornerBlock = string(spec, "corners", this.wallBlock);
        this.roofBlock = string(spec, "roof", this.cornerBlock);
        this.windowBlock = string(spec, "windowBlock", DEFAULT_WINDOW);
        this.foundationBlock = string(spec, "foundation", DEFAULT_BLOCK);
    }

    /** Parses and resolves one plan request. */
    static BlueprintSpec parse(StructureArchitect architect, World world, EntityPlayerMP player,
                               DroneController ctrl, JsonObject spec) {
        int width = spec.has("width") ? clampSide(spec.get("width").getAsInt()) : DEFAULT_SIDE;
        int depth = spec.has("depth") ? clampSide(spec.get("depth").getAsInt()) : width;
        int wallHeight = spec.has("height") ? clampHeight(spec.get("height").getAsInt()) : DEFAULT_HEIGHT;
        String style = lower(spec, "style", "house");
        String roofStyle = lower(spec, "roofStyle", "flat");
        boolean windows = !spec.has("windows") || spec.get("windows").getAsBoolean();
        String fallback = spec.has("block") ? spec.get("block").getAsString() : DEFAULT_BLOCK;

        Footprint footprint = footprint(architect, world, player, ctrl, spec, width, depth);
        String doorSide = spec.has("doorSide") ? lower(spec, "doorSide", footprint.defaultDoorSide)
                : footprint.defaultDoorSide;
        return new BlueprintSpec(spec, footprint.x1, footprint.z1, footprint.x2, footprint.z2,
                footprint.width, footprint.depth, wallHeight, footprint.floorY, doorSide,
                style, roofStyle, windows, fallback);
    }

    /** Where the structure goes, and at what floor level. */
    private static final class Footprint {
        int x1;
        int z1;
        int x2;
        int z2;
        int width;
        int depth;
        int floorY;
        String defaultDoorSide;
    }

    private static Footprint footprint(StructureArchitect architect, World world,
                                       EntityPlayerMP player, DroneController ctrl,
                                       JsonObject spec, int width, int depth) {
        Footprint result = new Footprint();
        result.width = width;
        result.depth = depth;
        if (spec.has("x1") && spec.has("z1") && spec.has("x2") && spec.has("z2")) {
            explicitBox(architect, world, player, ctrl, spec, result);
        } else if (spec.has("x") && spec.has("z")) {
            centre(architect, world, player, ctrl, spec, result);
        } else {
            searched(architect, world, player, ctrl, spec, result);
        }
        return result;
    }

    /** An explicit surveyed box; the box wins over width/depth. */
    private static void explicitBox(StructureArchitect architect, World world,
                                    EntityPlayerMP player, DroneController ctrl,
                                    JsonObject spec, Footprint out) {
        out.x1 = Math.min(spec.get("x1").getAsInt(), spec.get("x2").getAsInt());
        out.x2 = Math.max(spec.get("x1").getAsInt(), spec.get("x2").getAsInt());
        out.z1 = Math.min(spec.get("z1").getAsInt(), spec.get("z2").getAsInt());
        out.z2 = Math.max(spec.get("z1").getAsInt(), spec.get("z2").getAsInt());
        out.width = out.x2 - out.x1 + 1;
        out.depth = out.z2 - out.z1 + 1;
        int refY = referenceY(world, player, ctrl);
        out.floorY = explicitFloorY(spec);
        if (out.floorY == Integer.MIN_VALUE) {
            out.floorY = SiteSurvey.groundY(world, (out.x1 + out.x2) / 2,
                    (out.z1 + out.z2) / 2, refY);
            out.floorY = StructureArchitect.highestGround(world, out.x1, out.z1, out.x2, out.z2,
                    out.floorY);
        }
        out.defaultDoorSide = StructureArchitect.resolveDoorSideTowardPlayer(
                playerX(player, ctrl), playerZ(player, ctrl),
                (out.x1 + out.x2) * 0.5, (out.z1 + out.z2) * 0.5);
    }

    /** A centre point plus the requested width/depth. */
    private static void centre(StructureArchitect architect, World world,
                               EntityPlayerMP player, DroneController ctrl,
                               JsonObject spec, Footprint out) {
        int cx = spec.get("x").getAsInt();
        int cz = spec.get("z").getAsInt();
        out.x1 = cx - out.width / 2;
        out.z1 = cz - out.depth / 2;
        out.x2 = out.x1 + out.width - 1;
        out.z2 = out.z1 + out.depth - 1;
        int refY = referenceY(world, player, ctrl);
        out.floorY = explicitFloorY(spec);
        if (out.floorY == Integer.MIN_VALUE) {
            out.floorY = StructureArchitect.highestGround(world, out.x1, out.z1, out.x2, out.z2,
                    SiteSurvey.groundY(world, cx, cz, refY));
        }
        out.defaultDoorSide = StructureArchitect.resolveDoorSideTowardPlayer(
                playerX(player, ctrl), playerZ(player, ctrl), cx, cz);
    }

    /** No footprint given: search for a flat pad. */
    private static void searched(StructureArchitect architect, World world,
                                 EntityPlayerMP player, DroneController ctrl,
                                 JsonObject spec, Footprint out) {
        JsonObject site = architect.findBestBuildSite(world, player, ctrl,
                out.width, out.depth, SITE_SEARCH_RADIUS);
        out.x1 = site.get("x1").getAsInt();
        out.z1 = site.get("z1").getAsInt();
        out.x2 = site.get("x2").getAsInt();
        out.z2 = site.get("z2").getAsInt();
        out.floorY = site.get("floorY").getAsInt();
        out.defaultDoorSide = site.get("doorSide").getAsString();
    }

    /** An explicit {@code y}/{@code floorY}, or {@link Integer#MIN_VALUE}. */
    private static int explicitFloorY(JsonObject spec) {
        if (spec.has("y")) return spec.get("y").getAsInt();
        if (spec.has("floorY")) return spec.get("floorY").getAsInt();
        return Integer.MIN_VALUE;
    }

    private static int referenceY(World world, EntityPlayerMP player, DroneController ctrl) {
        return player != null && player.worldObj == world
                ? MathHelper.floor_double(player.posY)
                : MathHelper.floor_double(ctrl.drone.posY);
    }

    private static double playerX(EntityPlayerMP player, DroneController ctrl) {
        return player != null ? player.posX : ctrl.drone.posX;
    }

    private static double playerZ(EntityPlayerMP player, DroneController ctrl) {
        return player != null ? player.posZ : ctrl.drone.posZ;
    }

    private static int clampSide(int side) {
        return Math.max(WorldBounds.MIN_PLAN_SIDE, Math.min(WorldBounds.MAX_PLAN_SIDE, side));
    }

    private static int clampHeight(int height) {
        return Math.max(WorldBounds.MIN_PLAN_HEIGHT,
                Math.min(WorldBounds.MAX_PLAN_HEIGHT, height));
    }

    private static String lower(JsonObject spec, String key, String fallback) {
        return spec.has(key) ? spec.get(key).getAsString().toLowerCase(Locale.ROOT) : fallback;
    }

    private static String string(JsonObject spec, String key, String fallback) {
        if (spec == null || !spec.has(key) || spec.get(key).isJsonNull()) return fallback;
        String value = spec.get(key).getAsString();
        return value != null && !value.trim().isEmpty() ? value.trim() : fallback;
    }
}
