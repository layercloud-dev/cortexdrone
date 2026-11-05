package ir.layercloud.cortexdrone.inventory;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.FlightPathfinder;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import ir.layercloud.cortexdrone.world.RegistryIntrospector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidBlock;

/**
 * Dynamic fluid scooping, container filling/draining, and placement engine
 * powered by Forge's FluidRegistry and FluidContainerRegistry.
 * Supports Vanilla water/lava as well as any modded fluid block and container.
 */
@RequiredArgsConstructor
public class FluidOpsService {
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final FlightPathfinder pathfinder;
    private final InventoryService inventoryService;

    public static boolean isEmptyBucketStack(ItemStack st) {
        return RegistryIntrospector.isEmptyFluidContainer(st);
    }

    public static int countEmptyBuckets(IInventory inv) {
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

    public static ItemStack findFirstEmptyContainer(IInventory inv) {
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

    public static boolean consumeEmptyBucket(IInventory inv) {
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

    public static boolean matchesLiquidType(Block b, String filter) {
        if (!ItemClassifier.isLiquidBlock(b)) return false;
        String f = (filter == null || filter.trim().isEmpty()) ? "any" : filter.trim().toLowerCase();
        if (f.equals("any") || f.equals("all") || f.equals("liquid") || f.equals("fluid")) return true;
        Fluid fluid = RegistryIntrospector.lookupFluidForBlock(b);
        if (fluid != null) {
            String fName = fluid.getName() != null ? fluid.getName().toLowerCase() : "";
            FluidStack probeStack = new FluidStack(fluid, FluidContainerRegistry.BUCKET_VOLUME);
            String loc = fluid.getLocalizedName(probeStack);
            String fLoc = loc != null ? loc.toLowerCase() : "";
            if (fName.contains(f) || fLoc.contains(f) || f.contains(fName)) {
                return true;
            }
        }
        Material m = b.func_149688_o();
        if (f.contains("lava") && m == Material.field_151587_i) return true;
        if (f.contains("water") && m == Material.field_151586_h) return true;
        return ItemClassifier.getBlockIdentifier(b).contains(f);
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

    private static int findFilledFluidContainerSlot(IInventory inv, String fluidFilter) {
        if (inv == null) return -1;
        String f = (fluidFilter == null || fluidFilter.trim().isEmpty()) ? "water" : fluidFilter.trim().toLowerCase();
        for (int i = 0; i < inv.func_70302_i_(); i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
            FluidStack fs = RegistryIntrospector.getFluidInContainer(st);
            if (fs != null && fs.getFluid() != null) {
                String fn = fs.getFluid().getName() != null ? fs.getFluid().getName().toLowerCase() : "";
                String fl = fs.getLocalizedName() != null ? fs.getLocalizedName().toLowerCase() : "";
                if (f.equals("any") || fn.contains(f) || fl.contains(f) || f.contains(fn)) {
                    return i;
                }
            }
            String disp = st.func_82833_r();
            String id = ItemClassifier.getItemIdentifier(st);
            if (id.contains("bucket") && ItemClassifier.matchesBlockOrItemName(disp, id, st.func_77960_j(), f + " bucket")) {
                return i;
            }
        }
        return -1;
    }

    public boolean placeLiquidFromBucket(World world, DroneController ctrl, EntityPlayerMP owner, int x, int y, int z, String type) {
        if (!ItemClassifier.isReplaceableBlock(world, x, y, z)) return false;
        String t = (type == null || type.isEmpty()) ? "water" : type.trim().toLowerCase();
        String bucketFilter = t.endsWith("bucket") ? t : (t + " bucket");

        IInventory inv = ctrl.drone.mainInventory();
        int slot = findFilledFluidContainerSlot(inv, t);
        if (slot < 0) {
            this.inventoryService.withdrawMatchingFromNearbyChests(world, ctrl, owner, bucketFilter, 1);
            slot = findFilledFluidContainerSlot(inv, t);
            if (slot < 0 && owner != null) {
                this.inventoryService.takeFromPlayerInventory(ctrl, owner, bucketFilter, 1);
                slot = findFilledFluidContainerSlot(inv, t);
            }
        }
        if (slot < 0) return false;

        ItemStack filledStack = inv.func_70301_a(slot);
        FluidStack fs = RegistryIntrospector.getFluidInContainer(filledStack);
        Block fluidBlock = null;
        boolean isLava = t.contains("lava");
        if (fs != null && fs.getFluid() != null) {
            isLava = fs.getFluid() == FluidRegistry.LAVA || fs.getFluid().getTemperature() >= 800;
            fluidBlock = fs.getFluid().getBlock();
        }
        if (fluidBlock == null) {
            fluidBlock = Block.func_149684_b(isLava ? "flowing_lava" : "flowing_water");
            if (fluidBlock == null) fluidBlock = Block.func_149684_b(isLava ? "lava" : "water");
        }
        if (fluidBlock == null) return false;

        ItemStack singleFilled = filledStack.func_77946_l();
        singleFilled.field_77994_a = 1;
        ItemStack drainedContainer = null;
        try {
            drainedContainer = FluidContainerRegistry.drainFluidContainer(singleFilled);
        } catch (Throwable ignored) {
        }
        if (drainedContainer == null) {
            Item emptyBucket = (Item) Item.field_150901_e.func_82594_a("minecraft:bucket");
            if (emptyBucket != null) drainedContainer = new ItemStack(emptyBucket, 1, 0);
        }

        inv.func_70298_a(slot, 1);
        if (drainedContainer != null) {
            InventoryService.insertIntoInventory(inv, drainedContainer);
        }
        inv.func_70296_d();
        world.func_147465_d(x, y, z, fluidBlock, 0, 3);
        world.func_147459_d(x, y, z, fluidBlock);
        world.func_72908_a(x + 0.5, y + 0.5, z + 0.5, isLava ? "liquid.lava" : "liquid.splash", 0.7f, 1.0f);
        this.logger.log("SCOOP", ctrl, String.format("Placed %s at (%d,%d,%d)", ItemClassifier.getBlockDisplayName(fluidBlock, 0), x, y, z), "");
        return true;
    }

    public int startScoopLiquidTask(DroneController ctrl, EntityPlayerMP player, String rawType, int maxCount) {
        final Drone d = ctrl.drone;
        final World world = d.field_70170_p;
        String type = (rawType == null || rawType.trim().isEmpty()) ? "lava" : rawType.trim().toLowerCase();
        int wanted = Math.max(1, Math.min(8, maxCount));

        ctrl.scoopFilter = type;
        ctrl.scoopTargetCount = wanted;
        ctrl.scoopedCount = 0;
        ctrl.scoopQueue.clear();

        if (countEmptyBuckets(d.mainInventory()) < wanted) {
            int need = wanted - countEmptyBuckets(d.mainInventory());
            this.inventoryService.withdrawMatchingFromNearbyChests(world, ctrl, player, "bucket", need);
            if (countEmptyBuckets(d.mainInventory()) < wanted && player != null) {
                this.inventoryService.takeFromPlayerInventory(ctrl, player, "bucket", wanted - countEmptyBuckets(d.mainInventory()));
            }
        }

        double refX = player != null && player.field_70170_p == world ? player.field_70165_t : d.field_70165_t;
        double refY = player != null && player.field_70170_p == world ? player.field_70163_u : d.field_70163_u;
        double refZ = player != null && player.field_70170_p == world ? player.field_70161_v : d.field_70161_v;
        int ix = MathHelper.func_76128_c(refX);
        int iy = MathHelper.func_76128_c(refY);
        int iz = MathHelper.func_76128_c(refZ);

        List<int[]> candidates = new ArrayList<int[]>();
        int radius = 20;
        long scanDeadline = System.currentTimeMillis() + 30L;
        for (int x = ix - radius; x <= ix + radius && System.currentTimeMillis() <= scanDeadline; x++) {
            for (int y = Math.max(2, iy - 32); y <= Math.min(254, iy + 16); y++) {
                for (int z = iz - radius; z <= iz + radius; z++) {
                    if (world.func_147437_c(x, y, z)) continue;
                    Block b = world.func_147439_a(x, y, z);
                    if (matchesLiquidType(b, type) && world.func_72805_g(x, y, z) == 0) {
                        candidates.add(new int[]{x, y, z});
                    }
                }
            }
        }

        Collections.sort(candidates, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                double da = d.func_70092_e(a[0] + 0.5, a[1] + 0.5, a[2] + 0.5);
                double db = d.func_70092_e(b[0] + 0.5, b[1] + 0.5, b[2] + 0.5);
                return Double.compare(da, db);
            }
        });

        for (int[] c : candidates) {
            ctrl.scoopQueue.addLast(c);
        }

        if (!ctrl.scoopQueue.isEmpty()) {
            this.manager.setDroneMode(ctrl, Mode.SCOOP, "Scooping " + wanted + " '" + type + "' bucket(s) (" + candidates.size() + " source blocks found)");
            d.lightColor_$eq(type.contains("lava") ? 0xFF5500 : 0x0088FF);
            d.statusText_$eq("Scooping\n" + AIDroneManager.trimStatus(type));
        }
        return candidates.size();
    }

    public void tickScoopBehavior(DroneController ctrl, EntityPlayerMP owner) {
        Drone d = ctrl.drone;
        World world = d.field_70170_p;
        d.lightColor_$eq(ctrl.scoopFilter.contains("lava") ? 0xFF5500 : 0x0088FF);

        if (ctrl.scoopedCount >= ctrl.scoopTargetCount || ctrl.scoopQueue.isEmpty()) {
            if (!ctrl.isScriptRunning() && owner != null) {
                this.manager.droneSay(owner, ctrl, "Scooped " + ctrl.scoopedCount + " " + ctrl.scoopFilter + " bucket(s)! Flying back to deliver to your inventory.");
            }
            this.manager.setDroneMode(ctrl, ctrl.isScriptRunning() ? Mode.FOLLOW : Mode.DROP, "Completed liquid scooping");
            return;
        }

        while (!ctrl.scoopQueue.isEmpty()) {
            int[] peek = ctrl.scoopQueue.peekFirst();
            Block b = world.func_147439_a(peek[0], peek[1], peek[2]);
            if (matchesLiquidType(b, ctrl.scoopFilter) && world.func_72805_g(peek[0], peek[1], peek[2]) == 0) {
                break;
            }
            ctrl.scoopQueue.pollFirst();
        }
        if (ctrl.scoopQueue.isEmpty()) {
            this.manager.setDroneMode(ctrl, (!ctrl.isScriptRunning() && ctrl.scoopedCount > 0) ? Mode.DROP : Mode.FOLLOW, "No more source liquid blocks");
            return;
        }

        if (countEmptyBuckets(d.mainInventory()) == 0) {
            this.inventoryService.withdrawMatchingFromNearbyChests(world, ctrl, owner, "bucket", ctrl.scoopTargetCount - ctrl.scoopedCount);
            if (countEmptyBuckets(d.mainInventory()) == 0 && owner != null) {
                this.inventoryService.takeFromPlayerInventory(ctrl, owner, "bucket", ctrl.scoopTargetCount - ctrl.scoopedCount);
            }
            if (countEmptyBuckets(d.mainInventory()) == 0) {
                if (!ctrl.isScriptRunning() && owner != null) {
                    this.manager.droneSay(owner, ctrl, ctrl.scoopedCount > 0
                        ? ("Scooped " + ctrl.scoopedCount + " bucket(s) before running out of empty buckets! Delivering to you.")
                        : ("I need an empty Bucket in my inventory, your inventory, or a nearby chest to scoop " + ctrl.scoopFilter + "!"));
                }
                this.manager.setDroneMode(ctrl, (!ctrl.isScriptRunning() && ctrl.scoopedCount > 0) ? Mode.DROP : Mode.FOLLOW, "No empty bucket available");
                return;
            }
        }

        int[] target = ctrl.scoopQueue.peekFirst();
        double tx = target[0] + 0.5;
        double ty = target[1] + 0.5;
        double tz = target[2] + 0.5;
        double dist = d.func_70011_f(tx, ty, tz);
        if (dist <= 4.2) {
            ctrl.scoopQueue.pollFirst();
            scoopLiquidAt(world, ctrl, owner, target[0], target[1], target[2]);
            d.statusText_$eq("Scooped " + ctrl.scoopedCount + "/" + ctrl.scoopTargetCount + "\n" + AIDroneManager.trimStatus(ctrl.scoopFilter));
        } else {
            this.pathfinder.navigateToward(ctrl, tx, target[1] + 1.35, tz, 0.1f);
            d.statusText_$eq("To " + AIDroneManager.trimStatus(ctrl.scoopFilter) + "\n" + (int) dist + "m");
        }
    }
}
