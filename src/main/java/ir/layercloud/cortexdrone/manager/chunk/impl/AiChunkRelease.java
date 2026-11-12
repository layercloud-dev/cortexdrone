package ir.layercloud.cortexdrone.manager.chunk.impl;

import ir.layercloud.cortexdrone.manager.chunk.PlayerChunkOwnership;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/**
 * Bookkeeping for chunks this mod generated.
 *
 * <p>Extracted from {@code ChunkLoadingManager}. A chunk we generated may be
 * forgotten only when no player can see it and no drone is near it; otherwise it
 * stays tracked so a later sweep can retry. Both the "unload everything" and
 * "unload what is out of view" paths use these rules.</p>
 */
final class AiChunkRelease {
    /**
     * Everything the release logic needs from the manager.
     */
    interface Host {
        /**
         * AI-generated chunks per world.
         */
        Map<World, Set<ChunkCoordIntPair>> aiLoaded();

        /**
         * True when any drone is near the chunk.
         */
        boolean isAnyDroneNear(World world, ChunkCoordIntPair pair);

        /**
         * Asks vanilla to unload the chunk.
         */
        void dropChunk(WorldServer world, ChunkCoordIntPair pair);
    }

    private final Host host;

    AiChunkRelease(Host host) {
        this.host = host;
    }

    /**
     * Drops every AI chunk that is genuinely unowned.
     *
     * @return true when every tracked chunk was released (nothing was protected)
     */
    boolean releaseAll() {
        boolean fullyReleased = true;
        for (Map.Entry<World, Set<ChunkCoordIntPair>> entry : this.host.aiLoaded().entrySet()) {
            World world = entry.getKey();
            if (!(world instanceof WorldServer)) continue;
            WorldServer server = (WorldServer) world;
            for (ChunkCoordIntPair pair : new ArrayList<>(entry.getValue())) {
                if (isOwned(world, pair)) {
                    fullyReleased = false;
                    continue;
                }
                this.host.dropChunk(server, pair);
                entry.getValue().remove(pair);
            }
        }
        if (fullyReleased) this.host.aiLoaded().clear();
        return fullyReleased;
    }

    /**
     * Drops only the AI chunks no player can see and no drone is near.
     */
    void releaseOutsideView() {
        for (Map.Entry<World, Set<ChunkCoordIntPair>> entry : this.host.aiLoaded().entrySet()) {
            World world = entry.getKey();
            if (!(world instanceof WorldServer)) continue;
            WorldServer server = (WorldServer) world;
            for (ChunkCoordIntPair pair : new ArrayList<>(entry.getValue())) {
                if (isOwned(world, pair)) continue;
                this.host.dropChunk(server, pair);
                entry.getValue().remove(pair);
            }
        }
    }

    /**
     * A chunk is owned while a player can see it or a drone is near it.
     */
    boolean isOwned(World world, ChunkCoordIntPair pair) {
        return PlayerChunkOwnership.isWithinAnyPlayerView(world, pair.chunkXPos, pair.chunkZPos)
                || this.host.isAnyDroneNear(world, pair);
    }
}
