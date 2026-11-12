package ir.layercloud.cortexdrone.service.inventory;

import net.minecraft.inventory.IInventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Deferred container closes.
 *
 * <p>{@code openInventory()} and {@code closeInventory()} both call
 * {@code world.addBlockEvent}. Doing both inside one transfer puts two events
 * in the same end-of-tick batch, so the client ends up seeing only the final
 * {@code numPlayersUsing = 0} and a chest lid never moves. A close is therefore
 * queued here and applied {@link #MIN_OPEN_TICKS} ticks later, which is long
 * enough for the lid to travel and for the open/close sounds to play.</p>
 *
 * <p>The queue is static because containers are opened from actions that have
 * no manager reference, and it is tiny (one entry per transfer). Server thread
 * only.</p>
 */
public final class ContainerCloser {
    /**
     * Ticks a container stays open. A chest lid needs ~10 ticks to reach fully
     * open (0.1 per tick), so this is the shortest hold that still reads as a
     * chest opening.
     */
    public static final int MIN_OPEN_TICKS = 10;

    private static final List<Job> PENDING = Collections.synchronizedList(new ArrayList<>());

    private ContainerCloser() {
    }

    /**
     * Queues {@code opened} to be closed after the animation delay.
     */
    public static void schedule(List<IInventory> opened) {
        if (opened == null || opened.isEmpty()) return;
        PENDING.add(new Job(opened));
    }

    /**
     * Advances the queue. Call once per server tick.
     */
    public static void tick() {
        if (PENDING.isEmpty()) return;
        List<IInventory> due = new ArrayList<>();
        synchronized (PENDING) {
            Iterator<Job> it = PENDING.iterator();
            while (it.hasNext()) {
                Job job = it.next();
                if (--job.ticksLeft > 0) continue;
                due.addAll(job.opened);
                it.remove();
            }
        }
        for (IInventory inventory : due) {
            try {
                inventory.closeInventory();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Drops pending closes without applying them (world unload).
     */
    public static void clear() {
        PENDING.clear();
    }

    /**
     * Pending jobs, for diagnostics.
     */
    public static int pending() {
        return PENDING.size();
    }

    private static final class Job {
        private final List<IInventory> opened;
        private int ticksLeft;

        private Job(List<IInventory> opened) {
            this.opened = opened;
            this.ticksLeft = MIN_OPEN_TICKS;
        }
    }
}
