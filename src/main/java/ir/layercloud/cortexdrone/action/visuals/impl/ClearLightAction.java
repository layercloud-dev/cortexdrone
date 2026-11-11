package ir.layercloud.cortexdrone.action.visuals.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.visuals.VisualsResult;
import ir.layercloud.cortexdrone.action.visuals.VisualsState;
import ir.layercloud.cortexdrone.context.impl.VisualsContext;
import lombok.RequiredArgsConstructor;

/**
 * Command: switch the illumination aura off and restore every voxel it
 * changed. Used by lifecycle cleanup paths (despawn, shutdown, forget).
 */
@RequiredArgsConstructor
public final class ClearLightAction implements Action<VisualsContext> {
    private final LightingAura aura;
    private final VisualsState state;

    @Override
    public void execute(VisualsContext context) {
        this.state.lightAura = false;
        this.aura.clearAura(context.controller(), this.state);
        context.result(VisualsResult.success("active", false));
        context.complete();
    }
}
