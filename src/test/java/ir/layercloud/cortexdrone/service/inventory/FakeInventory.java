package ir.layercloud.cortexdrone.service.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

/**
 * Minimal in-memory IInventory for transfer arithmetic tests.
 */
@SuppressWarnings("unused")
public class FakeInventory implements IInventory {
    private final ItemStack[] slots;
    private final int limit;
    boolean dirty;

    public FakeInventory(int size) {
        this(size, 64);
    }

    public FakeInventory(int size, int limit) {
        this.slots = new ItemStack[size];
        this.limit = limit;
    }

    public int totalItems() {
        int total = 0;
        for (ItemStack stack : this.slots) {
            if (stack != null) total += stack.stackSize;
        }
        return total;
    }

    public void prefill(int slot, ItemStack stack) {
        this.slots[slot] = stack;
    }

    @Override
    public int getSizeInventory() {
        return this.slots.length;
    }

    @Override
    public ItemStack getStackInSlot(int index) {
        return this.slots[index];
    }

    @Override
    public ItemStack decrStackSize(int index, int count) {
        ItemStack stack = this.slots[index];
        if (stack == null) return null;
        if (stack.stackSize <= count) {
            this.slots[index] = null;
            return stack;
        }
        ItemStack split = stack.splitStack(count);
        return split;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int index) {
        ItemStack stack = this.slots[index];
        this.slots[index] = null;
        return stack;
    }

    @Override
    public void setInventorySlotContents(int index, ItemStack stack) {
        this.slots[index] = stack;
    }

    @Override
    public String getInventoryName() {
        return "fake";
    }

    @Override
    public boolean hasCustomInventoryName() {
        return false;
    }

    @Override
    public int getInventoryStackLimit() {
        return this.limit;
    }

    @Override
    public void markDirty() {
        this.dirty = true;
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return true;
    }

    @Override
    public void openInventory() {
    }

    @Override
    public void closeInventory() {
    }

    @Override
    public boolean isItemValidForSlot(int index, ItemStack stack) {
        return true;
    }
}
