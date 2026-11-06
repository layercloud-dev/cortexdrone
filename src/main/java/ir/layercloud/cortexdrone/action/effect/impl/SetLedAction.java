package ir.layercloud.cortexdrone.action.effect.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.effect.EffectRequest;
import ir.layercloud.cortexdrone.action.effect.EffectResult;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.LedEffectEngine;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;

/** Single-use command: change the drone LED color or effect. */
@RequiredArgsConstructor
final class SetLedAction implements Action<EffectContext> {
    private final EffectRequest request;

    @Override
    public void execute(EffectContext context) {
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
                drone.lightColor_$eq(controller.resolveEffectiveLedColor(drone.lightColor()));
            }
            context.result(EffectResult.success(
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
            context.result(EffectResult.success("color", color(drone)));
            return;
        }

        if (request.getHue() != null) {
            float saturation = request.getSaturation() != null ? request.getSaturation() : 1.0f;
            float brightness = request.getBrightness() != null ? request.getBrightness() : 1.0f;
            int packed = DroneController.hsvToRgbInt(request.getHue(), saturation, brightness);
            controller.customLedColor = packed;
            controller.ledEffect = "NONE";
            drone.lightColor_$eq(packed);
            context.result(EffectResult.success("color", color(drone)));
            return;
        }

        if (request.getColorText() != null) {
            String raw = request.getColorText().trim();
            String upper = raw.toUpperCase();
            if (LedEffectEngine.isEffectName(upper)) {
                controller.ledEffect = upper;
                drone.lightColor_$eq(controller.resolveEffectiveLedColor(drone.lightColor()));
                context.result(EffectResult.success(
                    "effect", controller.ledEffect,
                    "color", color(drone)));
                return;
            }
            if ("DEFAULT".equals(upper) || "AUTO".equals(upper) || "RESET".equals(upper)) {
                controller.ledEffect = "NONE";
                controller.customLedColor = null;
                context.result(EffectResult.success("effect", "NONE"));
                return;
            }
            Integer parsed = DroneController.parseNamedOrHexColor(raw);
            int packed = parsed != null ? parsed.intValue() : 0x00FFFF;
            controller.customLedColor = packed;
            controller.ledEffect = "NONE";
            drone.lightColor_$eq(packed);
            context.result(EffectResult.success("color", color(drone)));
            return;
        }

        if (request.getColorValue() != null) {
            int packed = request.getColorValue() & 0xFFFFFF;
            controller.customLedColor = packed;
            controller.ledEffect = "NONE";
            drone.lightColor_$eq(packed);
            context.result(EffectResult.success("color", color(drone)));
            return;
        }

        context.result(EffectResult.success());
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static String color(Drone drone) {
        return String.format("0x%06X", drone.lightColor() & 0xFFFFFF);
    }
}
