package ir.layercloud.cortexdrone.action.visuals.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.visuals.VisualsRequest;
import ir.layercloud.cortexdrone.action.visuals.VisualsResult;
import ir.layercloud.cortexdrone.context.impl.VisualsContext;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.manager.visuals.VisualsState;
import ir.layercloud.cortexdrone.manager.visuals.impl.LedEffects;
import ir.layercloud.cortexdrone.manager.visuals.impl.VisualsManager;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * Command: change the drone LED color or effect.
 *
 * <p>A static color is one-shot. An animated effect retains this action and the
 * action itself advances the animation, so the runtime pipeline does not run a
 * hidden LED loop for every drone.</p>
 */
@RequiredArgsConstructor
public final class SetLedAction implements Action<VisualsContext> {
    private final VisualsManager manager;
    private final VisualsRequest request;

    @Override
    public void execute(VisualsContext context) {
        DroneController controller = context.controller();
        Drone drone = controller.drone;
        VisualsState state = this.manager.state(controller);
        String effect = request.getEffect();
        if (effect != null) {
            String normalized = effect.trim().toUpperCase();
            if ("OFF".equals(normalized) || "NONE".equals(normalized)
                    || "DEFAULT".equals(normalized) || "AUTO".equals(normalized)
                    || "RESET".equals(normalized)) {
                state.ledEffect = "NONE";
                state.customLedColor = null;
            } else {
                state.ledEffect = normalized;
                drone.lightColor_$eq(LedEffects.resolveEffectiveLedColor(
                        state.ledEffect, state.ledEffectTick,
                        state.customLedColor, drone.lightColor()));
            }
            context.result(VisualsResult.success(
                    "effect", state.ledEffect,
                    "color", color(drone)));
            retainIfAnimated(context, state);
            return;
        }

        if (request.getRed() != null && request.getGreen() != null && request.getBlue() != null) {
            int red = clamp(request.getRed());
            int green = clamp(request.getGreen());
            int blue = clamp(request.getBlue());
            int packed = (red << 16) | (green << 8) | blue;
            state.customLedColor = packed;
            state.ledEffect = "NONE";
            drone.lightColor_$eq(packed);
            context.result(VisualsResult.success("color", color(drone)));
            return;
        }

        if (request.getHue() != null) {
            float saturation = request.getSaturation() != null ? request.getSaturation() : 1.0f;
            float brightness = request.getBrightness() != null ? request.getBrightness() : 1.0f;
            int packed = LedEffects.hsvToRgbInt(request.getHue(), saturation, brightness);
            state.customLedColor = packed;
            state.ledEffect = "NONE";
            drone.lightColor_$eq(packed);
            context.result(VisualsResult.success("color", color(drone)));
            return;
        }

        if (request.getColorText() != null) {
            String raw = request.getColorText().trim();
            String upper = raw.toUpperCase();
            if (LedEffects.isEffectName(upper)) {
                state.ledEffect = upper;
                drone.lightColor_$eq(LedEffects.resolveEffectiveLedColor(
                        state.ledEffect, state.ledEffectTick,
                        state.customLedColor, drone.lightColor()));
                context.result(VisualsResult.success(
                        "effect", state.ledEffect,
                        "color", color(drone)));
                retainIfAnimated(context, state);
                return;
            }
            if ("DEFAULT".equals(upper) || "AUTO".equals(upper) || "RESET".equals(upper)) {
                state.ledEffect = "NONE";
                state.customLedColor = null;
                context.result(VisualsResult.success("effect", "NONE"));
                return;
            }
            Integer parsed = LedEffects.parseNamedOrHexColor(raw);
            int packed = parsed != null ? parsed : 0x00FFFF;
            state.customLedColor = packed;
            state.ledEffect = "NONE";
            drone.lightColor_$eq(packed);
            context.result(VisualsResult.success("color", color(drone)));
            return;
        }

        if (request.getColorValue() != null) {
            int packed = request.getColorValue() & 0xFFFFFF;
            state.customLedColor = packed;
            state.ledEffect = "NONE";
            drone.lightColor_$eq(packed);
            context.result(VisualsResult.success("color", color(drone)));
            return;
        }

        context.result(VisualsResult.success());
    }

    @Override
    public void tick(VisualsContext context) {
        DroneController controller = context.controller();
        if (controller == null || controller.drone == null || controller.drone.isDead) {
            context.complete();
            return;
        }
        VisualsState state = this.manager.state(controller);
        if (!LedEffects.isAnimated(state.ledEffect)) {
            context.complete();
            return;
        }
        state.ledEffectTick++;
        Drone drone = controller.drone;
        drone.lightColor_$eq(LedEffects.resolveEffectiveLedColor(
                state.ledEffect, state.ledEffectTick,
                state.customLedColor, drone.lightColor()));
        World world = drone.worldObj;
        if ("DISCO".equalsIgnoreCase(state.ledEffect)
                && state.ledEffectTick % 6 == 0 && world instanceof WorldServer) {
            ((WorldServer) world).func_147487_a(
                    "note", drone.posX, drone.posY + 0.45, drone.posZ, 2, 0.25, 0.25, 0.25, 0.5);
        }
    }

    private static void retainIfAnimated(VisualsContext context, VisualsState state) {
        if (LedEffects.isAnimated(state.ledEffect)) {
            state.ledEffectTick = 0;
            context.retain();
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static String color(Drone drone) {
        return String.format("0x%06X", drone.lightColor() & 0xFFFFFF);
    }
}
