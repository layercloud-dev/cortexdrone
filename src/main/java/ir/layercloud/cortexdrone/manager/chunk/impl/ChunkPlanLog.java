package ir.layercloud.cortexdrone.manager.chunk.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.manager.chunk.PlayerChunkOwnership;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraftforge.common.ForgeChunkManager;

import java.util.List;

/**
 * Throttled diagnostics for a drone's chunk ownership.
 *
 * <p>Extracted from {@code ChunkLoadingManager}. Ownership changes every few
 * ticks while a drone flies, so the line is written only when something actually
 * changed (or every few hundred ticks), and it says how much of the plan this
 * mod owns versus how much vanilla owns.</p>
 */
final class ChunkPlanLog {
    private final DroneLogger logger;

    ChunkPlanLog(DroneLogger logger) {
        this.logger = logger;
    }

    /** Writes one ownership line when the cursor says it is due. */
    void log(DroneController controller, DroneChunkCursor cursor,
             ForgeChunkManager.Ticket ticket, List<ChunkCoordIntPair> plan,
             int loadedNow, long now, boolean loadAllowed) {
        int managed = 0;
        int playerOwned = 0;
        for (ChunkCoordIntPair pair : plan) {
            if (PlayerChunkOwnership.isWithinAnyPlayerView(ticket.world,
                    pair.chunkXPos, pair.chunkZPos)) {
                playerOwned++;
            } else {
                managed++;
            }
        }
        if (!cursor.shouldLog(managed, playerOwned, loadedNow, now)) return;
        this.logger.log("CHUNK", controller, "Navigation chunk ownership updated",
                "managed=" + managed + " playerOwned=" + playerOwned
                        + " plan=" + plan.size() + " held=" + ticket.getChunkList().size()
                        + " loadedNow=" + loadedNow
                        + " policy=" + (loadAllowed ? "open" : "closed"));
    }
}
