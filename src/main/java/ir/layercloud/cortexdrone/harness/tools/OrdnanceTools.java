package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.tool.*;
import ir.layercloud.cortexdrone.service.combat.TntDrop;
import ir.layercloud.cortexdrone.service.combat.TntDropLimits;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.ProtectedZone;
import ir.layercloud.cortexdrone.service.world.ProtectionZones;
import ir.layercloud.cortexdrone.service.world.WorldBounds;
import li.cil.oc.common.entity.Drone;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.List;

/**
 * Released ordnance: drop live TNT from the drone onto a ground target.
 *
 * <p>This is a bomb release, not a placed block. The charge is spawned as a
 * fused {@code EntityTNTPrimed} under the drone, falls, and detonates on its
 * own fuse while the drone is already outside the blast.</p>
 *
 * <p>The tool refuses rather than guesses: no TNT in the drone, a rider that
 * would be inside the blast, a protected zone, a blocked fall column, or a
 * release point inside the damage radius all produce a typed error with the
 * safe hover point when one exists.</p>
 */
@SuppressWarnings("unused")
public final class OrdnanceTools implements ToolProvider {
    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("tnt_drop")
                    .description("Drop live TNT from the drone onto a ground target, like a bomber "
                            + "release. The charge falls from the drone's current position and explodes "
                            + "on its fuse. x/y/z is the IMPACT block (aim at the ground, not at air). "
                            + "The drone must already be at least "
                            + (int) TntDropLimits.SAFE_DISTANCE + " blocks from the impact and directly "
                            + "above it, otherwise the result is too_close/off_target with the exact "
                            + "hover point to goto. Refuses when a player is riding, the impact is in a "
                            + "protected zone, the fall column is blocked, or the drone has no TNT "
                            + "(take_from_player filter 'tnt' first). Returns dropped count, fuse seconds, "
                            + "blast radius and an escape point; fly away immediately after the call.")
                    .parameters(ToolSchema.object()
                            .requiredInt("x", "Impact block X (the ground the charge should hit).")
                            .requiredInt("y", "Impact block Y.")
                            .requiredInt("z", "Impact block Z.")
                            .optionalInt("count", "Charges to release, " + TntDropLimits.MIN_COUNT
                                            + ".." + TntDropLimits.MAX_COUNT + ".",
                                    TntDropLimits.MIN_COUNT, TntDropLimits.MAX_COUNT,
                                    TntDropLimits.DEFAULT_COUNT)
                            .optionalInt("fuse_ticks", "Fuse in ticks, " + TntDropLimits.MIN_FUSE_TICKS
                                            + ".." + TntDropLimits.MAX_FUSE_TICKS + " (20 ticks = 1 s).",
                                    TntDropLimits.MIN_FUSE_TICKS, TntDropLimits.MAX_FUSE_TICKS,
                                    TntDropLimits.DEFAULT_FUSE_TICKS)
                            .optionalNumber("spread", "Horizontal spread between several charges, 0.."
                                    + (int) TntDropLimits.MAX_SPREAD + " blocks.", 0.0D,
                                    TntDropLimits.MAX_SPREAD, 0.0D))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.ACTION_MS)
                    .capabilities(ToolCapability.MUTATES_WORLD)
                    .build()
    );

    private final AIDroneManager manager;

    public OrdnanceTools(AIDroneManager manager) {
        this.manager = manager;
    }

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    @Override
    public void register(ToolRegistry registry) {
        registry.bind("tnt_drop", getClass().getSimpleName(),
                (context, arguments) -> drop(context.controller(), arguments));
    }

    private JsonObject drop(DroneController controller, JsonObject args) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return ToolJson.error("no_drone", "No active drone or world.",
                    "Spawn or link a drone before dropping TNT.", false);
        }
        Drone drone = controller.drone;
        World world = drone.worldObj;
        int x = args.get("x").getAsInt();
        int y = args.get("y").getAsInt();
        int z = args.get("z").getAsInt();
        if (!WorldBounds.inWorld(y)) {
            return ToolJson.error("invalid_target", "Impact Y " + y + " is outside the world.",
                    "Aim at a real ground block.", true);
        }
        if (drone.riddenByEntity != null) {
            return ToolJson.error("rider_onboard",
                    "A passenger is riding the drone; releasing TNT would put them inside the blast.",
                    "Land and unpick the passenger before any ordnance call.", false);
        }
        if (world.isAirBlock(x, y, z)) {
            return ToolJson.error("invalid_target", "Impact (" + x + "," + y + "," + z
                            + ") is air; a charge dropped there falls past it.",
                    "Aim at the ground block under the target, or use get_top_y for the surface.", true);
        }
        ProtectedZone zone = ProtectionZones.zoneAt(world, x, y, z);
        if (zone != null) {
            return ToolJson.error("protected_zone", "Impact (" + x + "," + y + "," + z
                            + ") is inside '" + zone.getLabel() + "', which the player marked as "
                            + "no-break. An explosion destroys blocks, so this is refused outright.",
                    "Bomb outside the protected area, or ask the player to remove the zone.", false);
        }

        int count = TntDropLimits.clampCount(ToolArgs.integer(args, "count",
                TntDropLimits.DEFAULT_COUNT));
        int fuse = TntDropLimits.clampFuse(ToolArgs.integer(args, "fuse_ticks",
                TntDropLimits.DEFAULT_FUSE_TICKS));
        double spread = TntDropLimits.clampSpread(ToolArgs.decimal(args, "spread", 0.0D));

        double impactX = x + 0.5D;
        double impactY = y + 1.0D;
        double impactZ = z + 0.5D;
        double distance = drone.getDistance(impactX, impactY, impactZ);
        double horizontal = Math.sqrt(
                (drone.posX - impactX) * (drone.posX - impactX)
                        + (drone.posZ - impactZ) * (drone.posZ - impactZ));
        if (distance < TntDropLimits.SAFE_DISTANCE) {
            JsonObject tooClose = ToolJson.error("too_close",
                    "The drone is " + round(distance) + " blocks from the impact; the blast reaches "
                            + (int) TntDropLimits.SAFE_DISTANCE + ".",
                    "Fly to the hover point above the target, then call tnt_drop again.", true);
            tooClose.add("hover", point(impactX, impactY + TntDropLimits.SAFE_DISTANCE + 1.0D, impactZ));
            tooClose.addProperty("distance", round(distance));
            return tooClose;
        }
        if (horizontal > 2.0D) {
            JsonObject offTarget = ToolJson.error("off_target",
                    "The drone is " + round(horizontal) + " blocks horizontally from the impact; "
                            + "a released charge falls straight down and would miss.",
                    "Move directly above the impact block first.", true);
            offTarget.add("hover", point(impactX, impactY + TntDropLimits.SAFE_DISTANCE + 1.0D, impactZ));
            offTarget.addProperty("horizontal_offset", round(horizontal));
            return offTarget;
        }
        if (drone.posY - impactY > TntDropLimits.MAX_FALL) {
            return ToolJson.error("too_high",
                    "The drop is " + round(drone.posY - impactY) + " blocks above the impact; the "
                            + "charge drifts on the way down. Maximum is " + TntDropLimits.MAX_FALL + ".",
                    "Descend to a hover below " + (y + 1 + TntDropLimits.MAX_FALL) + " and retry.", true);
        }
        String blocked = blockedColumn(world, x, z, (int) Math.ceil(impactY),
                (int) Math.floor(drone.posY));
        if (blocked != null) {
            return ToolJson.error("drop_path_blocked",
                    "The fall column above (" + x + "," + y + "," + z + ") is blocked at " + blocked
                            + "; the charge would land on the obstruction.",
                    "Pick a target with open sky above it, or break the obstruction first.", true);
        }

        IInventory inventory = drone.mainInventory();
        int slot = TntDrop.findTntSlot(inventory);
        if (slot < 0) {
            JsonObject missing = ToolJson.error("no_tnt", "The drone inventory holds no TNT.",
                    "Call take_from_player({filter:'tnt', count:n}) when the player has charges, or "
                            + "craft TNT (sand + gunpowder) before the run.", true);
            missing.addProperty("slots", inventory != null ? inventory.getSizeInventory() : 0);
            return missing;
        }

        double releaseY = drone.posY - 0.5D;
        TntDrop.Release release = TntDrop.release(world, inventory, slot, impactX, releaseY, impactZ,
                count, fuse, spread, drone.player());
        if (release.dropped <= 0) {
            return ToolJson.error("no_tnt", "The drone inventory holds no usable TNT charge.",
                    "Take TNT from the player or craft it before the run.", true);
        }
        JsonObject result = ToolJson.ok();
        result.addProperty("dropped", release.dropped);
        result.addProperty("fuse_ticks", release.fuse);
        result.addProperty("detonation_in_seconds", round(TntDropLimits.fuseSeconds(release.fuse)));
        result.addProperty("blast_radius", TntDropLimits.BLAST_BLOCK_RADIUS);
        result.addProperty("safe_distance", (int) TntDropLimits.SAFE_DISTANCE);
        result.add("impact", point(impactX, impactY, impactZ));
        result.add("release", point(release.x, releaseY, release.z));
        result.add("escape_to", point(escapeX(impactX, drone.posX), drone.posY,
                escapeZ(impactZ, drone.posZ)));
        result.addProperty("remaining_tnt", TntDrop.countTnt(inventory));
        result.addProperty("hint", "The fuse is burning. Fly to escape_to now, wait "
                + round(TntDropLimits.fuseSeconds(release.fuse) + 1.0D)
                + "s, then inspect the crater.");
        if (this.manager != null) {
            this.manager.getLogger().log("ORDNANCE", controller,
                    "Released " + release.dropped + " TNT charge(s) onto (" + x + "," + y + "," + z + ")",
                    "fuse=" + release.fuse + " slot=" + release.slot
                            + " remaining=" + TntDrop.countTnt(inventory));
        }
        return result;
    }

    /**
     * First non-passable voxel in the fall column, or {@code null}.
     */
    private static String blockedColumn(World world, int x, int z, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) {
            if (!ItemClassifier.isPassableVoxel(world, x, y, z)) {
                return "(" + x + "," + y + "," + z + ")";
            }
        }
        return null;
    }

    private static double escapeX(double impactX, double droneX) {
        return droneX >= impactX ? impactX + TntDropLimits.SAFE_DISTANCE + 5.0D
                : impactX - TntDropLimits.SAFE_DISTANCE - 5.0D;
    }

    private static double escapeZ(double impactZ, double droneZ) {
        return droneZ >= impactZ ? impactZ + TntDropLimits.SAFE_DISTANCE + 5.0D
                : impactZ - TntDropLimits.SAFE_DISTANCE - 5.0D;
    }

    private static JsonObject point(double x, double y, double z) {
        JsonObject point = new JsonObject();
        point.addProperty("x", round(x));
        point.addProperty("y", round(y));
        point.addProperty("z", round(z));
        return point;
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
