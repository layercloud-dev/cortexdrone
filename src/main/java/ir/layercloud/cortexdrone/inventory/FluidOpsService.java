package ir.layercloud.cortexdrone.inventory;

import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import ir.layercloud.cortexdrone.world.RegistryIntrospector;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
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
 * Single-block fluid scooping using Forge's FluidRegistry and
 * FluidContainerRegistry. Only the primitive is exposed: batch scooping and
 * fluid placement queues were removed with the legacy behavior layer.
 */
@RequiredArgsConstructor
public class FluidOpsService {
    private final DroneLogger logger;
    private final InventoryService inventoryService;

    private static boolean isEmptyBucketStack(ItemStack st) {
        return RegistryIntrospector.isEmptyFluidContainer(st);
    }

    private static int countEmptyBuckets(IInventory inv) {
        if (inv == null) return 0;
        int count = 0;
        for (int i = 0; i < inv.func_70302_i_(); i++) {
            ItemStack st = inv.func_70301_a(i);
            if (isEmptyBucketStack(st)) {
                count += st.field_77994_a;
            }
        }
        return count;
    }

    private static ItemStack findFirstEmptyContainer(IInventory inv) {
        if (inv == null) return null;
        for (int i = 0; i < inv.func_70302_i_(); i++) {
            ItemStack st = inv.func_70301_a(i);
            if (isEmptyBucketStack(st)) {
                ItemStack single = st.func_77946_l();
                single.field_77994_a = 1;
                return single;
            }
        }
        return null;
    }

    private static boolean consumeEmptyBucket(IInventory inv) {
        if (inv == null) return false;
        for (int i = 0; i < inv.func_70302_i_(); i++) {
            ItemStack st = inv.func_70301_a(i);
            if (isEmptyBucketStack(st)) {
                inv.func_70298_a(i, 1);
                inv.func_70296_d();
                return true;
            }
        }
        return false;
    }

    private static ItemStack resolveFilledContainer(Block b, ItemStack emptyContainer) {
        Fluid fluid = RegistryIntrospector.lookupFluidForBlock(b);
        if (fluid != null && emptyContainer != null) {
            try {
                ItemStack filled = FluidContainerRegistry.fillFluidContainer(
                    new FluidStack(fluid, FluidContainerRegistry.BUCKET_VOLUME),
                    emptyContainer
                );
                if (filled != null && filled.func_77973_b() != null) {
                    return filled;
                }
            } catch (Throwable ignored) {
            }
        }
        boolean isLava = b.func_149688_o() == Material.field_151587_i || ItemClassifier.getBlockIdentifier(b).contains("lava");
        Item fallback = (Item) Item.field_150901_e.func_82594_a(isLava ? "minecraft:lava_bucket" : "minecraft:water_bucket");
        return fallback != null ? new ItemStack(fallback, 1, 0) : null;
    }

    public boolean scoopLiquidAt(World world, DroneController ctrl, EntityPlayerMP owner, int x, int y, int z) {
        if (world == null || ctrl == null || ctrl.drone == null) return false;
        Block b = world.func_147439_a(x, y, z);
        int meta = world.func_72805_g(x, y, z);
        if (!ItemClassifier.isLiquidBlock(b)) return false;
        if (b instanceof IFluidBlock) {
            if (!((IFluidBlock) b).canDrain(world, x, y, z)) return false;
        } else if (meta != 0) {
            return false;
        }

        IInventory inv = ctrl.drone.mainInventory();
        if (countEmptyBuckets(inv) == 0) {
            this.inventoryService.withdrawMatchingFromNearbyChests(world, ctrl, owner, "bucket", 4);
            if (countEmptyBuckets(inv) == 0 && owner != null) {
                this.inventoryService.takeFromPlayerInventory(ctrl, owner, "bucket", 4);
            }
        }
        ItemStack emptyProbe = findFirstEmptyContainer(inv);
        if (emptyProbe == null) return false;

        ItemStack filledStack = resolveFilledContainer(b, emptyProbe);
        if (filledStack == null) return false;
        if (!consumeEmptyBucket(inv)) return false;

        if (b instanceof IFluidBlock) {
            ((IFluidBlock) b).drain(world, x, y, z, true);
        } else {
            world.func_147468_f(x, y, z);
        }
        world.func_72908_a(x + 0.5, y + 0.5, z + 0.5, "game.neutral.swim", 0.6f, 1.0f);

        int ins = InventoryService.insertIntoInventory(inv, filledStack);
        if (ins == 0 && owner != null && owner.field_71071_by != null) {
            owner.field_71071_by.func_70441_a(filledStack);
            InventoryService.syncPlayerInventory(owner);
        }
        ctrl.scoopedCount++;
        this.logger.log("SCOOP", ctrl, String.format("Scooped %s at (%d,%d,%d)", filledStack.func_82833_r(), x, y, z), "scooped=" + ctrl.scoopedCount);
        return true;
    }
}
