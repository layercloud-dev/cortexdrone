package ir.layercloud.cortexdrone.action.visuals.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.visuals.VisualsRequest;
import ir.layercloud.cortexdrone.action.visuals.VisualsResult;
import ir.layercloud.cortexdrone.context.impl.VisualsContext;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.service.visuals.LedEffectEngine;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;

/**
 * Single-use command: change the drone LED color or effect.
 */
@RequiredArgsConstructor
public final class SetLedAction implements Action<VisualsContext> {
    private final VisualsRequest request;

    @Override
    public void execute(VisualsContext context) {
        DroneController controller = context.controller();
        Drone drone = controller.drone;
        String effect = request.getEffect();
        if (effect != null) {
            String normalized = effect.trim().toUpperCase();
            if ("OFF".equals(normalized) || "NONE".equals(normalized)
                    || "DEFAULT".equals(normalized) || "AUTO".equals(normalized)
                    || "RESET".equals(normalized)) {
                controller.ledEffect = "NONE";
                controller.customLedColor = null;
            } else {
                controller.ledEffect = normalized;
                drone.lightColor_$eq(LedEffectEngine.resolveEffectiveLedColor(
                        controller.ledEffect, controller.ledEffectTick,
                        controller.customLedColor, drone.lightColor()));
            }
            context.result(VisualsResult.success(
                    "effect", controller.ledEffect,
                    "color", color(drone)));
            return;
        }

        if (request.getRed() != null && request.getGreen() != null && request.getBlue() != null) {
            int red = clamp(request.getRed());
            int green = clamp(request.getGreen());
            int blue = clamp(request.getBlue());
            int packed = (red << 16) | (green << 8) | blue;
            controller.customLedColor = packed;
            controller.ledEffect = "NONE";
            drone.lightColor_$eq(packed);
            context.result(VisualsResult.success("color", color(drone)));
            return;
        }

        if (request.getHue() != null) {
            float saturation = request.getSaturation() != null ? request.getSaturation() : 1.0f;
            float brightness = request.getBrightness() != null ? request.getBrightness() : 1.0f;
            int packed = LedEffectEngine.hsvToRgbInt(request.getHue(), saturation, brightness);
            controller.customLedColor = packed;
            controller.ledEffect = "NONE";
            drone.lightColor_$eq(packed);
            context.result(VisualsResult.success("color", color(drone)));
            return;
        }

        if (request.getColorText() != null) {
            String raw = request.getColorText().trim();
            String upper = raw.toUpperCase();
            if (LedEffectEngine.isEffectName(upper)) {
                controller.ledEffect = upper;
                drone.lightColor_$eq(LedEffectEngine.resolveEffectiveLedColor(
                        controller.ledEffect, controller.ledEffectTick,
                        controller.customLedColor, drone.lightColor()));
                context.result(VisualsResult.success(
                        "effect", controller.ledEffect,
                        "color", color(drone)));
                return;
            }
            if ("DEFAULT".equals(upper) || "AUTO".equals(upper) || "RESET".equals(upper)) {
                controller.ledEffect = "NONE";
                controller.customLedColor = null;
                context.result(VisualsResult.success("effect", "NONE"));
                return;
            }
            Integer parsed = LedEffectEngine.parseNamedOrHexColor(raw);
            int packed = parsed != null ? parsed : 0x00FFFF;
            controller.customLedColor = packed;
            controller.ledEffect = "NONE";
            drone.lightColor_$eq(packed);
            context.result(VisualsResult.success("color", color(drone)));
            return;
        }

        if (request.getColorValue() != null) {
            int packed = request.getColorValue() & 0xFFFFFF;
            controller.customLedColor = packed;
            controller.ledEffect = "NONE";
            drone.lightColor_$eq(packed);
            context.result(VisualsResult.success("color", color(drone)));
            return;
        }

        context.result(VisualsResult.success());
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static String color(Drone drone) {
        return String.format("0x%06X", drone.lightColor() & 0xFFFFFF);
    }
}
