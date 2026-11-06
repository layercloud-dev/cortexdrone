package ir.layercloud.cortexdrone.service.world;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.config.AIConfig;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.*;

/**
 * Spatial world discovery service coordinating room scans (RoomScanner),
 * connected vein clustering (VeinScanner), player raycasts (PlayerTargetScanner),
 * long-range biome/resource spiral searches, and base location discovery.
 *
 * <p>Fallback scan policy: per-axis non-air block scans and full-cube AIR scans
 * are expensive; avoid them when a cached nearby-resource summary can serve the
 * caller. On-demand {@code world.scanBlocks} time-slices at 2-4ms per slice.</p>
 */
@RequiredArgsConstructor
public class WorldScanner {
    // Cheap to recompute; don't re-run a full cube scan for a stale telemetry hint.
    // A drone moving >4 blocks (centre bucket) misses the cache; a block change
    // inside the scanned cube may also miss until expiry, which is acceptable
    // for a hint-only field.
    private static final long NEARBY_RESOURCES_TTL_MS = 5000L;
    private static final int NEARBY_RESOURCES_CACHE_CELLS = 4;
    private static final int NEARBY_RESOURCES_MAX_RADIUS = 24;

    private final DroneLogger logger;
    private final AIConfig config;

    private final java.util.Map<Long, java.util.Map<String, int[]>> nearbyResourceCache =
            new java.util.HashMap<>();
    private final java.util.Map<Long, Long> nearbyResourceExpiry =
            new java.util.HashMap<>();

    public JsonObject scanRoom3D(World world, int startX, int startY, int startZ, int maxRadius) {
        return RoomScanner.scanRoom3D(world, startX, startY, startZ, maxRadius);
    }

    public JsonObject scanConnectedVeins(World world, int cx, int cy, int cz, int radius, String rawFilter) {
        return VeinScanner.scanConnectedVeins(world, cx, cy, cz, radius, rawFilter);
    }

    public static JsonObject raycastPlayerLook(World world, EntityPlayerMP player, double maxDist) {
        return PlayerTargetScanner.raycastPlayerLook(world, player, maxDist);
    }

    public static JsonObject findPlayerSupportBlock(World world, EntityPlayerMP player) {
        return PlayerTargetScanner.findPlayerSupportBlock(world, player);
    }

