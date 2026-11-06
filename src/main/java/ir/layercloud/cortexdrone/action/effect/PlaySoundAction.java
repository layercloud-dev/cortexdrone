package ir.layercloud.cortexdrone.action.effect;

import ir.layercloud.cortexdrone.action.Action;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;

/** Single-use command: play a sound or note. */
@RequiredArgsConstructor
final class PlaySoundAction implements Action<EffectContext> {
    private final EffectRequest request;

    @Override
    public void execute(EffectContext context) {
        Drone drone = context.controller().drone;
        String sound = request.getSound();
        if (!sound.contains(".")) sound = "note." + sound;
        float pitch = request.getSemitone() >= 0
            ? (float) Math.pow(2.0, (request.getSemitone() - 12.0) / 12.0)
            : request.getPitch();
        drone.worldObj.playSoundEffect(drone.posX, drone.posY, drone.posZ,
            sound, request.getVolume(), pitch);
        context.result(EffectResult.success("sound", sound, "pitch", pitch));
    }
}
