package ir.layercloud.cortexdrone.service.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import org.junit.After;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * Container feedback must be visible.
 *
 * <p>Opening and closing in the same tick put both {@code addBlockEvent} calls
 * into one end-of-tick batch, so the client only ever saw
 * {@code numPlayersUsing = 0} and a chest lid never moved. The close is now
 * scheduled a few ticks later.</p>
 */
public class ContainerCloserTest {
    @After
    public void drainPendingCloses() {
        // Leave no scheduled close behind for another test's container.
        for (int i = 0; i < ContainerCloser.MIN_OPEN_TICKS + 1; i++) {
            ContainerCloser.tick();
        }
        ContainerCloser.clear();
    }

    @Test
    public void closeIsDeferredLongEnoughForTheLidToMove() {
        FakeContainer container = new FakeContainer();
        ContainerCloser.schedule(Collections.<IInventory>singletonList(container));

        for (int i = 1; i < ContainerCloser.MIN_OPEN_TICKS; i++) {
            ContainerCloser.tick();
            assertEquals("closed too early at tick " + i, 0, container.closed);
        }
        ContainerCloser.tick();
        assertEquals("the scheduled close must run", 1, container.closed);
    }

    @Test
    public void anEmptyScheduleDoesNothing() {
        assertEquals(0, ContainerCloser.pending());
        ContainerCloser.schedule(Collections.<IInventory>emptyList());
        ContainerCloser.tick();
        assertEquals(0, ContainerCloser.pending());
    }

    private static final class FakeContainer extends TileEntity implements IInventory {
        private int closed;

        @Override
        public void openInventory() {
        }

        @Override
        public void closeInventory() {
            this.closed++;
        }

        @Override
        public int getSizeInventory() {
            return 0;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return null;
        }

        @Override
        public ItemStack decrStackSize(int index, int count) {
            return null;
        }

        @Override
        public ItemStack getStackInSlotOnClosing(int index) {
            return null;
        }

        @Override
        public void setInventorySlotContents(int index, ItemStack stack) {
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
            return 64;
        }

        @Override
        public boolean isUseableByPlayer(EntityPlayer player) {
            return true;
        }

        @Override
        public boolean isItemValidForSlot(int index, ItemStack stack) {
            return true;
        }

        @Override
        public void markDirty() {
        }
    }
}
