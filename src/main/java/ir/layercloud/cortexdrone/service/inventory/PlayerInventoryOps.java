package ir.layercloud.cortexdrone.service.inventory;

import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
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
        if (ctrl == null || ctrl.drone == null || player == null || player.inventory == null) return 0;
        IInventory droneInv = ctrl.drone.mainInventory();
        IInventory playerInv = player.inventory;

        if (InventoryService.isInventoryFull(droneInv)) {
            int freed = 0;
            for (int i = 0; i < droneInv.getSizeInventory() && freed < 2; i++) {
                ItemStack st = droneInv.getStackInSlot(i);
                if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
                int meta = st.getItemDamage();
                String disp = st.getDisplayName();
                String combined = ItemClassifier.getItemIdentifier(st);
                if (!ItemClassifier.matchesBlockOrItemName(disp, combined, meta, filter)) {
                    ItemStack copy = st.copy();
                    if (player.inventory.addItemStackToInventory(copy)) {
                        droneInv.setInventorySlotContents(i, copy.stackSize > 0 ? copy : null);
                        freed++;
                    }
                }
            }
            droneInv.markDirty();
        }

        int moved = 0;
        String f = (filter == null || filter.trim().isEmpty()) ? "any" : filter.trim().toLowerCase();
        boolean matchAny = f.equals("any") || f.equals("all") || f.equals("*") || f.equals("block") || f.equals("solid");
        int mainSlots = Math.min(36, playerInv.getSizeInventory());
        for (int i = 0; i < mainSlots && moved < maxCount; i++) {
            ItemStack st = playerInv.getStackInSlot(i);
            if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
            int meta = st.getItemDamage();
            String disp = st.getDisplayName();
            String combined = ItemClassifier.getItemIdentifier(st);
            boolean ok = matchAny
                    ? (f.equals("all") || f.equals("*") || st.getItem() instanceof ItemBlock)
                    : ItemClassifier.matchesBlockOrItemName(disp, combined, meta, f);
            if (!ok) continue;

            int take = Math.min(st.stackSize, maxCount - moved);
            ItemStack toInsert = st.copy();
            toInsert.stackSize = take;
            int ins = InventoryService.insertIntoInventory(droneInv, toInsert);
            if (ins > 0) {
                playerInv.decrStackSize(i, ins);
                moved += ins;
            }
        }
        if (moved > 0) {
            droneInv.markDirty();
            InventoryService.syncPlayerInventory(player);
            this.logger.log("INV", ctrl, "Took " + moved + " item(s) matching '" + filter + "' from " + player.getCommandSenderName(), "");
        }
        return moved;
    }

    public int giveToPlayerInventory(DroneController ctrl, EntityPlayerMP player, String filter, int maxCount) {
        if (ctrl == null || ctrl.drone == null || player == null || player.inventory == null) return 0;
        IInventory droneInv = ctrl.drone.mainInventory();
        String f = (filter == null || filter.trim().isEmpty()) ? "all" : filter.trim().toLowerCase();
        boolean matchAll = f.equals("all") || f.equals("any") || f.equals("*");
        int given = 0;

        for (int i = 0; i < droneInv.getSizeInventory() && given < maxCount; i++) {
            ItemStack st = droneInv.getStackInSlot(i);
            if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
            int meta = st.getItemDamage();
            String disp = st.getDisplayName();
            String combined = ItemClassifier.getItemIdentifier(st);
            if (!matchAll && !ItemClassifier.matchesBlockOrItemName(disp, combined, meta, f)) continue;

            int take = Math.min(st.stackSize, maxCount - given);
            ItemStack giveStack = st.copy();
            giveStack.stackSize = take;
            int before = giveStack.stackSize;
            player.inventory.addItemStackToInventory(giveStack);
            int accepted = before - giveStack.stackSize;
            if (giveStack.stackSize > 0) {
                EntityItem ei = new EntityItem(player.worldObj, player.posX, player.posY + 0.5, player.posZ, giveStack);
                ei.delayBeforeCanPickup = 0;
                player.worldObj.spawnEntityInWorld(ei);
                accepted = before;
            }
            if (accepted > 0) {
                droneInv.decrStackSize(i, accepted);
                given += accepted;
            }
        }
        if (given > 0) {
            droneInv.markDirty();
            InventoryService.syncPlayerInventory(player);
            this.logger.log("INV", ctrl, "Gave " + given + " item(s) to " + player.getCommandSenderName(), "filter=" + f);
        } else if (ctrl.mode == Mode.MINE || !ctrl.mineQueue.isEmpty()) {
            ctrl.returnToOwnerAfterMine = true;
        }
        return given;
    }

    public int moveInventorySlot(IInventory srcInv, int srcSlot, String itemFilter, IInventory dstInv, int dstSlot, int maxCount, EntityPlayerMP syncPlayer) {
        if (srcInv == null || dstInv == null) return 0;
        if (srcSlot < 0) {
            for (int i = 0; i < srcInv.getSizeInventory(); i++) {
                ItemStack st = srcInv.getStackInSlot(i);
                if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
                if (itemFilter != null && !itemFilter.trim().isEmpty()) {
                    int meta = st.getItemDamage();
                    String disp = st.getDisplayName();
                    String combined = ItemClassifier.getItemIdentifier(st);
                    if (ItemClassifier.matchesBlockOrItemName(disp, combined, meta, itemFilter)) {
                        srcSlot = i;
                        break;
                    }
                } else if (dstInv instanceof TileEntityFurnace && dstSlot == 0) {
                    if (FurnaceRecipes.smelting().getSmeltingResult(st) != null) {
                        srcSlot = i;
                        break;
                    }
                } else if (dstInv instanceof TileEntityFurnace && dstSlot == 1) {
                    if (TileEntityFurnace.isItemFuel(st)) {
                        srcSlot = i;
                        break;
                    }
                } else {
                    srcSlot = i;
                    break;
                }
            }
        }
        if (srcSlot < 0 || srcSlot >= srcInv.getSizeInventory()) return 0;
        ItemStack srcStack = srcInv.getStackInSlot(srcSlot);
        if (srcStack == null || srcStack.stackSize <= 0) return 0;

        int moveAmt = Math.min(srcStack.stackSize, Math.max(1, maxCount));
        if (dstSlot < 0 || dstSlot >= dstInv.getSizeInventory()) {
            ItemStack copy = srcStack.copy();
            copy.stackSize = moveAmt;
            int ins = InventoryService.insertIntoInventory(dstInv, copy);
            if (ins > 0) {
                srcInv.decrStackSize(srcSlot, ins);
                srcInv.markDirty();
                dstInv.markDirty();
                InventoryService.syncPlayerInventory(syncPlayer);
            }
            return ins;
        }

        ItemStack dstStack = dstInv.getStackInSlot(dstSlot);
        if (dstStack == null) {
            ItemStack moved = srcStack.copy();
            moved.stackSize = moveAmt;
            dstInv.setInventorySlotContents(dstSlot, moved);
            srcInv.decrStackSize(srcSlot, moveAmt);
            srcInv.markDirty();
            dstInv.markDirty();
            InventoryService.syncPlayerInventory(syncPlayer);
            return moveAmt;
        } else if (dstStack.isItemEqual(srcStack) && ItemStack.areItemStackTagsEqual(dstStack, srcStack)) {
            int maxLim = Math.min(dstInv.getInventoryStackLimit(), dstStack.getMaxStackSize());
            int space = Math.max(0, maxLim - dstStack.stackSize);
            int add = Math.min(space, moveAmt);
            if (add > 0) {
                ItemStack updated = dstStack.copy();
                updated.stackSize += add;
                dstInv.setInventorySlotContents(dstSlot, updated);
                srcInv.decrStackSize(srcSlot, add);
                srcInv.markDirty();
                dstInv.markDirty();
                InventoryService.syncPlayerInventory(syncPlayer);
            }
            return add;
        }
        return 0;
    }
}
