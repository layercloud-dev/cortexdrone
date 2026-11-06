package ir.layercloud.cortexdrone.manager.building.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.block.BlockRequest;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.action.block.impl.BreakBlockAction;
import ir.layercloud.cortexdrone.action.block.impl.PlaceBlockAction;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.impl.BlockContext;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.manager.ActionManager;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.inventory.ToolInspector;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Two block primitives, nothing else: break the block at a position and place
 * one block at a position. There is no mining queue, no target selection, no
 * dig planner and no progress state — the model decides which blocks to touch.
 */
public final class BuildingManager extends ActionManager<BlockContext, BlockRequest, BlockResult> {
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private Method blockGetDropsMethod = null;

    public BuildingManager(AIDroneManager manager, DroneLogger logger) {
        super("block", "break_block", "place_block");
        this.manager = manager;
        this.logger = logger;
        try {
            this.blockGetDropsMethod = Block.class.getMethod(
                    "getDrops", World.class, int.class, int.class, int.class, int.class, int.class
            );
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    public List<ItemStack> getBlockDrops(World world, Block block, int x, int y, int z, int meta, int fortune) {
        List<ItemStack> drops = new ArrayList<ItemStack>();
        if (this.blockGetDropsMethod != null) {
            try {
                Object res = this.blockGetDropsMethod.invoke(block, world, x, y, z, meta, fortune);
                if (res instanceof List) {
                    drops.addAll((List<ItemStack>) res);
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

    /**
     * Breaks exactly one block immediately and inserts its drops into the drone
     * inventory; anything that does not fit is dropped in the world.
     */
    public String breakBlock(DroneController controller, int x, int y, int z) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return null;
        }
        Drone drone = controller.drone;
        World world = drone.worldObj;
        if (!ItemClassifier.isMineableBlock(world, x, y, z)) return null;

        Block block = world.getBlock(x, y, z);
        if (block == null || block == Blocks.air) return null;
        int meta = world.getBlockMetadata(x, y, z);
        String name = ItemClassifier.getBlockDisplayName(block, meta);
        int fortune = ToolInspector.getBestFortuneLevel(drone.mainInventory());
        List<ItemStack> drops = getBlockDrops(world, block, x, y, z, meta, fortune);

        world.playAuxSFX(2001, x, y, z, Block.getIdFromBlock(block) + (meta << 12));
        if (!world.setBlockToAir(x, y, z)) return null;

        IInventory inv = drone.mainInventory();
        if (drops != null) {
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
        this.logger.log("MINE", controller,
                String.format("Broke %s at (%d,%d,%d)", name, x, y, z), "");
        return name;
    }

    /**
     * Places exactly one explicit block from drone/chest/player inventory.
     */
    public boolean placeBlock(DroneController controller,
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

    @Override
    protected BlockContext createContext(DroneController controller, EntityPlayerMP player,
                                         String operation) {
        return new BlockContext(controller, player, operation);
    }

    @Override
    protected Action<BlockContext> createAction(BlockContext context, String operation,
                                                BlockRequest request) {
        if ("break_block".equals(operation)) return new BreakBlockAction(this, request);
        if ("place_block".equals(operation)) return new PlaceBlockAction(this, request);
        throw new IllegalArgumentException("Unsupported block action: " + operation);
    }

    @Override
    protected BlockResult result(BlockContext context) {
        return context.result();
    }
}