    public List<int[]> findDistantResourceOrBiome(World world, DroneController ctrl, double startX, double startZ, String target, int maxRadius) {
        List<int[]> found = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        String f = target.toLowerCase();
        int ix = MathHelper.floor_double(startX);
        int iz = MathHelper.floor_double(startZ);
        boolean checkUnderground = ItemClassifier.isLikelyUndergroundQuery(f);
        boolean checkBiome = RegistryIntrospector.hasBiomeAffinity(f);

        long deadline = System.currentTimeMillis() + 12L;
        int chunksLoadedCount = 0;

        for (int pass = 0; pass < 2; pass++) {
            boolean loadedOnly = (pass == 0);
            for (int r = 16; r <= maxRadius; r += 24) {
                if (System.currentTimeMillis() >= deadline) break;
                int steps = Math.max(8, (int) Math.ceil((2.0 * Math.PI * r) / 28.0));
                for (int s = 0; s < steps; s++) {
                    if (System.currentTimeMillis() >= deadline) break;
                    double ang = (2.0 * Math.PI * s) / steps;
                    int sx = ix + (int) Math.round(Math.cos(ang) * r);
                    int sz = iz + (int) Math.round(Math.sin(ang) * r);

                    boolean isLoaded = !(world instanceof WorldServer)
                            || ((WorldServer) world).theChunkProviderServer.chunkExists(sx >> 4, sz >> 4);
                    if (loadedOnly && !isLoaded) continue;
                    if (!loadedOnly && isLoaded) continue;

                    if (checkBiome) {
                        try {
                            BiomeGenBase biome = world.getBiomeGenForCoords(sx, sz);
                            if (!RegistryIntrospector.matchesBiome(biome, f)) {
                                continue;
                            }
                        } catch (Throwable ignored) {
                        }
                    }

                    if (!isLoaded) {
                        if (chunksLoadedCount >= 16) continue;
                        if (!ChunkAccess.isBlockLoaded(world, sx, sz)) continue;
                        chunksLoadedCount++;
                    }
                    int topY = world.getHeightValue(sx, sz);
                    for (int dx = -5; dx <= 5; dx++) {
                        for (int dz = -5; dz <= 5; dz++) {
                            int bx = sx + dx;
                            int bz = sz + dz;
                            int minY = checkUnderground && (dx % 2 == 0 && dz % 2 == 0) ? 2 : Math.max(40, topY - 10);
                            int maxY = Math.min(250, topY + 18);
                            for (int by = maxY; by >= minY; by--) {
                                if (!ItemClassifier.isMineableBlock(world, bx, by, bz)) continue;
                                Block b = world.getBlock(bx, by, bz);
                                int meta = world.getBlockMetadata(bx, by, bz);
                                if (ItemClassifier.matchesMineFilter(b, meta, f)) {
                                    long key = FlightPathfinder.packBlockPos(bx, by, bz);
                                    if (seen.add(key)) {
                                        found.add(new int[]{bx, by, bz});
                                        if (found.size() >= 96) {
                                            this.logger.log("PATH", ctrl,
                                                    String.format("Located distant '%s' at (%d,%d,%d) [dist=%dm]", target, bx, by, bz, r),
                                                    "count=" + found.size());
                                            return found;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (found.size() >= 48) {
                        this.logger.log("PATH", ctrl,
                                String.format("Located distant '%s' at (%d,%d,%d) [dist=%dm]", target, found.get(0)[0], found.get(0)[1], found.get(0)[2], r),
                                "count=" + found.size());
                        return found;
                    }
                }
            }
        }
        if (!found.isEmpty()) {
            int[] first = found.get(0);
            int distM = (int) Math.round(Math.hypot(first[0] - ix, first[2] - iz));
            this.logger.log("PATH", ctrl,
                    String.format("Located distant '%s' at (%d,%d,%d) [dist=%dm]", target, first[0], first[1], first[2], distM),
                    "count=" + found.size());
        }
        return found;
    }

    public Map<String, int[]> scanNearbyResources(World world, double cx, double cy, double cz, int radius) {
        int ix = MathHelper.floor_double(cx);
        int iy = MathHelper.floor_double(cy);
        int iz = MathHelper.floor_double(cz);

        int dimId = 0;
        if (world != null && world.provider != null) {
            try {
                dimId = world.provider.dimensionId;
            } catch (RuntimeException ignored) {
            }
        }
        boolean cacheable = radius >= 8 && radius <= NEARBY_RESOURCES_MAX_RADIUS;
        long cacheKey = 0L;
        if (cacheable) {
            int cell = NEARBY_RESOURCES_CACHE_CELLS;
            int bx = Math.floorDiv(ix, cell);
            int by = Math.floorDiv(iy, cell);
            int bz = Math.floorDiv(iz, cell);
            cacheKey = cacheKey(bx, by, bz, dimId, radius);
            long now = System.currentTimeMillis();
            Long expiry = this.nearbyResourceExpiry.get(cacheKey);
            if (expiry != null) {
                if (expiry > now) {
                    Map<String, int[]> hit = this.nearbyResourceCache.get(cacheKey);
                    if (hit != null) {
                        return copySummary(hit);
                    }
                } else {
                    this.nearbyResourceExpiry.remove(cacheKey);
                    this.nearbyResourceCache.remove(cacheKey);
                }
            }
        }

        Map<String, int[]> summary = scanNearbyResourcesUncached(world, ix, iy, iz, radius);

        if (cacheable) {
            Map<String, int[]> stored = copySummary(summary);
            this.nearbyResourceCache.put(cacheKey, stored);
            this.nearbyResourceExpiry.put(cacheKey, System.currentTimeMillis() + NEARBY_RESOURCES_TTL_MS);
            if (this.nearbyResourceCache.size() > 64) {
                long now = System.currentTimeMillis();
                java.util.Iterator<Long> it = this.nearbyResourceExpiry.keySet().iterator();
                while (it.hasNext()) {
                    Long k = it.next();
                    Long exp = this.nearbyResourceExpiry.get(k);
                    if (exp == null || exp <= now) {
                        it.remove();
                        this.nearbyResourceCache.remove(k);
                    }
                }
            }
        }
        return summary;
    }

    private static long cacheKey(int bx, int by, int bz, int dimId, int radius) {
        long k = dimId;
        k = k * 1315423911L ^ (bx & 0xFFFFFFFFL);
        k = k * 1315423911L ^ (by & 0xFFFFFFFFL);
        k = k * 1315423911L ^ (bz & 0xFFFFFFFFL);
        return k * 1315423911L ^ (radius & 0xFFFFFFFFL);
    }

    private static Map<String, int[]> copySummary(Map<String, int[]> source) {
        Map<String, int[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : source.entrySet()) {
            copy.put(e.getKey(), e.getValue().clone());
        }
        return copy;
    }

    private static Map<String, int[]> scanNearbyResourcesUncached(World world, int ix, int iy, int iz, int radius) {
        Map<String, int[]> summary = new LinkedHashMap<>();
        if (world == null) return summary;

        for (int x = ix - radius; x <= ix + radius; x++) {
            for (int z = iz - radius; z <= iz + radius; z++) {
                if (world instanceof WorldServer && !((WorldServer) world).theChunkProviderServer.chunkExists(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = Math.max(2, iy - radius); y <= Math.min(254, iy + radius); y++) {
                    try {
                        if (world.isAirBlock(x, y, z)) continue;
                        Block b = world.getBlock(x, y, z);
                        if (b == null) continue;
                        int meta = world.getBlockMetadata(x, y, z);
                        if (ItemClassifier.isOreBlock(b, meta) || ItemClassifier.isLogBlock(b) || b == Blocks.chest) {
                            String name = ItemClassifier.getBlockDisplayName(b, meta);
                            int distSq = (x - ix) * (x - ix) + (y - iy) * (y - iy) + (z - iz) * (z - iz);
                            int[] cur = summary.get(name);
                            if (cur == null) {
                                summary.put(name, new int[]{1, x, y, z, distSq});
                            } else {
                                cur[0]++;
                                if (distSq < cur[4]) {
                                    cur[1] = x;
                                    cur[2] = y;
                                    cur[3] = z;
                                    cur[4] = distSq;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return summary;
    }
}
