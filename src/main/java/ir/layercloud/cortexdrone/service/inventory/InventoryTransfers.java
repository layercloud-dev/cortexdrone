package ir.layercloud.cortexdrone.service.inventory;

import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * Pure slot-transfer arithmetic shared by every inventory path.
 *
 * <p>These rules decide how many items actually move, so they are the place
 * where a bug creates or destroys items. They are deliberately free of the
 * OpenComputers drone types (no {@code Drone}, no world, no logging) so they
 * can be unit-tested against plain {@link IInventory} fakes.</p>
 */
public final class InventoryTransfers {
    private InventoryTransfers() {
    }

    /**
     * Inserts as much of {@code stack} as {@code inv} will accept.
     *
     * <p>Existing matching stacks are topped up first, then empty slots are
     * filled. {@code stack.stackSize} is decremented by the amount moved; the
     * caller owns the remainder.</p>
     *
     * @return the number of items inserted
     */
    public static int insertIntoInventory(IInventory inv, ItemStack stack) {
        if (inv == null || stack == null || stack.stackSize <= 0) return 0;
        int inserted = 0;
        int size = inv.getSizeInventory();

        for (int i = 0; i < size && stack.stackSize > 0; i++) {
            ItemStack existing = inv.getStackInSlot(i);
            if (existing != null && existing.isItemEqual(stack)
                    && ItemStack.areItemStackTagsEqual(existing, stack)) {
                int max = Math.min(inv.getInventoryStackLimit(), existing.getMaxStackSize());
                int space = max - existing.stackSize;
                if (space > 0) {
                    int add = Math.min(space, stack.stackSize);
                    ItemStack updated = existing.copy();
                    updated.stackSize += add;
                    inv.setInventorySlotContents(i, updated);
                    stack.stackSize -= add;
                    inserted += add;
                }
            }
        }

        for (int i = 0; i < size && stack.stackSize > 0; i++) {
            ItemStack existing = inv.getStackInSlot(i);
            if (existing == null) {
                int max = Math.min(inv.getInventoryStackLimit(), stack.getMaxStackSize());
                int add = Math.min(max, stack.stackSize);
                ItemStack copy = stack.copy();
                copy.stackSize = add;
                inv.setInventorySlotContents(i, copy);
                stack.stackSize -= add;
                inserted += add;
            }
        }

        if (inserted > 0) {
            inv.markDirty();
        }
        return inserted;
    }

    /**
     * Moves as much of one source slot as the targets will accept.
     *
     * <p>The stack is copied once and the <em>same</em> working copy is offered
     * to every target; whatever the targets cannot take is written back to the
     * source slot. This is the only safe way to chain inserts: inserting a
     * fresh full copy per container creates items whenever a container is
     * partially filled.</p>
     *
     * @return the number of items actually moved
     */
    public static int spreadStackAcross(IInventory source, int slot,
                                        List<? extends IInventory> targets) {
        if (source == null || targets == null || targets.isEmpty()) return 0;
        if (slot < 0 || slot >= source.getSizeInventory()) return 0;
        ItemStack existing = source.getStackInSlot(slot);
        if (existing == null || existing.stackSize <= 0) return 0;

        ItemStack working = existing.copy();
        int moved = 0;
        for (IInventory target : targets) {
            if (working.stackSize <= 0) break;
            if (target == null || target == source) continue;
            moved += insertIntoInventory(target, working);
        }
        if (moved <= 0) return 0;
        source.setInventorySlotContents(slot, working.stackSize > 0 ? working : null);
        source.markDirty();
        return moved;
    }

    /**
     * Empties one source slot into a sequence of containers, one at a time.
     *
     * <p>Unlike {@link #spreadStackAcross}, this variant lets the caller wrap
     * each insert (for example in a container open/close animation) and keeps
     * going after a partial fill. It still moves the same working stack, so the
     * number of items is conserved.</p>
     *
     * @param inserter performs one insert into the given target
     * @return {@code true} when the source slot was fully emptied
     */
    public static boolean drainSlotAcross(IInventory source, int slot,
                                          List<? extends IInventory> targets,
                                          TransferStep inserter) {
        if (source == null || targets == null || slot < 0 || slot >= source.getSizeInventory()) {
            return false;
        }
        ItemStack existing = source.getStackInSlot(slot);
        if (existing == null || existing.stackSize <= 0) return false;

        ItemStack working = existing.copy();
        boolean touched = false;
        for (IInventory target : targets) {
            if (working.stackSize <= 0) break;
            if (target == null || target == source || inserter == null) continue;
            int moved = inserter.insert(target, working);
            if (moved > 0) touched = true;
        }
        if (touched) {
            source.setInventorySlotContents(slot, working.stackSize > 0 ? working : null);
            source.markDirty();
        }
        return working.stackSize <= 0;
    }

    /**
     * One insert performed while a container is open.
     */
    public interface TransferStep {
        int insert(IInventory target, ItemStack working);
    }

    /**
     * The best slot to place {@code filter} from.
     *
     * <p>A bucket query accepts any item; a block query skips non-block and
     * liquid items. Matching scores, with the highest score winning.</p>
     *
     * @return the slot index, or {@code -1} when nothing matches
     */
    public static int findPlaceableSlot(IInventory inv, String filter) {
        if (inv == null) return -1;
        String f = (filter == null || filter.trim().isEmpty()) ? "any" : filter.trim().toLowerCase();
        boolean matchAnyBlock = f.equals("any") || f.equals("block") || f.equals("solid");
        boolean isBucketQuery = f.contains("bucket");
        int size = inv.getSizeInventory();
        int bestSlot = -1;
        int bestScore = 0;
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
            Item item = st.getItem();
            if (!isBucketQuery) {
                Block b = Block.getBlockFromItem(item);
                if (b == null || b == Blocks.air || ItemClassifier.isLiquidBlock(b)) {
                    continue;
                }
            }
            if (matchAnyBlock) {
                return i;
            }
            int score = ItemClassifier.scoreBlockOrItemMatch(st.getDisplayName(), ItemClassifier.getItemIdentifier(st), st.getItemDamage(), f);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /**
     * True when no slot can accept another item of any kind.
     */
    public static boolean isInventoryFull(IInventory inv) {
        if (inv == null) return true;
        int size = inv.getSizeInventory();
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st == null || st.stackSize < Math.min(inv.getInventoryStackLimit(), st.getMaxStackSize())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Total number of items across every slot.
     */
    public static int countInventoryItems(IInventory inv) {
        if (inv == null) return 0;
        int total = 0;
        int size = inv.getSizeInventory();
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st != null && st.stackSize > 0) {
                total += st.stackSize;
            }
        }
        return total;
    }
}
