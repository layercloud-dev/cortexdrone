package ir.layercloud.cortexdrone.action.visuals.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.visuals.*;
import ir.layercloud.cortexdrone.context.impl.VisualsContext;
import lombok.RequiredArgsConstructor;

/**
 * Command: turn the illumination aura on/off and configure it.
 *
 * <p>The action owns the whole light job: state transitions, world light
 * mutation and the per-tick re-apply loop. The manager only creates and retains
 * it.</p>
 */
@RequiredArgsConstructor
public final class SetLightAction implements Action<VisualsContext> {
    private final LightingAura aura;
    private final VisualsState state;
    private final VisualsRequest request;

    @Override
    public void execute(VisualsContext context) {
        LightConfig config = LightConfig.builder()
                .enabled(this.request.getLightEnabled())
                .toggle(this.request.isLightToggle())
                .radius(this.request.getLightRadius())
                .level(this.request.getLightLevel())
                .color(this.request.getLightColor())
                .build();
        LightResult result = this.aura.configure(context.controller(), this.state, config);
        if (!result.isOk()) {
            context.result(VisualsResult.failure(result.getError()));
            return;
        }
        context.result(VisualsResult.success(
                "active", result.isActive(),
                "radius", result.getRadius(),
                "level", result.getLevel(),
                "cells", result.getCells(),
                "torchFree", result.isTorchFree()));
        if (result.isActive()) {
            context.retain();
        }
    }

    @Override
    public void tick(VisualsContext context) {
        DroneController controller = context.controller();
        if (controller == null || controller.drone == null) {
            context.complete();
            return;
        }
        if (!this.state.lightAura) {
            context.complete();
            return;
        }
        this.aura.tick(controller, this.state);
    }
}
