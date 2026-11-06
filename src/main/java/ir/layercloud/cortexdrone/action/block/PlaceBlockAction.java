package ir.layercloud.cortexdrone.action.block;

import ir.layercloud.cortexdrone.action.Action;
import lombok.RequiredArgsConstructor;

/** Single-use command: place exactly one explicit block. */
@RequiredArgsConstructor
final class PlaceBlockAction implements Action<BlockContext> {
    private final BlockService service;
    private final BlockRequest request;

    @Override
    public void execute(BlockContext context) {
        boolean placed = this.service.placeBlock(
            context.controller(), context.player(),
            this.request.getX(), this.request.getY(), this.request.getZ(),
            this.request.getFilter() != null ? this.request.getFilter() : "any",
            this.request.getMeta());
        context.result(BlockResult.placed(placed,
            this.request.getFilter(), this.request.getX(), this.request.getY(), this.request.getZ()));
    }
}
