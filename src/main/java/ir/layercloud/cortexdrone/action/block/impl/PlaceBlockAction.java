package ir.layercloud.cortexdrone.action.block.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.context.impl.BlockContext;
import ir.layercloud.cortexdrone.manager.building.impl.BuildingManager;
import lombok.RequiredArgsConstructor;

/**
 * Single-use command: place exactly one explicit block.
 */
@RequiredArgsConstructor
public final class PlaceBlockAction implements Action<BlockContext> {
    private final BuildingManager buildingManager;
    private final BlockRequest request;

    @Override
    public void execute(BlockContext context) {
        String filter = this.request.getFilter() != null ? this.request.getFilter() : "any";
        boolean placed = this.buildingManager.placeBlock(
                context.controller(), context.player(),
                this.request.getX(), this.request.getY(), this.request.getZ(),
                filter, this.request.getMeta());
        context.result(BlockResult.placed(placed, filter,
                this.request.getX(), this.request.getY(), this.request.getZ()));
    }
}
