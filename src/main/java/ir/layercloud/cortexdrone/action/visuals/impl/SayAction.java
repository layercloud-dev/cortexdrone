package ir.layercloud.cortexdrone.action.visuals.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.visuals.VisualsRequest;
import ir.layercloud.cortexdrone.action.visuals.DroneChat;
import ir.layercloud.cortexdrone.action.visuals.VisualsResult;
import ir.layercloud.cortexdrone.context.impl.VisualsContext;
import lombok.RequiredArgsConstructor;

/**
 * Single-use command: say one chat message.
 */
@RequiredArgsConstructor
public final class SayAction implements Action<VisualsContext> {
    private final DroneChat chat;
    private final VisualsRequest request;

    @Override
    public void execute(VisualsContext context) {
        this.chat.say(context.player(), context.controller(), request.getMessage());
        context.result(VisualsResult.success());
    }
}
