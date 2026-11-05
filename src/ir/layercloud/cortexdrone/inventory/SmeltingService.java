package ir.layercloud.cortexdrone.inventory;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.FlightPathfinder;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import li.cil.oc.common.entity.Drone;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

public class SmeltingService {
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final FlightPathfinder pathfinder;
    private final InventoryService inventoryService;

    public SmeltingService(AIDroneManager manager, DroneLogger logger, FlightPathfinder pathfinder, InventoryService inventoryService) {
        this.manager = manager;
        this.logger = logger;
        this.pathfinder = pathfinder;
        this.inventoryService = inventoryService;
    }

    public List<TileEntity> findAllFurnaceTiles(final World world, final double x, final double y, final double z, int radius) {
        // Fast path: use loaded tile entities only — see InventoryService.findAllInventoryTiles.
        List<TileEntity> tiles = new ArrayList<TileEntity>();
        double r2 = (double) radius * radius;
        if (world.field_147482_g != null) {
            for (Object o : world.field_147482_g) {
                if (!(o instanceof TileEntity)) continue;
                TileEntity te = (TileEntity) o;
                int bx = te.field_145851_c;
                int by = te.field_145848_d;
                int bz = te.field_145849_e;
                double dx = bx + 0.5 - x;
                double dy = by + 0.5 - y;
                double dz = bz + 0.5 - z;
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                if (ItemClassifier.isFurnaceTile(world, te)) {
                    tiles.add(te);
                }
            }
        }
        // Fallback only when the loaded-list lookup returned nothing (e.g. no list):
        // keep the dense cube scan so callers never lose furnaces, but never both.
        if (tiles.isEmpty() && world.field_147482_g == null) {
            int cx = MathHelper.func_76128_c(x);
            int cy = MathHelper.func_76128_c(y);
            int cz = MathHelper.func_76128_c(z);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int bx = cx + dx, by = cy + dy, bz = cz + dz;
                        if (by < 1 || by > 254) continue;
                        TileEntity te = world.func_147438_o(bx, by, bz);
                        if (ItemClassifier.isFurnaceTile(world, te)) {
                            tiles.add(te);
                        }
                    }
                }
            }
        }
        Collections.sort(tiles, new Comparator<TileEntity>() {
            @Override
            public int compare(TileEntity a, TileEntity b) {
                double da = (a.field_145851_c + 0.5 - x) * (a.field_145851_c + 0.5 - x)
                    + (a.field_145848_d + 0.5 - y) * (a.field_145848_d + 0.5 - y)
                    + (a.field_145849_e + 0.5 - z) * (a.field_145849_e + 0.5 - z);
                double db = (b.field_145851_c + 0.5 - x) * (b.field_145851_c + 0.5 - x)
                    + (b.field_145848_d + 0.5 - y) * (b.field_145848_d + 0.5 - y)
                    + (b.field_145849_e + 0.5 - z) * (b.field_145849_e + 0.5 - z);
                return Double.compare(da, db);
            }
        });
        return tiles;
    }

    private int pullSmeltableFromInventory(IInventory src, IInventory dst, String filter, int maxCount, boolean isPlayerMain) {
        if (src == null || dst == null || maxCount <= 0) return 0;
        int moved = 0;
        int limit = isPlayerMain ? Math.min(36, src.func_70302_i_()) : src.func_70302_i_();
        for (int i = 0; i < limit && moved < maxCount; i++) {
            ItemStack st = src.func_70301_a(i);
            if (!ItemClassifier.matchesSmeltFilter(st, filter)) continue;
            int take = Math.min(st.field_77994_a, maxCount - moved);
            ItemStack copy = st.func_77946_l();
            copy.field_77994_a = take;
            int ins = InventoryService.insertIntoInventory(dst, copy);
            if (ins > 0) {
                src.func_70298_a(i, ins);
                moved += ins;
            }
        }
        if (moved > 0) src.func_70296_d();
        return moved;
    }

    private int pullFuelFromInventory(IInventory src, IInventory dst, int maxCount, boolean isPlayerMain) {
        if (src == null || dst == null || maxCount <= 0) return 0;
        int moved = 0;
        int limit = isPlayerMain ? Math.min(36, src.func_70302_i_()) : src.func_70302_i_();
        for (int i = 0; i < limit && moved < maxCount; i++) {
            ItemStack st = src.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
            // Dynamically detect high-efficiency furnace fuel (>= 800 ticks, e.g. coal/lignite/coke/charcoal/blaze rod) via TileEntityFurnace.getItemBurnTime
            int burnTicks = TileEntityFurnace.func_145952_a(st);
            if (burnTicks >= 800 && st.func_77976_d() > 1 && FurnaceRecipes.func_77602_a().func_151395_a(st) == null) {
                int take = Math.min(st.field_77994_a, maxCount - moved);
                ItemStack copy = st.func_77946_l();
                copy.field_77994_a = take;
                int ins = InventoryService.insertIntoInventory(dst, copy);
                if (ins > 0) {
                    src.func_70298_a(i, ins);
                    moved += ins;
                }
            }
        }
        if (moved > 0) src.func_70296_d();
        return moved;
    }

    public int startSmeltTask(DroneController ctrl, EntityPlayerMP player, String rawFilter, int maxCount) {
        Drone d = ctrl.drone;
        World world = d.field_70170_p;
        String filter = (rawFilter == null || rawFilter.trim().isEmpty()) ? "meat" : rawFilter.trim();
        int wanted = Math.max(1, Math.min(256, maxCount));
        ctrl.smeltFilter = filter;
        ctrl.smeltTargetCount = wanted;
        ctrl.smeltedCount = 0;

        double refX = player != null && player.field_70170_p == world ? player.field_70165_t : d.field_70165_t;
        double refY = player != null && player.field_70170_p == world ? player.field_70163_u : d.field_70163_u;
        double refZ = player != null && player.field_70170_p == world ? player.field_70161_v : d.field_70161_v;

        List<TileEntity> furnaces = findAllFurnaceTiles(world, refX, refY, refZ, 36);
        if (furnaces.isEmpty()) {
            furnaces = findAllFurnaceTiles(world, d.field_70165_t, d.field_70163_u, d.field_70161_v, 36);
        }
        if (furnaces.isEmpty()) {
            this.logger.log("MINE", ctrl, "No furnace found within 36m for smelting '" + filter + "'", "");
            return 0;
        }

        TileEntity chosenFurnace = furnaces.get(0);
        for (TileEntity fte : furnaces) {
            if (fte instanceof IInventory) {
                IInventory finv = (IInventory) fte;
                ItemStack s0 = finv.func_70301_a(0);
                ItemStack s1 = finv.func_70302_i_() > 1 ? finv.func_70301_a(1) : null;
                if (ItemClassifier.matchesSmeltFilter(s0, filter) || (s1 != null && s1.field_77994_a > 0)) {
                    chosenFurnace = fte;
                    break;
                }
            }
        }
        ctrl.smeltFurnacePos = new int[]{chosenFurnace.field_145851_c, chosenFurnace.field_145848_d, chosenFurnace.field_145849_e};

        IInventory droneInv = d.mainInventory();
        int haveSmeltable = 0;
        for (int i = 0; i < droneInv.func_70302_i_(); i++) {
            ItemStack st = droneInv.func_70301_a(i);
            if (ItemClassifier.matchesSmeltFilter(st, filter)) {
                haveSmeltable += st.field_77994_a;
            }
        }

        if (haveSmeltable < wanted && player != null && player.field_71071_by != null) {
            int pulledPlayer = pullSmeltableFromInventory(player.field_71071_by, droneInv, filter, wanted - haveSmeltable, true);
            if (pulledPlayer > 0) {
                InventoryService.syncPlayerInventory(player);
                haveSmeltable += pulledPlayer;
            }
        }

        if (haveSmeltable < wanted) {
            List<TileEntity> chests = this.inventoryService.findAllInventoryTiles(world, refX, refY, refZ, 28);
            for (TileEntity cte : chests) {
                if (haveSmeltable >= wanted || InventoryService.isInventoryFull(droneInv)) break;
                if (cte instanceof IInventory) {
                    haveSmeltable += pullSmeltableFromInventory((IInventory) cte, droneInv, filter, wanted - haveSmeltable, false);
                }
            }
        }

        if (chosenFurnace instanceof IInventory) {
            IInventory finv = (IInventory) chosenFurnace;
            ItemStack s0 = finv.func_70301_a(0);
            if (ItemClassifier.matchesSmeltFilter(s0, filter)) {
                haveSmeltable += s0.field_77994_a;
            }
            ItemStack s1 = finv.func_70302_i_() > 1 ? finv.func_70301_a(1) : null;
            if ((s1 == null || s1.field_77994_a == 0) && !InventoryService.isInventoryFull(droneInv)) {
                List<TileEntity> chests = this.inventoryService.findAllInventoryTiles(world, refX, refY, refZ, 28);
                for (TileEntity cte : chests) {
                    if (cte instanceof IInventory && pullFuelFromInventory((IInventory) cte, droneInv, 8, false) > 0) {
                        break;
                    }
                }
            }
        }

        if (haveSmeltable > 0) {
            ctrl.smeltTargetCount = Math.min(wanted, haveSmeltable);
            this.manager.setDroneMode(ctrl, Mode.SMELT, "Smelting '" + filter + "' (count=" + ctrl.smeltTargetCount + ") at ("
                + ctrl.smeltFurnacePos[0] + "," + ctrl.smeltFurnacePos[1] + "," + ctrl.smeltFurnacePos[2] + ")");
            d.lightColor_$eq(0xFF6600);
            d.statusText_$eq("Cooking 0/" + ctrl.smeltTargetCount + "\n" + AIDroneManager.trimStatus(filter));
        }
        return haveSmeltable;
    }

    public void tickSmeltBehavior(DroneController ctrl, EntityPlayerMP owner) {
        Drone d = ctrl.drone;
        World world = d.field_70170_p;
        d.lightColor_$eq(0xFF6600);

        if (ctrl.smeltFurnacePos == null) {
            List<TileEntity> furnaces = findAllFurnaceTiles(world, d.field_70165_t, d.field_70163_u, d.field_70161_v, 36);
            if (furnaces.isEmpty()) {
                this.manager.setDroneMode(ctrl, (!ctrl.isScriptRunning() && ctrl.smeltedCount > 0) ? Mode.DROP : Mode.FOLLOW, "No furnace found");
                return;
            }
            TileEntity fte = furnaces.get(0);
            ctrl.smeltFurnacePos = new int[]{fte.field_145851_c, fte.field_145848_d, fte.field_145849_e};
        }

        int fx = ctrl.smeltFurnacePos[0], fy = ctrl.smeltFurnacePos[1], fz = ctrl.smeltFurnacePos[2];
        TileEntity te = world.func_147438_o(fx, fy, fz);
        if (!(te instanceof IInventory)) {
            ctrl.smeltFurnacePos = null;
            return;
        }
        IInventory finv = (IInventory) te;
        IInventory droneInv = d.mainInventory();

        double[] standoff = FlightPathfinder.findMiningStandoff(world, d, fx, fy, fz);
        double sx = standoff != null ? standoff[0] : (fx + 0.5);
        double sy = standoff != null ? standoff[1] : (fy + 1.2);
        double sz = standoff != null ? standoff[2] : (fz + 0.5);
        double distFurnace = d.func_70011_f(fx + 0.5, fy + 0.5, fz + 0.5);

        if (distFurnace > 3.8) {
            this.pathfinder.navigateToward(ctrl, sx, sy, sz, 0.1f);
            d.statusText_$eq("To Furnace\n" + (int) distFurnace + "m");
            return;
        }

        FlightPathfinder.setDroneFlightTarget(d, sx, sy, sz, 0.08f);

        // 1) Extract cooked output from slot 2
        if (finv.func_70302_i_() > 2) {
            ItemStack outSt = finv.func_70301_a(2);
            if (outSt != null && outSt.field_77994_a > 0) {
                ItemStack copy = outSt.func_77946_l();
                int ins = InventoryService.insertIntoInventory(droneInv, copy);
                if (ins == 0) {
                    // Free 1 non-smeltable byproduct slot (e.g. wool/feathers) so cooked output always fits in droneInv
                    for (int i = droneInv.func_70302_i_() - 1; i >= 0; i--) {
                        ItemStack st = droneInv.func_70301_a(i);
                        if (st != null && !ItemClassifier.matchesSmeltFilter(st, ctrl.smeltFilter) && !st.func_77969_a(outSt)) {
                            if (owner != null && owner.field_71071_by != null && owner.field_71071_by.func_70441_a(st.func_77946_l())) {
                                droneInv.func_70299_a(i, null);
                                InventoryService.syncPlayerInventory(owner);
                                break;
                            }
                        }
                    }
                    ins = InventoryService.insertIntoInventory(droneInv, copy);
                }
                if (ins == 0 && owner != null && owner.field_71071_by != null) {
                    int before = copy.field_77994_a;
                    owner.field_71071_by.func_70441_a(copy);
                    ins = before - copy.field_77994_a;
                    InventoryService.syncPlayerInventory(owner);
                }
                if (ins > 0) {
                    finv.func_70298_a(2, ins);
                    finv.func_70296_d();
                    ctrl.smeltedCount += ins;
                }
            }
        }

        // 2) Clear non-smeltable junk from slot 0
        ItemStack inSt = finv.func_70301_a(0);
        if (inSt != null && inSt.field_77994_a > 0) {
            ItemStack smeltRes = FurnaceRecipes.func_77602_a().func_151395_a(inSt);
            if (smeltRes == null) {
                ItemStack junk = inSt.func_77946_l();
                int ins = InventoryService.insertIntoInventory(droneInv, junk);
                if (ins > 0) {
                    finv.func_70298_a(0, ins);
                } else {
                    finv.func_70299_a(0, null);
                    EntityItem ei = new EntityItem(world, fx + 0.5, fy + 1.1, fz + 0.5, junk);
                    world.func_72838_d(ei);
                }
                finv.func_70296_d();
                inSt = finv.func_70301_a(0);
            }
        }

        // 3) Load matching smeltable items from drone into slot 0
        for (int i = 0; i < droneInv.func_70302_i_(); i++) {
            ItemStack dst = droneInv.func_70301_a(i);
            if (!ItemClassifier.matchesSmeltFilter(dst, ctrl.smeltFilter)) continue;
            inSt = finv.func_70301_a(0);
            if (inSt == null || (inSt.func_77969_a(dst) && ItemStack.func_77970_a(inSt, dst) && inSt.field_77994_a < inSt.func_77976_d())) {
                this.inventoryService.moveInventorySlot(droneInv, i, null, finv, 0, 64, owner);
                inSt = finv.func_70301_a(0);
                break;
            }
        }

        // 4) Load fuel from drone into slot 1 if needed
        if (finv.func_70302_i_() > 1) {
            ItemStack fuelSt = finv.func_70301_a(1);
            if (fuelSt == null || fuelSt.field_77994_a == 0) {
                for (int i = 0; i < droneInv.func_70302_i_(); i++) {
                    ItemStack dst = droneInv.func_70301_a(i);
                    if (dst != null && dst.field_77994_a > 0 && TileEntityFurnace.func_145954_b(dst) && !ItemClassifier.matchesSmeltFilter(dst, ctrl.smeltFilter)) {
                        this.inventoryService.moveInventorySlot(droneInv, i, null, finv, 1, 16, owner);
                        break;
                    }
                }
            }
        }

        // 5) Accelerate smelting while drone actively tends the furnace
        inSt = finv.func_70301_a(0);
        if (inSt != null && inSt.field_77994_a > 0 && te instanceof TileEntityFurnace) {
            TileEntityFurnace tf = (TileEntityFurnace) te;
            if (tf.field_145956_a <= 10) {
                tf.field_145956_a = 400;
                tf.field_145963_i = 400;
            }
            tf.field_145961_j += 65;
            if (tf.field_145961_j >= 195) {
                tf.field_145961_j = 0;
                tf.func_145949_j();
                tf.func_70296_d();
                if (world instanceof WorldServer) {
                    ((WorldServer) world).func_147487_a("flame", fx + 0.5, fy + 0.6, fz + 0.5, 5, 0.25, 0.25, 0.25, 0.02);
                }
                world.func_72908_a(fx + 0.5, fy + 0.5, fz + 0.5, "random.fizz", 0.3f, 1.4f);
            }
            d.statusText_$eq("Cooking " + ctrl.smeltedCount + "/" + ctrl.smeltTargetCount + "\n" + AIDroneManager.trimStatus(inSt.func_82833_r()));
            return;
        }

        boolean moreInDrone = false;
        for (int i = 0; i < droneInv.func_70302_i_(); i++) {
            if (ItemClassifier.matchesSmeltFilter(droneInv.func_70301_a(i), ctrl.smeltFilter)) {
                moreInDrone = true;
                break;
            }
        }
        ItemStack outRem = finv.func_70302_i_() > 2 ? finv.func_70301_a(2) : null;
        if (!moreInDrone && (inSt == null || inSt.field_77994_a == 0) && (outRem == null || outRem.field_77994_a == 0)) {
            if (!ctrl.isScriptRunning() && owner != null) {
                this.manager.droneSay(owner, ctrl, "Finished cooking/smelting " + ctrl.smeltedCount + " item(s)! Delivering them to your inventory.");
            }
            this.logger.log("MINE", ctrl, "Completed furnace smelting task", "smelted=" + ctrl.smeltedCount + " scriptRunning=" + ctrl.isScriptRunning());
            this.manager.setDroneMode(ctrl, ctrl.isScriptRunning() ? Mode.FOLLOW : Mode.DROP, "Smelting completed");
        }
    }
}
