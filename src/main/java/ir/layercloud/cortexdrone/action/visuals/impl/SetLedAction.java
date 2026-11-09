package ir.layercloud.cortexdrone.action.visuals.impl;

import ir.layercloud.cortexdrone.service.visuals.VisualLimits;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.visuals.VisualsRequest;
import ir.layercloud.cortexdrone.action.visuals.VisualsResult;
import ir.layercloud.cortexdrone.context.impl.VisualsContext;
import ir.layercloud.cortexdrone.DroneController;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;

/**
 * Single-use command: set the drone LED to one static colour.
 *
 * <p>There are no named or animated effects in Java. A skill or inline Code
 * Mode loop composes animations by calling this primitive repeatedly.</p>
 */
@RequiredArgsConstructor
public final class SetLedAction implements Action<VisualsContext> {
    private final VisualsRequest request;

    @Override
    public void execute(VisualsContext context) {
        DroneController controller = context.controller();
        Drone drone = controller.drone;
        Integer color = this.request.getColorValue();
        if (color == null && this.request.getRed() != null
                && this.request.getGreen() != null && this.request.getBlue() != null) {
            color = (clamp(this.request.getRed()) << 16)
                    | (clamp(this.request.getGreen()) << 8)
                    | clamp(this.request.getBlue());
        }
        if (color == null) {
            context.result(VisualsResult.failure(
                    "set_led requires color (int, hex string, #RRGGBB or [r,g,b]) or r/g/b"));
            return;
        }
        int packed = color & 0xFFFFFF;
        drone.lightColor_$eq(packed);
        context.result(VisualsResult.success(
                "color", String.format("0x%06X", packed)));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(VisualLimits.MAX_COLOR, value));
    }
}
