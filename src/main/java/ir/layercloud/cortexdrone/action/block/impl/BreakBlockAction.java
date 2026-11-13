package ir.layercloud.cortexdrone.action.block.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.impl.BlockContext;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.inventory.ToolInspector;
import ir.layercloud.cortexdrone.service.world.BlockPlacer;
import ir.layercloud.cortexdrone.service.world.BlockSight;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.VeinBlocks;
import ir.layercloud.cortexdrone.service.world.VeinLimits;
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
        if (this.request.isVein()) {
            breakVein(context, controller, drone, world, x, y, z, name);
            return;
        }
        int fortune = ToolInspector.getBestFortuneLevel(drone.mainInventory());
        List<ItemStack> drops = computeDrops(world, block, x, y, z, meta, fortune);

        world.playAuxSFX(2001, x, y, z, Block.getIdFromBlock(block) + (meta << 12));
        if (!world.setBlockToAir(x, y, z)) {
            context.result(BlockResult.failure("Cannot break block at "
                    + x + "," + y + "," + z + " (world refused the change)"));
            return;
        }
        // The aux effect sends particles on the client, but the block-state
        // packet is skipped for chunks that are not light-populated; refresh
        // guarantees the gap actually appears.
        BlockPlacer.refresh(world, x, y, z, Blocks.air);

        IInventory inv = drone.mainInventory();
        giveDrops(world, drone, inv, drops);
        this.logger.log("MINE", controller,
                String.format("Broke %s at (%d,%d,%d)", name, x, y, z), "");
        context.result(BlockResult.broken(name, x, y, z));
    }

    /**
     * Breaks the connected cluster of the target block.
     *
     * <p>The plan is computed once on the server thread. Every block in it has
     * already passed the same mineable check as a single break; each one is then
     * broken through the same path (drops, inventory, refresh) and counted. A
     * block that the world refuses is skipped and reported through
     * {@code refused}, so a partial vein is never described as complete.</p>
     *
     * <p>Reach and line of sight are checked for the target only: the drone
     * cannot see every voxel inside a cluster from one standoff, and the radius
     * plus the target check is what keeps a vein break physical.</p>
     */
    private void breakVein(BlockContext context, DroneController controller, Drone drone,
                           World world, int x, int y, int z, String name) {
        List<int[]> plan = VeinBlocks.plan(world, x, y, z,
                this.request.getVeinRadius(), this.request.getVeinBlocks());
        int fortune = ToolInspector.getBestFortuneLevel(drone.mainInventory());
        IInventory inv = drone.mainInventory();
        List<String> broken = new ArrayList<>();
        int refused = 0;

        for (int[] point : plan) {
            int bx = point[0];
            int by = point[1];
            int bz = point[2];
            if (!ItemClassifier.isMineableBlock(world, bx, by, bz)) {
                refused++;
                continue;
            }
            Block current = world.getBlock(bx, by, bz);
            if (current == null || current == Blocks.air) {
                refused++;
                continue;
            }
            int currentMeta = world.getBlockMetadata(bx, by, bz);
            List<ItemStack> drops = computeDrops(world, current, bx, by, bz, currentMeta, fortune);
            world.playAuxSFX(2001, bx, by, bz,
                    Block.getIdFromBlock(current) + (currentMeta << 12));
            if (!world.setBlockToAir(bx, by, bz)) {
                refused++;
                continue;
            }
            BlockPlacer.refresh(world, bx, by, bz, Blocks.air);
            giveDrops(world, drone, inv, drops);
            broken.add(ItemClassifier.getBlockDisplayName(current, currentMeta));
        }

        if (broken.isEmpty()) {
            context.result(BlockResult.failure("Cannot break the vein at "
                    + x + "," + y + "," + z + " (no block in the cluster could be removed)"));
            return;
        }
        this.logger.log("MINE", controller,
                String.format("Broke %s vein: %d block(s) at (%d,%d,%d)", name,
                        broken.size(), x, y, z),
                "radius=" + this.request.getVeinRadius()
                        + " cap=" + this.request.getVeinBlocks()
                        + " refused=" + refused);
        context.result(BlockResult.vein(name, x, y, z, broken.size(), broken));
    }

    /**
     * Inserts drops into the drone inventory, leaving the overflow on the floor.
     */
    private static void giveDrops(World world, Drone drone, IInventory inv, List<ItemStack> drops) {
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

    /**
     * Resolves the drops for one block, including the modded-ore fallback used
     * when a mod returns an empty list from {@code getDrops}.
     */
    @SuppressWarnings("unchecked")
    public static List<ItemStack> computeDrops(World world, Block block,
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
