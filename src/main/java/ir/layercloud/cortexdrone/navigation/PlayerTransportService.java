package ir.layercloud.cortexdrone.navigation;

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
        int bx = MathHelper.func_76128_c(gx);
        int by = MathHelper.func_76128_c(gy);
        int bz = MathHelper.func_76128_c(gz);
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
        int topY = world.func_72976_f(bx, bz);
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
        this.manager.setDroneMode(ctrl, Mode.CARRY, String.format("Carrying %s to (%.1f,%.1f,%.1f)", player.func_70005_c_(), gx, gy, gz));
        d.lightColor_$eq(0x00FFFF);
        d.statusText_$eq("Carrying\n" + AIDroneManager.trimStatus(player.func_70005_c_()));
    }

    public void tickCarryBehavior(DroneController ctrl, EntityPlayerMP owner) {
        Drone d = ctrl.drone;
        World world = d.field_70170_p;
        if (owner == null || owner.field_70170_p != world) {
            if (d.field_70153_n != null) {
                d.field_70153_n.func_70078_a(null);
            }
            this.manager.setDroneMode(ctrl, Mode.STAY, "Owner offline during CARRY");
            return;
        }
        owner.field_70143_R = 0.0f;

        if (owner.field_70154_o != d && d.func_70032_d(owner) > 4.0) {
            this.pathfinder.navigateToward(ctrl, owner.field_70165_t, owner.field_70163_u + 1.5, owner.field_70161_v, 0.14f);
            d.statusText_$eq("To Player\n" + (int) d.func_70032_d(owner) + "m");
            return;
        }

        double dist = d.func_70011_f(ctrl.carryGoalX, ctrl.carryGoalY, ctrl.carryGoalZ);
        if (dist <= 2.8) {
            if (owner.field_70154_o == d) {
                owner.func_70078_a(null);
            }
            double[] safe = findSafeLandingSpot(world, ctrl.carryGoalX, ctrl.carryGoalY, ctrl.carryGoalZ);
            owner.field_70143_R = 0.0f;
            owner.func_70634_a(safe[0], safe[1], safe[2]);
            owner.field_70143_R = 0.0f;
            if (!ctrl.isScriptRunning()) {
                this.manager.droneSay(owner, ctrl, String.format("Safely transported you to (%.0f, %.0f, %.0f)!", safe[0], safe[1], safe[2]));
            }
            this.manager.setDroneMode(ctrl, Mode.FOLLOW, "Completed player transport");
            return;
        }

        if (owner.field_70154_o != d) {
            owner.func_70078_a(d);
        }
        this.pathfinder.navigateToward(ctrl, ctrl.carryGoalX, ctrl.carryGoalY + 1.4, ctrl.carryGoalZ, 0.14f);
        owner.field_70143_R = 0.0f;

        if (owner.field_70154_o != d) {
            double py = ItemClassifier.isPassableVoxel(world, MathHelper.func_76128_c(d.field_70165_t), MathHelper.func_76128_c(d.field_70163_u - 1.2), MathHelper.func_76128_c(d.field_70161_v))
                ? (d.field_70163_u - 1.2)
                : d.field_70163_u;
            owner.field_70159_w = d.field_70159_w;
            owner.field_70181_x = d.field_70181_x;
            owner.field_70179_y = d.field_70179_y;
            if (owner.field_71135_a != null) {
                owner.field_71135_a.func_147359_a(new S12PacketEntityVelocity(owner));
            }
            owner.func_70634_a(d.field_70165_t, py, d.field_70161_v);
        } else if (owner.field_71135_a != null) {
            owner.field_71135_a.func_147359_a(new S12PacketEntityVelocity(d));
        }
        owner.field_70143_R = 0.0f;
        d.lightColor_$eq(0x00FFFF);
        d.statusText_$eq("Airlift " + (int) dist + "m\n" + AIDroneManager.trimStatus(owner.func_70005_c_()));
    }
}
