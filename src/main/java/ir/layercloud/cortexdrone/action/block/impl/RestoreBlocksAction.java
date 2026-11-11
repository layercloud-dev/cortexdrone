package ir.layercloud.cortexdrone.action.block.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.block.BlockResult;
import ir.layercloud.cortexdrone.context.impl.BlockContext;
import ir.layercloud.cortexdrone.service.world.DamageJournal;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts back blocks the drone had to break to reach a target.
 *
 * <p>Navigation avoids damage when it can, but a tunnel is sometimes the only
 * path. Every auto-dig is journalled, so this action can undo the work once the
 * job is done. It is deliberately conservative:</p>
 *
 * <ul>
 *   <li>a slot that is no longer air is left alone (the world changed);</li>
 *   <li>tile entities are re-created from the captured NBT;</li>
 *   <li>restoration goes newest-first, so tunnels close from the far end back.</li>
 * </ul>
 */
@RequiredArgsConstructor
@SuppressWarnings("unused")
public final class RestoreBlocksAction implements Action<BlockContext> {
    private final DamageJournal journal;

    @Override
    public void execute(BlockContext context) {
        DroneController controller = context.controller();
        Drone drone = controller != null ? controller.drone : null;
        World world = drone != null ? drone.worldObj : null;
        if (drone == null || world == null) {
            context.result(BlockResult.failure("No active drone or world."));
            return;
        }
        List<DamageJournal.Record> pending = this.journal.pending(controller);
        if (pending.isEmpty()) {
            context.result(BlockResult.builder()
                    .ok(true)
                    .error(null)
                    .build());
            return;
        }

        List<DamageJournal.Record> restored = new ArrayList<>();
        int skipped = 0;
        // Newest-first: the block closest to the drone's current position is
        // usually the last one dug, and closing backwards avoids re-exposing
        // the tunnel while the drone is still inside it.
        for (int i = pending.size() - 1; i >= 0; i--) {
            DamageJournal.Record record = pending.get(i);
            if (!restore(world, record)) {
                skipped++;
                continue;
            }
            restored.add(record);
        }
        this.journal.remove(controller, restored);
        context.result(BlockResult.builder()
                .ok(true)
                .block("restored=" + restored.size())
                .error(skipped > 0 ? skipped + " block(s) were left in place (occupied or gone)" : null)
                .build());
    }

    /**
     * @return true when the recorded block is back in the world
     */
    private static boolean restore(World world, DamageJournal.Record record) {
        if (record == null || record.block() == null) return false;
        int x = record.x();
        int y = record.y();
        int z = record.z();
        if (!world.blockExists(x, y, z)) return false;
        if (!world.isAirBlock(x, y, z)) return false;

        Block block = record.block();
        world.setBlock(x, y, z, block, record.meta(), 3);
        if (record.hasTile()) {
            try {
                TileEntity entity = TileEntity.createAndLoadEntity(record.tile());
                if (entity != null) {
                    world.setTileEntity(x, y, z, entity);
                    entity.markDirty();
                }
            } catch (Throwable ignored) {
                // The block is back; a missing tile is better than a hole.
            }
        }
        world.markBlockForUpdate(x, y, z);
        return true;
    }

    /**
     * Convenience for other actions that need to capture a tile snapshot.
     */
    public static NBTTagCompound snapshotTile(World world, int x, int y, int z) {
        try {
            TileEntity entity = world.getTileEntity(x, y, z);
            if (entity == null) return null;
            NBTTagCompound tag = new NBTTagCompound();
            entity.writeToNBT(tag);
            return tag;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
