package ir.layercloud.cortexdrone.action.visuals.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.visuals.VisualsRequest;
import ir.layercloud.cortexdrone.action.visuals.VisualsResult;
import ir.layercloud.cortexdrone.context.visuals.impl.VisualsContext;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;

/** Single-use command: play a sound or note. */
@RequiredArgsConstructor
public final class PlaySoundAction implements Action<VisualsContext> {
    private final VisualsRequest request;

    @Override
    public void execute(VisualsContext context) {
        Drone drone = context.controller().drone;
        String sound = request.getSound();
        if (!sound.contains(".")) sound = "note." + sound;
        float pitch = request.getSemitone() >= 0
            ? (float) Math.pow(2.0, (request.getSemitone() - 12.0) / 12.0)
            : request.getPitch();
        drone.worldObj.playSoundEffect(drone.posX, drone.posY, drone.posZ,
            sound, request.getVolume(), pitch);
        context.result(VisualsResult.success("sound", sound, "pitch", pitch));
    }
}
