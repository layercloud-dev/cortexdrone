package ir.layercloud.cortexdrone.service.world;

import ir.layercloud.cortexdrone.DroneController;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers blocks a drone had to break, so they can be put back afterwards.
 *
 * <p>Navigation prefers detours and only tunnels when no path exists. Even then
 * the break is not free: this journal captures the original block, metadata and
 * tile-entity NBT <b>before</b> the break, in traversal order. A later
 * {@code repair_trail} call can restore them in reverse.</p>
 *
 * <p>This is storage, not policy: the action layer decides when to record and
 * when to repair.</p>
 */
public final class DamageJournal {
    private static final int MAX_RECORDS = 512;

    private final Map<DroneController, List<Record>> journals = new IdentityHashMap<>();

    /** One broken block, with everything needed to put it back. */
    public static final class Record {
        private final int x;
        private final int y;
        private final int z;
        private final Block block;
        private final int meta;
        private final NBTTagCompound tile;
        private final String reason;
        private final long tick;

        Record(int x, int y, int z, Block block, int meta, NBTTagCompound tile,
               String reason, long tick) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.meta = meta;
            this.tile = tile;
            this.reason = reason;
            this.tick = tick;
        }

        public int x() {
            return this.x;
        }

        public int y() {
            return this.y;
        }

        public int z() {
            return this.z;
        }

        public Block block() {
            return this.block;
        }

        public int meta() {
            return this.meta;
        }

        public NBTTagCompound tile() {
            return this.tile;
        }

        public String reason() {
            return this.reason;
        }

        public long tick() {
            return this.tick;
        }

        public boolean hasTile() {
            return this.tile != null;
        }
    }

    /**
     * Captures a block about to be broken.
     *
     * <p>Call this immediately before the break; after the break the state is
     * gone and the block is unrecoverable.</p>
     *
     * @return the record, or {@code null} when the block is air/unbreakable
     */
    public Record record(DroneController controller, World world, int x, int y, int z, String reason) {
        if (controller == null || world == null) return null;
        if (!world.blockExists(x, y, z)) return null;
        Block block = world.getBlock(x, y, z);
        if (block == null || world.isAirBlock(x, y, z)) return null;
        int meta = world.getBlockMetadata(x, y, z);
        NBTTagCompound tile = null;
        try {
            TileEntity entity = world.getTileEntity(x, y, z);
            if (entity != null) {
                tile = new NBTTagCompound();
                entity.writeToNBT(tile);
            }
        } catch (Throwable ignored) {
        }
        Record record = new Record(x, y, z, block, meta, tile, reason,
                world.getTotalWorldTime());
        List<Record> journal = journal(controller);
        journal.add(record);
        while (journal.size() > MAX_RECORDS) journal.remove(0);
        return record;
    }

    /** Most recent records first, bounded by {@code limit}. */
    public List<Record> snapshot(DroneController controller, int limit) {
        List<Record> journal = this.journals.get(controller);
        if (journal == null || journal.isEmpty()) return Collections.emptyList();
        List<Record> newestFirst = new ArrayList<>(journal);
        Collections.reverse(newestFirst);
        int max = Math.min(Math.max(1, limit), newestFirst.size());
        return new ArrayList<>(newestFirst.subList(0, max));
    }

    public int size(DroneController controller) {
        List<Record> journal = this.journals.get(controller);
        return journal != null ? journal.size() : 0;
    }

    /** Oldest-first order, which is the order a repair should consume. */
    public List<Record> pending(DroneController controller) {
        List<Record> journal = this.journals.get(controller);
        return journal == null ? Collections.<Record>emptyList() : new ArrayList<>(journal);
    }

    /** Clears records that were restored successfully. */
    public void remove(DroneController controller, List<Record> restored) {
        List<Record> journal = this.journals.get(controller);
        if (journal == null || restored == null || restored.isEmpty()) return;
        journal.removeAll(restored);
        if (journal.isEmpty()) this.journals.remove(controller);
    }

    public void clear(DroneController controller) {
        this.journals.remove(controller);
    }

    private List<Record> journal(DroneController controller) {
        List<Record> journal = this.journals.get(controller);
        if (journal == null) {
            journal = new ArrayList<>();
            this.journals.put(controller, journal);
        }
        return journal;
    }

    /** Convenience for the drone entity, for callers that only have it. */
    public static boolean isAir(World world, int x, int y, int z) {
        return world == null || world.isAirBlock(x, y, z);
    }

    /** True when the drone still exists and is in the same world. */
    public static boolean sameWorld(Drone drone, World world) {
        return drone != null && drone.worldObj != null && drone.worldObj == world;
    }
}
