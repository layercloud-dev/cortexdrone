package ir.layercloud.cortexdrone.action.interact.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.interact.InteractionRequest;
import lombok.RequiredArgsConstructor;

/** Single-use command: activate exactly one block. */
@RequiredArgsConstructor
final class InteractBlockAction implements Action<InteractionContext> {
    private final InteractionService service;
    private final InteractionRequest request;

    @Override
    public void execute(InteractionContext context) {
        context.targetBlock(request.getX(), request.getY(), request.getZ());
        context.result(this.service.interactBlock(context, request));
    }
}
