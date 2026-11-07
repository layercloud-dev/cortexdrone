package ir.layercloud.cortexdrone.action.interact.impl;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.interact.InteractionRequest;
import ir.layercloud.cortexdrone.action.interact.InteractionResult;
import ir.layercloud.cortexdrone.action.interact.InteractionSupport;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.impl.InteractionContext;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.RegistryIntrospector;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidBlock;

/**
 * Single-use command: activate exactly one block. A source liquid is scooped
 * into a bucket instead of being activated, because the drone has no hand.
 *
 * <p>The action owns the whole physical operation, including the bucket
 * sourcing and the world mutation. Services are only queried for read-only
 * registry data and low-level inventory transfers.</p>
 */
@RequiredArgsConstructor
public final class InteractBlockAction implements Action<InteractionContext> {
    private final InventoryService inventoryService;
    private final DroneLogger logger;
    private final InteractionRequest request;

    @Override
    public void execute(InteractionContext context) {
        DroneController controller = context.controller();
        EntityPlayerMP player = context.player();
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            context.result(InteractionResult.failure("No active drone or world", "activated", false));
            return;
        }

        Drone drone = controller.drone;
        World world = drone.worldObj;
        int x = request.getX();
        int y = request.getY();
        int z = request.getZ();
        context.targetBlock(x, y, z);

        Block block = world.getBlock(x, y, z);
        if (ItemClassifier.isLiquidBlock(block) && world.getBlockMetadata(x, y, z) == 0) {
            boolean scooped = scoopLiquid(world, controller, player, x, y, z);
            context.result(InteractionResult.success(
                    "activated", scooped,
                    "scooped", scooped));
            return;
        }

        EntityPlayer fakePlayer = drone.player();
        if (fakePlayer == null) {
            context.result(InteractionResult.failure("No fake player on drone", "activated", false));
            return;
        }
        InteractionSupport.syncFakePlayerToDrone(fakePlayer, drone);

        boolean activated = block != null && block.onBlockActivated(
                world, x, y, z, fakePlayer, request.getSide(), 0.5f, 0.5f, 0.5f);
        context.result(InteractionResult.success(
                "activated", activated,
                "block", ItemClassifier.getBlockDisplayName(
                        world.getBlock(x, y, z), world.getBlockMetadata(x, y, z))));
    }

    private boolean scoopLiquid(World world, DroneController controller, EntityPlayerMP owner,
                                int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        if (!ItemClassifier.isLiquidBlock(block)) return false;
        if (block instanceof IFluidBlock) {
            if (!((IFluidBlock) block).canDrain(world, x, y, z)) return false;
        } else if (meta != 0) {
            return false;
        }

        IInventory inv = controller.drone.mainInventory();
        if (countEmptyBuckets(inv) == 0) {
            this.inventoryService.withdrawMatchingFromNearbyChests(world, controller, owner, "bucket", 4);
            if (countEmptyBuckets(inv) == 0 && owner != null) {
                this.inventoryService.takeFromPlayerInventory(controller, owner, "bucket", 4);
            }
        }
        ItemStack emptyProbe = findFirstEmptyContainer(inv);
        if (emptyProbe == null) return false;

        ItemStack filledStack = resolveFilledContainer(block, emptyProbe);
        if (filledStack == null) return false;
        if (!consumeEmptyBucket(inv)) return false;

        if (block instanceof IFluidBlock) {
            ((IFluidBlock) block).drain(world, x, y, z, true);
        } else {
            world.setBlockToAir(x, y, z);
        }
        world.playSoundEffect(x + 0.5, y + 0.5, z + 0.5, "game.neutral.swim", 0.6f, 1.0f);

        int inserted = InventoryService.insertIntoInventory(inv, filledStack);
        if (inserted == 0 && owner != null && owner.inventory != null) {
            owner.inventory.addItemStackToInventory(filledStack);
            InventoryService.syncPlayerInventory(owner);
        }
        this.logger.log("SCOOP", controller,
                String.format("Scooped %s at (%d,%d,%d)", filledStack.getDisplayName(), x, y, z), "");
        return true;
    }

    private static int countEmptyBuckets(IInventory inv) {
        if (inv == null) return 0;
        int count = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (isBucket(stack)) {
                count += stack.stackSize;
            }
        }
        return count;
    }

    private static ItemStack findFirstEmptyContainer(IInventory inv) {
        if (inv == null) return null;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (isBucket(stack)) {
                ItemStack single = stack.copy();
                single.stackSize = 1;
                return single;
            }
        }
        return null;
    }

    private static boolean consumeEmptyBucket(IInventory inv) {
        if (inv == null) return false;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            if (!isBucket(inv.getStackInSlot(i))) continue;
            inv.decrStackSize(i, 1);
            inv.markDirty();
            return true;
        }
        return false;
    }

    private static boolean isBucket(ItemStack stack) {
        return stack != null && RegistryIntrospector.isEmptyFluidContainer(stack);
    }

    private static ItemStack resolveFilledContainer(Block block, ItemStack emptyContainer) {
        Fluid fluid = RegistryIntrospector.lookupFluidForBlock(block);
        if (fluid != null && emptyContainer != null) {
            try {
                ItemStack filled = FluidContainerRegistry.fillFluidContainer(
                        new FluidStack(fluid, FluidContainerRegistry.BUCKET_VOLUME),
                        emptyContainer
                );
                if (filled != null && filled.getItem() != null) {
                    return filled;
                }
            } catch (Throwable ignored) {
            }
        }
        boolean isLava = block.getMaterial() == Material.lava
                || ItemClassifier.getBlockIdentifier(block).contains("lava");
        Item fallback = (Item) Item.itemRegistry.getObject(
                isLava ? "minecraft:lava_bucket" : "minecraft:water_bucket");
        return fallback != null ? new ItemStack(fallback, 1, 0) : null;
    }
}
