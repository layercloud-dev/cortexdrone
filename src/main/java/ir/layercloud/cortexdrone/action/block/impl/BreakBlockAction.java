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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-use command: break exactly one block. The block is gone when the call
 * returns; there is no retained break and no progress to tick.
 *
 * <p>The action owns the whole physical operation: reach/visibility guard,
 * drop resolution, inventory insertion and world mutation. The manager only
 * creates and runs it.</p>
 */
@RequiredArgsConstructor
public final class BreakBlockAction implements Action<BlockContext> {
    private static final Method BLOCK_GET_DROPS = resolveGetDropsMethod();

    private final DroneLogger logger;
    private final BlockRequest request;

    @Override
    public void execute(BlockContext context) {
        DroneController controller = context.controller();
        int x = this.request.getX();
        int y = this.request.getY();
        int z = this.request.getZ();
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            context.result(BlockResult.failure("No active drone or world"));
            return;
        }

        Drone drone = controller.drone;
        World world = drone.worldObj;
        if (!ItemClassifier.isMineableBlock(world, x, y, z)) {
            context.result(BlockResult.failure("Cannot break block at "
                    + x + "," + y + "," + z + " (not mineable or already air)"));
            return;
        }
        BlockSight.Result sight = BlockSight.check(world,
                drone.posX, BlockSight.eyeY(drone), drone.posZ, x, y, z);
        if (!sight.isVisible() || sight.getDistance() > BlockSight.REACH) {
            this.logger.log("MINE", controller,
                    String.format("Refused unreachable block at (%d,%d,%d)", x, y, z),
                    sight.getDistance() > BlockSight.REACH
                            ? "distance=" + Math.round(sight.getDistance() * 100.0) / 100.0
                            : "occluded by " + sight.getOccluderName());
            context.result(BlockResult.failure("Cannot break block at "
                    + x + "," + y + "," + z + " (unreachable or occluded)"));
            return;
        }

        Block block = world.getBlock(x, y, z);
        if (block == null || block == Blocks.air) {
            context.result(BlockResult.failure("Cannot break block at "
                    + x + "," + y + "," + z + " (not mineable or already air)"));
            return;
        }
        int meta = world.getBlockMetadata(x, y, z);
        String name = ItemClassifier.getBlockDisplayName(block, meta);
        int fortune = ToolInspector.getBestFortuneLevel(drone.mainInventory());
        List<ItemStack> drops = getBlockDrops(world, block, x, y, z, meta, fortune);

        world.playAuxSFX(2001, x, y, z, Block.getIdFromBlock(block) + (meta << 12));
        if (!world.setBlockToAir(x, y, z)) {
            context.result(BlockResult.failure("Cannot break block at "
                    + x + "," + y + "," + z + " (world refused the change)"));
            return;
        }

        IInventory inv = drone.mainInventory();
        for (ItemStack drop : drops) {
            if (drop == null || drop.stackSize <= 0) continue;
            ItemStack copy = drop.copy();
            InventoryService.insertIntoInventory(inv, copy);
            if (copy.stackSize > 0) {
                EntityItem leftover = new EntityItem(world, drone.posX, drone.posY, drone.posZ, copy);
                leftover.delayBeforeCanPickup = 0;
                world.spawnEntityInWorld(leftover);
            }
        }
        this.logger.log("MINE", controller,
                String.format("Broke %s at (%d,%d,%d)", name, x, y, z), "");
        context.result(BlockResult.broken(name, x, y, z));
    }

    private static Method resolveGetDropsMethod() {
        try {
            return Block.class.getMethod(
                    "getDrops", World.class, int.class, int.class, int.class, int.class, int.class
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ItemStack> getBlockDrops(World world, Block block,
                                                 int x, int y, int z, int meta, int fortune) {
        List<ItemStack> drops = new ArrayList<>();
        if (BLOCK_GET_DROPS != null) {
            try {
                Object resolved = BLOCK_GET_DROPS.invoke(block, world, x, y, z, meta, fortune);
                if (resolved instanceof List) {
                    drops.addAll((List<ItemStack>) resolved);
                }
            } catch (Throwable ignored) {
            }
        }
        if (drops.isEmpty()) {
            int count = Math.max(1, block.quantityDropped(world.rand));
            Item item = block.getItemDropped(meta, world.rand, fortune);
            if (item == null) {
                item = Item.getItemFromBlock(block);
            }
            if (item != null) {
                drops.add(new ItemStack(item, count, block.damageDropped(meta)));
            }
        }
        return drops;
    }
}
