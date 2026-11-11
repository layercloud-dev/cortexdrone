package ir.layercloud.cortexdrone.action.block.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.impl.BlockContext;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.inventory.ToolInspector;
import ir.layercloud.cortexdrone.service.world.BlockSight;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.util.List;

/**
 * One atomic block replacement: verify the target item is available, remove
 * the old block, and place the new one in the same call.
 *
 * <p>The old block is only removed after the replacement item has been found,
 * and restored if the placement fails. That keeps a failed replacement from
 * leaving a hole in a floor, wall or ceiling.</p>
 */
@RequiredArgsConstructor
public final class ReplaceBlockAction implements Action<BlockContext> {
    private final DroneLogger logger;
    private final BlockRequest request;

    @Override
    public void execute(BlockContext context) {
        DroneController controller = context.controller();
        int x = this.request.getX();
        int y = this.request.getY();
        int z = this.request.getZ();
        String filter = this.request.getFilter() != null ? this.request.getFilter() : "any";
        int metaOverride = this.request.getMeta();
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            context.result(BlockResult.failure("No active drone or world"));
            return;
        }

        Drone drone = controller.drone;
        World world = drone.worldObj;
        if (!ItemClassifier.isMineableBlock(world, x, y, z)) {
            context.result(BlockResult.failure("Cannot replace block at "
                    + x + "," + y + "," + z + " (air, unbreakable or protected)"));
            return;
        }
        BlockSight.Result sight = BlockSight.check(world,
                drone.posX, BlockSight.eyeY(drone), drone.posZ, x, y, z);
        if (!sight.isVisible() || sight.getDistance() > BlockSight.REACH) {
            this.logger.log("BUILD", controller,
                    String.format("Refused unreachable replace at (%d,%d,%d)", x, y, z),
                    sight.getDistance() > BlockSight.REACH
                            ? "distance=" + Math.round(sight.getDistance() * 100.0) / 100.0
                            : "occluded by " + sight.getOccluderName());
            context.result(BlockResult.failure("Cannot replace block at "
                    + x + "," + y + "," + z + " (unreachable or occluded)"));
            return;
        }

        Block oldBlock = world.getBlock(x, y, z);
        int oldMeta = world.getBlockMetadata(x, y, z);
        String oldName = ItemClassifier.getBlockDisplayName(oldBlock, oldMeta);
        if (ItemClassifier.matchesBlockOrItemName(oldName,
                ItemClassifier.getBlockIdentifier(oldBlock, oldMeta), oldMeta, filter)
                && (metaOverride < 0 || metaOverride == oldMeta)) {
            context.result(BlockResult.replaced(oldName, oldName, x, y, z, true));
            return;
        }

        IInventory inventory = drone.mainInventory();
        int slot = InventoryService.findPlaceableItemSlot(inventory, filter);
        if (slot < 0) {
            context.result(BlockResult.failure("No '" + filter
                    + "' block in the drone inventory; nothing was broken. "
                    + "Gather or withdraw the replacement first."));
            return;
        }
        ItemStack targetStack = inventory.getStackInSlot(slot);
        if (targetStack == null || targetStack.stackSize <= 0 || targetStack.getItem() == null) {
            context.result(BlockResult.failure("Replacement item stack is empty"));
            return;
        }
        Item targetItem = targetStack.getItem();
        Block newBlock = Block.getBlockFromItem(targetItem);
        int newMeta = metaOverride >= 0 ? metaOverride : targetItem.getMetadata(targetStack.getItemDamage());
        if (newBlock == null || newBlock == Blocks.air || ItemClassifier.isLiquidBlock(newBlock)) {
            context.result(BlockResult.failure("'" + filter + "' is not a placeable block"));
            return;
        }

        int fortune = ToolInspector.getBestFortuneLevel(inventory);
        List<ItemStack> drops = BreakBlockAction.computeDrops(world, oldBlock, x, y, z, oldMeta, fortune);

        world.playAuxSFX(2001, x, y, z, Block.getIdFromBlock(oldBlock) + (oldMeta << 12));
        if (!world.setBlockToAir(x, y, z)) {
            context.result(BlockResult.failure("World refused to remove the old block at "
                    + x + "," + y + "," + z));
            return;
        }

        if (!world.setBlock(x, y, z, newBlock, newMeta, 3)) {
            // Never leave a hole: restore the original block when placement fails.
            world.setBlock(x, y, z, oldBlock, oldMeta, 3);
            world.playAuxSFX(2001, x, y, z, Block.getIdFromBlock(oldBlock) + (oldMeta << 12));
            context.result(BlockResult.failure("Could not place the replacement at "
                    + x + "," + y + "," + z + "; the original block was restored."));
            return;
        }

        world.playAuxSFX(2001, x, y, z, Block.getIdFromBlock(newBlock) + (newMeta << 12));
        inventory.decrStackSize(slot, 1);
        inventory.markDirty();
        for (ItemStack drop : drops) {
            if (drop == null || drop.stackSize <= 0) continue;
            ItemStack copy = drop.copy();
            InventoryService.insertIntoInventory(inventory, copy);
            if (copy.stackSize > 0) {
                EntityItem leftover = new EntityItem(world, drone.posX, drone.posY, drone.posZ, copy);
                leftover.delayBeforeCanPickup = 0;
                world.spawnEntityInWorld(leftover);
            }
        }
        String newName = ItemClassifier.getBlockDisplayName(newBlock, newMeta);
        this.logger.log("BUILD", controller,
                String.format("Replaced %s with %s at (%d,%d,%d)", oldName, newName, x, y, z), "");
        context.result(BlockResult.replaced(oldName, newName, x, y, z, false));
    }
}
