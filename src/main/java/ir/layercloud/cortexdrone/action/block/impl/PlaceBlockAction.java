package ir.layercloud.cortexdrone.action.block.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.block.impl.BlockContext;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/** Single-use command: place exactly one explicit block. */
@RequiredArgsConstructor
public final class PlaceBlockAction implements Action<BlockContext> {
    private final InventoryService inventoryService;
    private final DroneLogger logger;
    private final BlockRequest request;

    @Override
    public void execute(BlockContext context) {
        DroneController controller = context.controller();
        EntityPlayerMP player = context.player();
        int x = request.getX();
        int y = request.getY();
        int z = request.getZ();
        String filter = request.getFilter() != null ? request.getFilter() : "any";

        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            context.result(BlockResult.failure("No active drone or world"));
            return;
        }

        Drone drone = controller.drone;
        World world = drone.worldObj;
        if (!ItemClassifier.isReplaceableBlock(world, x, y, z)) {
            context.result(BlockResult.placed(false, filter, x, y, z));
            return;
        }

        IInventory inventory = drone.mainInventory();
        int slot = InventoryService.findPlaceableItemSlot(inventory, filter);
        if (slot < 0) {
            int pulled = this.inventoryService.withdrawMatchingFromNearbyChests(
                world, controller, player, filter, 64);
            if (pulled > 0) slot = InventoryService.findPlaceableItemSlot(inventory, filter);
            if (slot < 0 && player != null) {
                int taken = this.inventoryService.takeFromPlayerInventory(
                    controller, player, filter, 64);
                if (taken > 0) slot = InventoryService.findPlaceableItemSlot(inventory, filter);
            }
        }
        if (slot < 0) {
            context.result(BlockResult.placed(false, filter, x, y, z));
            return;
        }

        ItemStack stack = inventory.getStackInSlot(slot);
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) {
            context.result(BlockResult.placed(false, filter, x, y, z));
            return;
        }

        Item item = stack.getItem();
        Block block = Block.getBlockFromItem(item);
        if (block == null || block == Blocks.air) {
            context.result(BlockResult.placed(false, filter, x, y, z));
            return;
        }

        int meta = request.getMeta() >= 0 ? request.getMeta() : item.getMetadata(stack.getItemDamage());
        boolean placed = world.setBlock(x, y, z, block, meta, 3);
        if (placed) {
            world.playAuxSFX(2001, x, y, z, Block.getIdFromBlock(block) + (meta << 12));
            inventory.decrStackSize(slot, 1);
            inventory.markDirty();
            controller.builtCount++;
            this.logger.log("BUILD", controller,
                String.format("Placed %s at (%d,%d,%d)", block.getLocalizedName(), x, y, z), "");
        }
        context.result(BlockResult.placed(placed, filter, x, y, z));
    }
}
