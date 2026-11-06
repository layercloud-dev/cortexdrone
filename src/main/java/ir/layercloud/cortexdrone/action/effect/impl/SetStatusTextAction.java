package ir.layercloud.cortexdrone.action.effect.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.effect.EffectRequest;
import ir.layercloud.cortexdrone.action.effect.EffectResult;
import lombok.RequiredArgsConstructor;

/** Single-use command: set the drone status text. */
@RequiredArgsConstructor
final class SetStatusTextAction implements Action<EffectContext> {
    private final EffectRequest request;

    @Override
    public void execute(EffectContext context) {
        context.controller().drone.statusText_$eq(request.getText());
        context.result(EffectResult.success("text", request.getText()));
    }
}
