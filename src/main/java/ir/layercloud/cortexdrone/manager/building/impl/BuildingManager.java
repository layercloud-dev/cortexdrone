package ir.layercloud.cortexdrone.manager.building.impl;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.action.block.impl.BreakBlockAction;
import ir.layercloud.cortexdrone.action.block.impl.PlaceBlockAction;
import ir.layercloud.cortexdrone.action.block.impl.ReplaceBlockAction;
import ir.layercloud.cortexdrone.action.block.impl.RestoreBlocksAction;
import ir.layercloud.cortexdrone.service.world.DamageJournal;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.impl.BlockContext;
import ir.layercloud.cortexdrone.manager.ActionManager;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Two block primitives, nothing else: break the block at a position and place
 * one block at a position. The manager only routes the typed request to its
 * action; the physical work lives in {@link BreakBlockAction} and
 * {@link PlaceBlockAction}.
 */
public final class BuildingManager extends ActionManager<BlockContext, BlockRequest, BlockResult> {
    private final DroneLogger logger;
    private final DamageJournal journal;

    public BuildingManager(DroneLogger logger, DamageJournal journal) {
        super("block", "break_block", "place_block", "replace_block", "repair_trail");
        this.logger = logger;
        this.journal = journal;
    }

    /** The damage journal this manager records navigation digs into. */
    public DamageJournal journal() {
        return this.journal;
    }

    @Override
    protected BlockContext createContext(DroneController controller, EntityPlayerMP player,
                                         String operation) {
        return new BlockContext(controller, player, operation);
    }

    @Override
    protected Action<BlockContext> createAction(BlockContext context, String operation,
                                                BlockRequest request) {
        if ("break_block".equals(operation)) return new BreakBlockAction(this.logger, request);
        if ("place_block".equals(operation)) return new PlaceBlockAction(this.logger, request);
        if ("replace_block".equals(operation)) return new ReplaceBlockAction(this.logger, request);
        if ("repair_trail".equals(operation)) return new RestoreBlocksAction(this.journal);
        throw new IllegalArgumentException("Unsupported block action: " + operation);
    }

    @Override
    protected BlockResult result(BlockContext context) {
        return context.result();
    }
}
