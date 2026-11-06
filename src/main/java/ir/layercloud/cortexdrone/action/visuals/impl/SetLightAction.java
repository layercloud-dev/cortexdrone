package ir.layercloud.cortexdrone.action.visuals.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.visuals.VisualsRequest;
import ir.layercloud.cortexdrone.action.visuals.VisualsResult;
import ir.layercloud.cortexdrone.context.impl.VisualsContext;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.manager.visuals.LightConfig;
import ir.layercloud.cortexdrone.manager.visuals.LightResult;
import ir.layercloud.cortexdrone.manager.visuals.VisualsState;
import ir.layercloud.cortexdrone.manager.visuals.impl.LightingAura;
import ir.layercloud.cortexdrone.manager.visuals.impl.VisualsManager;
import lombok.RequiredArgsConstructor;

/**
 * Command: turn the illumination aura on/off and configure it.
 *
 * <p>When the aura is active this action retains itself and re-applies the light
 * every tick from the action registry. There is no background lighting loop in
 * the runtime pipeline.</p>
 */
@RequiredArgsConstructor
public final class SetLightAction implements Action<VisualsContext> {
    private final VisualsManager manager;
    private final LightingAura aura;
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
        LightResult result = this.aura.configure(
                context.controller(), this.manager.state(context.controller()), config);
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
        VisualsState state = this.manager.state(controller);
        if (!state.autoTorch) {
            context.complete();
            return;
        }
        this.aura.tick(controller, state);
    }
}
