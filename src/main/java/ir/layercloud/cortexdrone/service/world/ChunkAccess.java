package ir.layercloud.cortexdrone.service.world;

import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * Read-only chunk availability checks.
 *
 * <p>CortexDrone no longer loads, forces or unloads chunks. Chunk ownership is
 * left to vanilla and server-side chunk-loader features; the AI only asks
 * whether a chunk is already loaded before reading it.</p>
 */
public final class ChunkAccess {
    private ChunkAccess() {
    }

    public static boolean isLoaded(World world, int chunkX, int chunkZ) {
        if (!(world instanceof WorldServer)) return false;
        try {
            return ((WorldServer) world).theChunkProviderServer.chunkExists(chunkX, chunkZ);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isBlockLoaded(World world, int x, int z) {
        return isLoaded(world, x >> 4, z >> 4);
    }

    public static boolean isBlockLoaded(World world, double x, double z) {
        return isLoaded(world, MathHelper.floor_double(x) >> 4, MathHelper.floor_double(z) >> 4);
    }
}
