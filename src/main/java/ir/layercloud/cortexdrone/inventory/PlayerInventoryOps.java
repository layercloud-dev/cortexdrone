package ir.layercloud.cortexdrone.inventory;

import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.tileentity.TileEntityFurnace;

/**
 * Handles item transfers between the drone and player inventories
 * (`takeFromPlayerInventory`, `giveToPlayerInventory`, `moveInventorySlot`).
 */
@RequiredArgsConstructor
public class PlayerInventoryOps {
    private final DroneLogger logger;

    public int takeFromPlayerInventory(DroneController ctrl, EntityPlayerMP player, String filter, int maxCount) {
        if (ctrl == null || ctrl.drone == null || player == null || player.field_71071_by == null) return 0;
        IInventory droneInv = ctrl.drone.mainInventory();
        IInventory playerInv = player.field_71071_by;

        if (InventoryService.isInventoryFull(droneInv)) {
            int freed = 0;
            for (int i = 0; i < droneInv.func_70302_i_() && freed < 2; i++) {
                ItemStack st = droneInv.func_70301_a(i);
                if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
                int meta = st.func_77960_j();
                String disp = st.func_82833_r();
                String combined = ItemClassifier.getItemIdentifier(st);
                if (!ItemClassifier.matchesBlockOrItemName(disp, combined, meta, filter)) {
                    ItemStack copy = st.func_77946_l();
                    if (player.field_71071_by.func_70441_a(copy)) {
                        droneInv.func_70299_a(i, copy.field_77994_a > 0 ? copy : null);
                        freed++;
                    }
                }
            }
            droneInv.func_70296_d();
        }

        int moved = 0;
        String f = (filter == null || filter.trim().isEmpty()) ? "any" : filter.trim().toLowerCase();
        boolean matchAny = f.equals("any") || f.equals("all") || f.equals("*") || f.equals("block") || f.equals("solid");
        int mainSlots = Math.min(36, playerInv.func_70302_i_());
        for (int i = 0; i < mainSlots && moved < maxCount; i++) {
            ItemStack st = playerInv.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
            int meta = st.func_77960_j();
            String disp = st.func_82833_r();
            String combined = ItemClassifier.getItemIdentifier(st);
            boolean ok = matchAny
                ? (f.equals("all") || f.equals("*") || st.func_77973_b() instanceof ItemBlock)
                : ItemClassifier.matchesBlockOrItemName(disp, combined, meta, f);
            if (!ok) continue;

            int take = Math.min(st.field_77994_a, maxCount - moved);
            ItemStack toInsert = st.func_77946_l();
            toInsert.field_77994_a = take;
            int ins = InventoryService.insertIntoInventory(droneInv, toInsert);
            if (ins > 0) {
                playerInv.func_70298_a(i, ins);
                moved += ins;
            }
        }
        if (moved > 0) {
            droneInv.func_70296_d();
            InventoryService.syncPlayerInventory(player);
            this.logger.log("INV", ctrl, "Took " + moved + " item(s) matching '" + filter + "' from " + player.func_70005_c_(), "");
        }
        return moved;
    }

    public int giveToPlayerInventory(DroneController ctrl, EntityPlayerMP player, String filter, int maxCount) {
        if (ctrl == null || ctrl.drone == null || player == null || player.field_71071_by == null) return 0;
        IInventory droneInv = ctrl.drone.mainInventory();
        String f = (filter == null || filter.trim().isEmpty()) ? "all" : filter.trim().toLowerCase();
        boolean matchAll = f.equals("all") || f.equals("any") || f.equals("*");
        int given = 0;

        for (int i = 0; i < droneInv.func_70302_i_() && given < maxCount; i++) {
            ItemStack st = droneInv.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
            int meta = st.func_77960_j();
            String disp = st.func_82833_r();
            String combined = ItemClassifier.getItemIdentifier(st);
            if (!matchAll && !ItemClassifier.matchesBlockOrItemName(disp, combined, meta, f)) continue;

            int take = Math.min(st.field_77994_a, maxCount - given);
            ItemStack giveStack = st.func_77946_l();
            giveStack.field_77994_a = take;
            int before = giveStack.field_77994_a;
            player.field_71071_by.func_70441_a(giveStack);
            int accepted = before - giveStack.field_77994_a;
            if (giveStack.field_77994_a > 0) {
                EntityItem ei = new EntityItem(player.field_70170_p, player.field_70165_t, player.field_70163_u + 0.5, player.field_70161_v, giveStack);
                ei.field_145804_b = 0;
                player.field_70170_p.func_72838_d(ei);
                accepted = before;
            }
            if (accepted > 0) {
                droneInv.func_70298_a(i, accepted);
                given += accepted;
            }
        }
        if (given > 0) {
            droneInv.func_70296_d();
            InventoryService.syncPlayerInventory(player);
            this.logger.log("INV", ctrl, "Gave " + given + " item(s) to " + player.func_70005_c_(), "filter=" + f);
        } else if (ctrl.mode == Mode.MINE || !ctrl.mineQueue.isEmpty()) {
            ctrl.returnToOwnerAfterMine = true;
        }
        return given;
    }

