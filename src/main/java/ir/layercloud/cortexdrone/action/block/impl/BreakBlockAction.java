package ir.layercloud.cortexdrone.action.block.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import lombok.RequiredArgsConstructor;

/** Single-use command: break exactly one explicit block. */
@RequiredArgsConstructor
final class BreakBlockAction implements Action<BlockContext> {
    private final BlockService service;
    private final BlockRequest request;

    @Override
    public void execute(BlockContext context) {
        boolean started = this.service.startSingleBlockTask(
            context.controller(), context.player(),
            this.request.getX(), this.request.getY(), this.request.getZ());
        context.result(BlockResult.breakStarted(started,
            this.request.getX(), this.request.getY(), this.request.getZ()));
        if (started) {
            context.trackBreak(this.request.getX(), this.request.getY(), this.request.getZ());
        }
    }
}
