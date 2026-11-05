package ir.layercloud.cortexdrone.navigation;

import ir.layercloud.cortexdrone.world.ItemClassifier;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * Baritone-style ChunkCacheContext (BSI) for fast chunk-local block and passability lookups during 3D A*.
 */
public class ChunkCacheContext {
    private final World world;
    private final int baseChunkX;
    private final int baseChunkZ;
    private final int gridSize;
    private final Chunk[][] chunks;
    private final boolean[][] resolved;
    // Memoized passability lookups: A*/Theta* revisits the same voxel hundreds of times.
    private final java.util.HashMap<Long, Boolean> passableCache = new java.util.HashMap<Long, Boolean>(512);
    private final java.util.HashMap<Long, Block> blockCache = new java.util.HashMap<Long, Block>(512);

    public ChunkCacheContext(World world, int centerX, int centerZ, int chunkRadius) {
        this.world = world;
        this.gridSize = chunkRadius * 2 + 1;
        this.baseChunkX = (centerX >> 4) - chunkRadius;
        this.baseChunkZ = (centerZ >> 4) - chunkRadius;
        this.chunks = new Chunk[this.gridSize][this.gridSize];
        this.resolved = new boolean[this.gridSize][this.gridSize];
    }

    /**
     * Loads a chunk only when pathfinding actually reads it. The old eager
     * 7x7 preload generated/loaded up to 49 chunks per repath, including
     * chunks the search never touched, and then left them to vanilla's
     * unload queue.
     */
    private Chunk chunkAt(int gridX, int gridZ) {
        if (!this.resolved[gridX][gridZ]) {
            int cx = this.baseChunkX + gridX;
            int cz = this.baseChunkZ + gridZ;
            if (DroneChunkLoader.requestChunk(this.world, cx, cz)) {
                try {
                    this.chunks[gridX][gridZ] = this.world.func_72964_e(cx, cz);
                } catch (Throwable ignored) {
                }
            }
            this.resolved[gridX][gridZ] = true;
        }
        return this.chunks[gridX][gridZ];
    }

    static long pack(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
    }

    public Block getBlock(int x, int y, int z) {
        if (y <= 0 || y >= 256) return Blocks.field_150357_h;
        int cx = (x >> 4) - this.baseChunkX;
        int cz = (z >> 4) - this.baseChunkZ;
        if (cx >= 0 && cx < this.gridSize && cz >= 0 && cz < this.gridSize) {
            Chunk c = chunkAt(cx, cz);
            if (c != null) {
                long key = pack(x, y, z);
                Block cached = this.blockCache.get(key);
                if (cached != null) return cached;
                Block b = c.func_150810_a(x & 15, y, z & 15);
                if (b != null) this.blockCache.put(key, b);
                return b;
            }
            // Unknown/unloaded chunk: treat it as solid so A* never plans a
            // route through a chunk the AI is not allowed to load.
            return Blocks.field_150357_h;
        }
        if (!DroneChunkLoader.requestChunk(this.world, x >> 4, z >> 4)) {
            return Blocks.field_150357_h;
        }
        return this.world.func_147439_a(x, y, z);
    }

    public boolean isPassable(int x, int y, int z) {
        if (y <= 1 || y >= 255) return false;
        long key = pack(x, y, z);
        Boolean cached = this.passableCache.get(key);
        if (cached != null) return cached.booleanValue();
        Block b = getBlock(x, y, z);
        boolean pass;
        if (b == null || b == Blocks.field_150350_a) pass = true;
        else if (ItemClassifier.isLiquidBlock(b)) pass = true;
        else if (b.func_149655_b(this.world, x, y, z)) pass = true;
        else pass = b.func_149668_a(this.world, x, y, z) == null;
        if (this.passableCache.size() < 32768) this.passableCache.put(key, Boolean.valueOf(pass));
        return pass;
    }
}
