package ir.layercloud.cortexdrone.harness;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;

/**
 * Exact registry of chunks currently watched by players, driven by Forge's
 * ChunkWatchEvent.Watch / UnWatch (the same events vanilla uses when sending
 * and removing chunks for a player).
 *
 * <p>The drone chunk loader must never force, load, re-order, unforce or release
 * a chunk in this set. This is stronger than a view-distance calculation and
 * catches border/deferred chunks that are still retained by the player chunk
 * manager.</p>
 */
public final class PlayerChunkWatchRegistry {
    private static final Map<World, Map<Long, Integer>> WATCHED =
        Collections.synchronizedMap(new WeakHashMap<World, Map<Long, Integer>>());

    private PlayerChunkWatchRegistry() {
    }

    public static void watch(World world, ChunkCoordIntPair chunk) {
        if (world == null || chunk == null) return;
        Map<Long, Integer> map = watchedMap(world);
        long key = key(chunk.field_77276_a, chunk.field_77275_b);
        synchronized (map) {
            Integer prev = map.get(Long.valueOf(key));
            map.put(Long.valueOf(key), Integer.valueOf(prev == null ? 1 : prev.intValue() + 1));
        }
    }

    public static void unwatch(World world, ChunkCoordIntPair chunk) {
        if (world == null || chunk == null) return;
        Map<Long, Integer> map = WATCHED.get(world);
        if (map == null) return;
        long key = key(chunk.field_77276_a, chunk.field_77275_b);
        synchronized (map) {
            Integer prev = map.get(Long.valueOf(key));
            if (prev == null) return;
            if (prev.intValue() <= 1) map.remove(Long.valueOf(key));
            else map.put(Long.valueOf(key), Integer.valueOf(prev.intValue() - 1));
        }
    }

    public static boolean isWatched(World world, int cx, int cz) {
        if (world == null) return false;
        Map<Long, Integer> map = WATCHED.get(world);
        if (map == null) return false;
        synchronized (map) {
            Integer count = map.get(Long.valueOf(key(cx, cz)));
            return count != null && count.intValue() > 0;
        }
    }

    public static void clear(World world) {
        if (world != null) WATCHED.remove(world);
    }

    private static Map<Long, Integer> watchedMap(World world) {
        Map<Long, Integer> map = WATCHED.get(world);
        if (map == null) {
            map = new ConcurrentHashMap<Long, Integer>();
            Map<Long, Integer> prev = WATCHED.put(world, map);
            if (prev != null) map = prev;
        }
        return map;
    }

    private static long key(int cx, int cz) {
        return (((long) cx) << 32) | (((long) cz) & 0xFFFFFFFFL);
    }
}
