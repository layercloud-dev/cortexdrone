package ir.layercloud.cortexdrone.service.combat;

import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * Drops live TNT from the drone, the way a bomber releases a charge: the drone
 * stays high and moves off, the charge falls and detonates on its own fuse.
 *
 * <p>The world mutation is deliberately explicit rather than
 * {@code Item.onItemRightClick}: vanilla's TNT item is placed as a block, which
 * is not a drop and cannot be fused to a chosen tick. This class owns the two
 * facts that must stay true:</p>
 * <ul>
 *   <li>a charge is only released from a caller-checked safe height, and</li>
 *   <li>the inventory is debited by exactly the number of charges spawned.</li>
 * </ul>
 */
public final class TntDrop {
    private TntDrop() {
    }

    /**
     * The outcome of one release.
     */
    public static final class Release {
        public final int dropped;
        public final int slot;
        public final double x;
        public final double y;
        public final double z;
        public final int fuse;

        Release(int dropped, int slot, double x, double y, double z, int fuse) {
            this.dropped = dropped;
            this.slot = slot;
            this.x = x;
            this.y = y;
            this.z = z;
            this.fuse = fuse;
        }
    }

    /**
     * Finds a TNT stack in the drone inventory.
     *
     * @return the slot, or {@code -1}
     */
    public static int findTntSlot(IInventory inventory) {
        if (inventory == null) return -1;
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            if (isTnt(inventory.getStackInSlot(i))) return i;
        }
        return -1;
    }

    /**
     * True when the stack is a TNT charge, by item, registry name or display name.
     */
    public static boolean isTnt(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;
        String id = ItemClassifier.getItemIdentifier(stack);
        String display = stack.getDisplayName();
        return matchesTnt(id) || matchesTnt(display);
    }

    public static boolean matchesTnt(String text) {
        if (text == null) return false;
        String value = text.toLowerCase(java.util.Locale.ROOT);
        return value.contains("tnt") || value.contains("dynamite") || value.contains("c4");
    }

    /**
     * Releases up to {@code count} charges from one slot, spread along X/Z.
     *
     * <p>Charges are spawned with a small horizontal spread so several do not
     * fuse into one another. The stack is debited through
     * {@link IInventory#decrStackSize(int, int)} so a drone inventory that is
     * backed by OpenComputers' own storage stays consistent.</p>
     */
    public static Release release(World world, IInventory inventory, int slot,
                                  double x, double y, double z, int count, int fuseTicks,
                                  double spread, EntityLivingBase placedBy) {
        int fuse = TntDropLimits.clampFuse(fuseTicks);
        if (world == null || inventory == null || slot < 0 || slot >= inventory.getSizeInventory()) {
            return new Release(0, slot, x, y, z, fuse);
        }
        ItemStack stack = inventory.getStackInSlot(slot);
        if (!isTnt(stack)) {
            return new Release(0, slot, x, y, z, fuse);
        }
        int wanted = Math.min(TntDropLimits.clampCount(count), stack.stackSize);
        int spawned = 0;
        for (int i = 0; i < wanted; i++) {
            double offset = wanted == 1 ? 0.0D
                    : ((i / (double) (wanted - 1)) - 0.5D) * 2.0D * spread;
            EntityTNTPrimed tnt = new EntityTNTPrimed(world, x + offset, y, z, placedBy);
            tnt.fuse = fuse;
            world.spawnEntityInWorld(tnt);
            spawned++;
        }
        if (spawned > 0) {
            inventory.decrStackSize(slot, spawned);
            inventory.markDirty();
        }
        return new Release(spawned, slot, x, y, z, fuse);
    }

    /**
     * Total TNT charges in an inventory; diagnostics and result reporting.
     */
    public static int countTnt(IInventory inventory) {
        if (inventory == null) return 0;
        int total = 0;
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (isTnt(stack)) total += stack.stackSize;
        }
        return total;
    }
}
