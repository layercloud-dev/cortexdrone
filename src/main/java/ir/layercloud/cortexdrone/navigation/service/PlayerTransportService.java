package ir.layercloud.cortexdrone.navigation.service;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Smooth entity-mounted & velocity-synchronized player airlift service (Mode.CARRY).
 */
@RequiredArgsConstructor
public class PlayerTransportService {
    private final AIDroneManager manager;
    private final FlightPathfinder pathfinder;

    public static double[] findSafeLandingSpot(World world, double gx, double gy, double gz) {
        int bx = MathHelper.floor_double(gx);
        int by = MathHelper.floor_double(gy);
        int bz = MathHelper.floor_double(gz);
        for (int r = 0; r <= 3; r++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        int x = bx + dx, y = by + dy, z = bz + dz;
                        if (y <= 2 || y >= 252) continue;
                        if (!ItemClassifier.isPassableVoxel(world, x, y - 1, z)
                            && ItemClassifier.isPassableVoxel(world, x, y, z)
                            && ItemClassifier.isPassableVoxel(world, x, y + 1, z)) {
                            return new double[]{x + 0.5, (double) y, z + 0.5};
                        }
                    }
                }
            }
        }
        int topY = world.getHeightValue(bx, bz);
        return new double[]{bx + 0.5, Math.max(gy, (double) topY), bz + 0.5};
    }

    public void startCarryPlayerTask(DroneController ctrl, EntityPlayerMP player, double gx, double gy, double gz) {
        if (ctrl == null || ctrl.drone == null || player == null) return;
        Drone d = ctrl.drone;
        ctrl.carryGoalX = gx;
        ctrl.carryGoalY = gy;
        ctrl.carryGoalZ = gz;
        ctrl.goalX = gx;
        ctrl.goalY = gy;
        ctrl.goalZ = gz;
        this.manager.setDroneMode(ctrl, Mode.CARRY, String.format("Carrying %s to (%.1f,%.1f,%.1f)", player.getCommandSenderName(), gx, gy, gz));
        d.lightColor_$eq(0x00FFFF);
        d.statusText_$eq("Carrying\n" + AIDroneManager.trimStatus(player.getCommandSenderName()));
    }

    public void tickCarryBehavior(DroneController ctrl, EntityPlayerMP owner) {
        Drone d = ctrl.drone;
        World world = d.worldObj;
        if (owner == null || owner.worldObj != world) {
            if (d.riddenByEntity != null) {
                d.riddenByEntity.mountEntity(null);
            }
            this.manager.setDroneMode(ctrl, Mode.STAY, "Owner offline during CARRY");
            return;
        }
        owner.fallDistance = 0.0f;

        if (owner.ridingEntity != d && d.getDistanceToEntity(owner) > 4.0) {
            this.pathfinder.navigateToward(ctrl, owner.posX, owner.posY + 1.5, owner.posZ, 0.14f);
            d.statusText_$eq("To Player\n" + (int) d.getDistanceToEntity(owner) + "m");
            return;
        }

        double dist = d.getDistance(ctrl.carryGoalX, ctrl.carryGoalY, ctrl.carryGoalZ);
        if (dist <= 2.8) {
            if (owner.ridingEntity == d) {
                owner.mountEntity(null);
            }
            double[] safe = findSafeLandingSpot(world, ctrl.carryGoalX, ctrl.carryGoalY, ctrl.carryGoalZ);
            owner.fallDistance = 0.0f;
            owner.setPositionAndUpdate(safe[0], safe[1], safe[2]);
            owner.fallDistance = 0.0f;
            if (!ctrl.isScriptRunning()) {
                this.manager.droneSay(owner, ctrl, String.format("Safely transported you to (%.0f, %.0f, %.0f)!", safe[0], safe[1], safe[2]));
            }
            this.manager.setDroneMode(ctrl, Mode.FOLLOW, "Completed player transport");
            return;
        }

        if (owner.ridingEntity != d) {
            owner.mountEntity(d);
        }
        this.pathfinder.navigateToward(ctrl, ctrl.carryGoalX, ctrl.carryGoalY + 1.4, ctrl.carryGoalZ, 0.14f);
        owner.fallDistance = 0.0f;

        if (owner.ridingEntity != d) {
            double py = ItemClassifier.isPassableVoxel(world, MathHelper.floor_double(d.posX), MathHelper.floor_double(d.posY - 1.2), MathHelper.floor_double(d.posZ))
                ? (d.posY - 1.2)
                : d.posY;
            owner.motionX = d.motionX;
            owner.motionY = d.motionY;
            owner.motionZ = d.motionZ;
            if (owner.playerNetServerHandler != null) {
                owner.playerNetServerHandler.sendPacket(new S12PacketEntityVelocity(owner));
            }
            owner.setPositionAndUpdate(d.posX, py, d.posZ);
        } else if (owner.playerNetServerHandler != null) {
            owner.playerNetServerHandler.sendPacket(new S12PacketEntityVelocity(d));
        }
        owner.fallDistance = 0.0f;
        d.lightColor_$eq(0x00FFFF);
        d.statusText_$eq("Airlift " + (int) dist + "m\n" + AIDroneManager.trimStatus(owner.getCommandSenderName()));
    }
}
