package ir.layercloud.cortexdrone.action.effect.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.effect.EffectRequest;
import ir.layercloud.cortexdrone.action.effect.EffectResult;
import ir.layercloud.cortexdrone.context.action.effect.impl.EffectContext;
import lombok.RequiredArgsConstructor;

/** Single-use command: say one chat message. */
@RequiredArgsConstructor
public final class SayAction implements Action<EffectContext> {
    private final AIDroneManager manager;
    private final EffectRequest request;

    @Override
    public void execute(EffectContext context) {
        this.manager.droneSay(context.player(), context.controller(), request.getMessage());
        context.result(EffectResult.success());
    }
}
