package ir.layercloud.cortexdrone.action.interact;

import li.cil.oc.common.entity.Drone;
import lombok.experimental.UtilityClass;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Shared low-level helpers for interaction actions.
 */
@UtilityClass
public final class InteractionSupport {
    public static void syncFakePlayerToDrone(EntityPlayer fakePlayer, Drone drone) {
        if (fakePlayer == null || drone == null) return;
        try {
            fakePlayer.posX = drone.posX;
            fakePlayer.posY = drone.posY;
            fakePlayer.posZ = drone.posZ;
            fakePlayer.dimension = drone.dimension;
            fakePlayer.rotationYaw = drone.rotationYaw;
            fakePlayer.rotationPitch = drone.rotationPitch;
        } catch (Throwable ignored) {
        }
    }
}
