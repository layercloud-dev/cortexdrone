package ir.layercloud.cortexdrone.action.visuals.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.visuals.VisualsRequest;
import ir.layercloud.cortexdrone.action.visuals.VisualsResult;
import ir.layercloud.cortexdrone.context.impl.VisualsContext;
import lombok.RequiredArgsConstructor;

/** Single-use command: set the drone status text. */
@RequiredArgsConstructor
public final class SetStatusTextAction implements Action<VisualsContext> {
    private final VisualsRequest request;

    @Override
    public void execute(VisualsContext context) {
        context.controller().drone.statusText_$eq(request.getText());
        context.result(VisualsResult.success("text", request.getText()));
    }
}
