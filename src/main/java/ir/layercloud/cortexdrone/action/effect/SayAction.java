package ir.layercloud.cortexdrone.action.effect;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import lombok.RequiredArgsConstructor;

/** Single-use command: say one chat message. */
@RequiredArgsConstructor
final class SayAction implements Action<EffectContext> {
    private final AIDroneManager manager;
    private final EffectRequest request;

    @Override
    public void execute(EffectContext context) {
        this.manager.droneSay(context.player(), context.controller(), request.getMessage());
        context.result(EffectResult.success());
    }
}
