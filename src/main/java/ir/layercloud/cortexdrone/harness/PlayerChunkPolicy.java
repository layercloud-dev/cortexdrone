package ir.layercloud.cortexdrone.harness;

/**
 * Pure player-chunk ownership policy used by the chunk loader. A chunk inside
 * any player's vanilla view distance is owned by vanilla and must never be
 * forced, loaded, re-ordered, unforced, released, or unloaded by the drone.
 */
public final class PlayerChunkPolicy {
    private PlayerChunkPolicy() {
    }

    public static boolean isWithinViewDistance(int playerChunkX, int playerChunkZ,
                                               int chunkX, int chunkZ,
                                               int viewDistanceChunks) {
        int view = Math.max(0, viewDistanceChunks);
        return Math.abs(playerChunkX - chunkX) <= view
            && Math.abs(playerChunkZ - chunkZ) <= view;
    }
}
