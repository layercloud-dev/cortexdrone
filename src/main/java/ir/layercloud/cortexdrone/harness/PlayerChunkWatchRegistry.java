package ir.layercloud.cortexdrone.harness;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;

/**
 * Exact registry of chunks currently watched by players, driven by Forge's
 * ChunkWatchEvent.Watch / UnWatch.
 *
 * <p>Watches are tracked per player, not as a single counter. Forge 1.7.10 can
 * fire UnWatch without a matching Watch (for a chunk that was never acknowledged
 * by the client), so a counter can decrement another player's watch and wrongly
 * declare a still-watched chunk unprotected.</p>
 *
 * <p>This registry is an additional safety net; {@link PlayerChunkOwnership}
 * also protects every chunk inside a player's server view distance.</p>
 */
public final class PlayerChunkWatchRegistry {
    private static final Map<World, Map<Long, Set<UUID>>> WATCHED =
        Collections.synchronizedMap(new WeakHashMap<World, Map<Long, Set<UUID>>>());

    private PlayerChunkWatchRegistry() {
    }

    public static void watch(World world, UUID playerId, ChunkCoordIntPair chunk) {
        if (world == null || playerId == null || chunk == null) return;
        Map<Long, Set<UUID>> map = watchedMap(world);
        long key = key(chunk.field_77276_a, chunk.field_77275_b);
        synchronized (map) {
            Set<UUID> players = map.get(Long.valueOf(key));
            if (players == null) {
                players = new HashSet<UUID>();
                map.put(Long.valueOf(key), players);
            }
            players.add(playerId);
        }
    }

    public static void unwatch(World world, UUID playerId, ChunkCoordIntPair chunk) {
        if (world == null || playerId == null || chunk == null) return;
        Map<Long, Set<UUID>> map = WATCHED.get(world);
        if (map == null) return;
        long key = key(chunk.field_77276_a, chunk.field_77275_b);
        synchronized (map) {
            Set<UUID> players = map.get(Long.valueOf(key));
            if (players == null) return;
            players.remove(playerId);
            if (players.isEmpty()) map.remove(Long.valueOf(key));
        }
    }

    public static boolean isWatched(World world, int cx, int cz) {
        if (world == null) return false;
        Map<Long, Set<UUID>> map = WATCHED.get(world);
        if (map == null) return false;
        synchronized (map) {
            Set<UUID> players = map.get(Long.valueOf(key(cx, cz)));
            return players != null && !players.isEmpty();
        }
    }

    /** Drops every watch belonging to a player (logout, dimension change, etc.). */
    public static void clearPlayer(UUID playerId) {
        if (playerId == null) return;
        synchronized (WATCHED) {
            for (Map<Long, Set<UUID>> map : WATCHED.values()) {
                synchronized (map) {
                    Iterator<Map.Entry<Long, Set<UUID>>> it = map.entrySet().iterator();
                    while (it.hasNext()) {
                        Set<UUID> players = it.next().getValue();
                        players.remove(playerId);
                        if (players.isEmpty()) it.remove();
                    }
                }
            }
        }
    }

    public static void clear(World world) {
        if (world == null) return;
        WATCHED.remove(world);
    }

    private static Map<Long, Set<UUID>> watchedMap(World world) {
        synchronized (WATCHED) {
            Map<Long, Set<UUID>> map = WATCHED.get(world);
            if (map == null) {
                map = new java.util.HashMap<Long, Set<UUID>>();
                WATCHED.put(world, map);
            }
            return map;
        }
    }

    private static long key(int cx, int cz) {
        return (((long) cx) << 32) | (((long) cz) & 0xFFFFFFFFL);
    }
}
