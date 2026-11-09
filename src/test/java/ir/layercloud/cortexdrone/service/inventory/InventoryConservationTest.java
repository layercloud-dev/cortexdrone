package ir.layercloud.cortexdrone.service.inventory;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Item conservation across container transfers.
 *
 * <p>Regression: {@code makeRoom} inserted a fresh full copy of a stack into
 * every candidate container and only cleared the source slot when the last
 * container accepted the whole stack. Two partially-filled chests therefore
 * created items out of nothing.</p>
 */
public class InventoryConservationTest {
    /** One shared item instance: distinct {@code new Item()} objects never stack. */
    private static final Item ITEM = new Item();

    private static ItemStack stack(int size) {
        return new ItemStack(ITEM, size);
    }

    @Test
    public void spreadingAStackAcrossPartiallyFilledContainersNeverDuplicates() {
        // Source: a full 64 stack. Two targets accept 10 and 6 items.
        FakeInventory source = new FakeInventory(1);
        source.prefill(0, stack(64));

        FakeInventory first = new FakeInventory(1);
        first.prefill(0, stack(54)); // 10 free
        FakeInventory second = new FakeInventory(1);
        second.prefill(0, stack(58)); // 6 free
        List<FakeInventory> targets = new ArrayList<>();
        targets.add(first);
        targets.add(second);

        int moved = InventoryTransfers.spreadStackAcross(source, 0, targets);

        int afterSource = source.totalItems();
        int afterTargets = first.totalItems() + second.totalItems();
        assertEquals("no items may be created", 64, afterSource + (afterTargets - 54 - 58));
        assertEquals("the source must shrink by exactly what moved", 64 - moved, afterSource);
        assertEquals("all possible space must be used", 16, moved);
    }

    @Test
    public void sourceSlotIsEmptiedWhenEverythingMoves() {
        FakeInventory source = new FakeInventory(1);
        source.prefill(0, stack(20));
        FakeInventory target = new FakeInventory(1);

        int moved = InventoryTransfers.spreadStackAcross(source, 0, java.util.Collections.singletonList(target));

        assertEquals(20, moved);
        assertNull("the moved slot must be cleared", source.getStackInSlot(0));
        assertEquals(20, target.totalItems());
    }

    @Test
    public void sourceKeepsTheRemainderWhenTargetsAreFull() {
        FakeInventory source = new FakeInventory(1);
        source.prefill(0, stack(64));
        FakeInventory full = new FakeInventory(1);
        full.prefill(0, stack(64));

        int moved = InventoryTransfers.spreadStackAcross(source, 0, java.util.Collections.singletonList(full));

        assertEquals(0, moved);
        assertEquals(64, source.totalItems());
    }

    @Test
    public void negativeOrEmptySourceIsIgnored() {
        FakeInventory source = new FakeInventory(1);
        assertEquals(0, InventoryTransfers.spreadStackAcross(source, 0, java.util.Collections.<FakeInventory>emptyList()));
        assertEquals(0, InventoryTransfers.spreadStackAcross(source, 0, null));
        assertEquals(0, InventoryTransfers.spreadStackAcross(null, 0, null));
    }

    /**
     * The exact {@code makeRoom} path: one working stack, per-container insert,
     * partial fills allowed. The old code copied the full stack per container.
     */
    @Test
    public void drainSlotAcrossConservesItemsThroughPartialContainers() {
        FakeInventory source = new FakeInventory(1);
        source.prefill(0, stack(16));
        FakeInventory first = new FakeInventory(1);
        first.prefill(0, stack(54));
        FakeInventory second = new FakeInventory(1);
        second.prefill(0, stack(58));
        List<FakeInventory> targets = new ArrayList<>();
        targets.add(first);
        targets.add(second);

        boolean drained = InventoryTransfers.drainSlotAcross(source, 0, targets,
                InventoryTransfers::insertIntoInventory);

        assertTrue("16 free slots exist, so the stack must be fully drained", drained);
        assertNull(source.getStackInSlot(0));
        assertEquals("10 + 6 items moved, nothing created", 64, first.totalItems());
        assertEquals(64, second.totalItems());
    }

    @Test
    public void drainSlotAcrossNeverInsertsAFullCopyIntoEveryContainer() {
        // The regression shape: 64 items, two containers with 10 and 6 free.
        // A fresh full copy per container would leave 64 in the source and add
        // 10 + 6 duplicated items.
        FakeInventory source = new FakeInventory(1);
        source.prefill(0, stack(64));
        FakeInventory first = new FakeInventory(1);
        first.prefill(0, stack(54));
        FakeInventory second = new FakeInventory(1);
        second.prefill(0, stack(58));
        List<FakeInventory> targets = new ArrayList<>();
        targets.add(first);
        targets.add(second);

        boolean drained = InventoryTransfers.drainSlotAcross(source, 0, targets,
                InventoryTransfers::insertIntoInventory);

        assertFalse("only 16 of 64 fit", drained);
        assertEquals("48 remain, exactly 16 moved", 48, source.totalItems());
        assertEquals(64, first.totalItems());
        assertEquals(64, second.totalItems());
        assertEquals("conservation: 48 + 64 + 64 == 64 + 54 + 58",
                64 + 54 + 58, source.totalItems() + first.totalItems() + second.totalItems());
    }

    @Test
    public void drainSlotAcrossKeepsTheRemainderWhenContainersFill() {
        FakeInventory source = new FakeInventory(1);
        source.prefill(0, stack(64));
        FakeInventory first = new FakeInventory(1);
        first.prefill(0, stack(60)); // 4 free
        FakeInventory second = new FakeInventory(1);
        second.prefill(0, stack(62)); // 2 free
        List<FakeInventory> targets = new ArrayList<>();
        targets.add(first);
        targets.add(second);

        boolean drained = InventoryTransfers.drainSlotAcross(source, 0, targets,
                InventoryTransfers::insertIntoInventory);

        assertFalse("only 6 of 64 fit", drained);
        assertEquals("the remainder stays in the source slot", 58, source.totalItems());
        assertEquals(64, first.totalItems());
        assertEquals(64, second.totalItems());
    }

    @Test
    public void drainSlotAcrossStopsWhenTheWorkingStackIsEmpty() {
        FakeInventory source = new FakeInventory(1);
        source.prefill(0, stack(8));
        FakeInventory roomy = new FakeInventory(2);
        FakeInventory untouched = new FakeInventory(1);
        List<FakeInventory> targets = new ArrayList<>();
        targets.add(roomy);
        targets.add(untouched);

        assertTrue(InventoryTransfers.drainSlotAcross(source, 0, targets,
                InventoryTransfers::insertIntoInventory));
        assertEquals(8, roomy.totalItems());
        assertEquals("the second container must not receive a duplicate", 0, untouched.totalItems());
    }
}