    public int moveInventorySlot(IInventory srcInv, int srcSlot, String itemFilter, IInventory dstInv, int dstSlot, int maxCount, EntityPlayerMP syncPlayer) {
        if (srcInv == null || dstInv == null) return 0;
        if (srcSlot < 0) {
            for (int i = 0; i < srcInv.func_70302_i_(); i++) {
                ItemStack st = srcInv.func_70301_a(i);
                if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
                if (itemFilter != null && !itemFilter.trim().isEmpty()) {
                    int meta = st.func_77960_j();
                    String disp = st.func_82833_r();
                    String combined = ItemClassifier.getItemIdentifier(st);
                    if (ItemClassifier.matchesBlockOrItemName(disp, combined, meta, itemFilter)) {
                        srcSlot = i;
                        break;
                    }
                } else if (dstInv instanceof TileEntityFurnace && dstSlot == 0) {
                    if (FurnaceRecipes.func_77602_a().func_151395_a(st) != null) {
                        srcSlot = i;
                        break;
                    }
                } else if (dstInv instanceof TileEntityFurnace && dstSlot == 1) {
                    if (TileEntityFurnace.func_145954_b(st)) {
                        srcSlot = i;
                        break;
                    }
                } else {
                    srcSlot = i;
                    break;
                }
            }
        }
        if (srcSlot < 0 || srcSlot >= srcInv.func_70302_i_()) return 0;
        ItemStack srcStack = srcInv.func_70301_a(srcSlot);
        if (srcStack == null || srcStack.field_77994_a <= 0) return 0;

        int moveAmt = Math.min(srcStack.field_77994_a, Math.max(1, maxCount));
        if (dstSlot < 0 || dstSlot >= dstInv.func_70302_i_()) {
            ItemStack copy = srcStack.func_77946_l();
            copy.field_77994_a = moveAmt;
            int ins = InventoryService.insertIntoInventory(dstInv, copy);
            if (ins > 0) {
                srcInv.func_70298_a(srcSlot, ins);
                srcInv.func_70296_d();
                dstInv.func_70296_d();
                InventoryService.syncPlayerInventory(syncPlayer);
            }
            return ins;
        }

        ItemStack dstStack = dstInv.func_70301_a(dstSlot);
        if (dstStack == null) {
            ItemStack moved = srcStack.func_77946_l();
            moved.field_77994_a = moveAmt;
            dstInv.func_70299_a(dstSlot, moved);
            srcInv.func_70298_a(srcSlot, moveAmt);
            srcInv.func_70296_d();
            dstInv.func_70296_d();
            InventoryService.syncPlayerInventory(syncPlayer);
            return moveAmt;
        } else if (dstStack.func_77969_a(srcStack) && ItemStack.func_77970_a(dstStack, srcStack)) {
            int maxLim = Math.min(dstInv.func_70297_j_(), dstStack.func_77976_d());
            int space = Math.max(0, maxLim - dstStack.field_77994_a);
            int add = Math.min(space, moveAmt);
            if (add > 0) {
                ItemStack updated = dstStack.func_77946_l();
                updated.field_77994_a += add;
                dstInv.func_70299_a(dstSlot, updated);
                srcInv.func_70298_a(srcSlot, add);
                srcInv.func_70296_d();
                dstInv.func_70296_d();
                InventoryService.syncPlayerInventory(syncPlayer);
            }
            return add;
        }
        return 0;
    }
}
