package ir.layercloud.cortexdrone.action.interact;

import ir.layercloud.cortexdrone.action.Action;
import lombok.RequiredArgsConstructor;

/** Single-use command: use one held item or attack one entity. */
@RequiredArgsConstructor
final class UseItemAction implements Action<InteractionContext> {
    private final InteractionService service;
    private final InteractionRequest request;

    @Override
    public void execute(InteractionContext context) {
        if (request.hasEntity()) context.targetEntity(request.getEntityId());
        context.result(this.service.useItem(context, request));
    }
}
