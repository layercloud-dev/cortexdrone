package ir.layercloud.cortexdrone.manager.navigation.impl;

import li.cil.oc.common.entity.Drone;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;

/**
 * Passenger survival guard for navigation.
 *
 * <p>A carried player or mob is 1.8 blocks tall. Clearing a one-block tunnel
 * under them suffocates the passenger, and a low ceiling crushes them, so
 * navigation refuses to dig while a living entity is riding. The caller opens a
 * 2x2 escape shaft with {@code escape_cave} instead.</p>
 *
 * <p>Extracted from {@link NavigationManager} to keep that class inside its
 * modular line budget; the behaviour is two predicates, not a policy.</p>
 */
public final class RiderGuard {
    private RiderGuard() {
    }

    public static boolean isCarrying(Drone drone) {
        return drone != null && drone.riddenByEntity instanceof EntityLivingBase;
    }

    static boolean blocksTunnel(NavigationSession session) {
        return session != null && session.carryingRider;
    }

    static String refusal(String reason) {
        return reason + " (carrying a rider: refusing to tunnel through a one-block hole, "
                + "which would suffocate them; open a 2x2 shaft with escape_cave first)";
    }

    /**
     * A rider is flown in open sky only.
     *
     * <p>The drone hovers 0.65 above a path cell, so a 1.8-block passenger
     * occupies three cells there. A two-high cave corridor that the 0.375-tall
     * drone threads freely pushes the rider's head into stone. Open-sky legs
     * cannot do that. The one exception is the final vertical descent beside a
     * covered landing cell, which is short and ends in a dismount.</p>
     */
    static boolean routeAllowed(Drone drone, double tx, double ty, double tz) {
        if (drone == null || drone.worldObj == null) return false;
        int bx = MathHelper.floor_double(tx);
        int by = MathHelper.floor_double(ty);
        int bz = MathHelper.floor_double(tz);
        if (drone.worldObj.canBlockSeeTheSky(bx, by, bz)
                && drone.worldObj.canBlockSeeTheSky(bx, by + 1, bz)) {
            return true;
        }
        double horizontal = Math.hypot(tx - drone.posX, tz - drone.posZ);
        return horizontal <= 2.5D && Math.abs(ty - drone.posY) <= 6.0D;
    }

    static String routeRefusal(double tx, double ty, double tz) {
        return String.format("carrying a rider: refusing to fly into a covered corridor at "
                + "(%.1f,%.1f,%.1f); a passenger needs open sky because their head is 1.8 blocks "
                + "above the drone", tx, ty, tz);
    }
}
