package ir.layercloud.cortexdrone.action.block.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.impl.BlockContext;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * Single-use command: place exactly one explicit block from the drone
 * inventory. The action owns the physical operation; the manager only creates
 * and runs it.
 */
@RequiredArgsConstructor
public final class PlaceBlockAction implements Action<BlockContext> {
    private final DroneLogger logger;
    private final BlockRequest request;

    @Override
    public void execute(BlockContext context) {
        DroneController controller = context.controller();
        int x = this.request.getX();
        int y = this.request.getY();
        int z = this.request.getZ();
        String filter = this.request.getFilter() != null ? this.request.getFilter() : "any";
        boolean placed = place(controller, x, y, z, filter, this.request.getMeta());
        context.result(BlockResult.placed(placed, filter, x, y, z));
    }

    private boolean place(DroneController controller,
                          int x, int y, int z, String filter, int metaOverride) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return false;
        }
        Drone drone = controller.drone;
        World world = drone.worldObj;
        if (!ItemClassifier.isReplaceableBlock(world, x, y, z)) return false;

        // Strict primitive: only the drone inventory is used. If the block is
        // not there, fail so the model can gather or take it explicitly.
        IInventory inventory = drone.mainInventory();
        int slot = InventoryService.findPlaceableItemSlot(inventory, filter);
        if (slot < 0) return false;

        ItemStack stack = inventory.getStackInSlot(slot);
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) return false;

        Item item = stack.getItem();
        Block block = Block.getBlockFromItem(item);
        if (block == null || block == Blocks.air) return false;

        int meta = metaOverride >= 0 ? metaOverride : item.getMetadata(stack.getItemDamage());
        boolean placed = world.setBlock(x, y, z, block, meta, 3);
        if (placed) {
            world.playAuxSFX(2001, x, y, z, Block.getIdFromBlock(block) + (meta << 12));
            inventory.decrStackSize(slot, 1);
            inventory.markDirty();
            this.logger.log("BUILD", controller,
                    String.format("Placed %s at (%d,%d,%d)", block.getLocalizedName(), x, y, z), "");
        }
        return placed;
    }
}
