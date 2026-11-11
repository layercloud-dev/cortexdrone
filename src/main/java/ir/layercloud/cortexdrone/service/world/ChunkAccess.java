package ir.layercloud.cortexdrone.service.world;

import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * Chunk availability checks and the policy-gated loading bridge.
 *
 * <p>Chunk lifetime belongs to vanilla and Forge: the navigation chunk manager
 * registers a {@link Loader} here, asks Forge for tickets and lets
 * {@code ChunkProviderServer} unload chunks through its normal unload queue.
 * Services never unload or generate a chunk on their own.</p>
 *
 * <p>When no loader is installed (world not started, or the player policy is
 * closed) {@link #ensureLoaded(World, int, int)} simply returns {@code false}
 * and callers must treat the chunk as unavailable/solid.</p>
 */
public final class ChunkAccess {
    private static volatile Loader loader;

    private ChunkAccess() {
    }

    /**
     * Installed by the navigation chunk manager for one server lifetime.
     */
    public interface Loader {
        boolean ensureLoaded(World world, int chunkX, int chunkZ);
    }

    public static void setLoader(Loader chunkLoader) {
        loader = chunkLoader;
    }

    public static boolean isLoaded(World world, int chunkX, int chunkZ) {
        if (!(world instanceof WorldServer)) return false;
        try {
            return ((WorldServer) world).theChunkProviderServer.chunkExists(chunkX, chunkZ);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isBlockLoaded(World world, int x, int z) {
        return isLoaded(world, x >> 4, z >> 4);
    }

    public static boolean isBlockLoaded(World world, double x, double z) {
        return isLoaded(world, MathHelper.floor_double(x) >> 4, MathHelper.floor_double(z) >> 4);
    }

    /**
     * Returns true when the chunk is loaded, or when the installed manager is
     * allowed to load it right now. This is the only loading entry point used by
     * pathfinding and raycasts, so the player-online policy is never bypassed.
     */
    public static boolean ensureLoaded(World world, int chunkX, int chunkZ) {
        if (isLoaded(world, chunkX, chunkZ)) return true;
        Loader current = loader;
        return current != null && current.ensureLoaded(world, chunkX, chunkZ);
    }

    public static boolean ensureBlockLoaded(World world, int x, int z) {
        return ensureLoaded(world, x >> 4, z >> 4);
    }
}
