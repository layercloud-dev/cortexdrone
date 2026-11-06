package ir.layercloud.cortexdrone.manager.visuals.impl;

import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.manager.visuals.LightConfig;
import ir.layercloud.cortexdrone.manager.visuals.LightResult;
import ir.layercloud.cortexdrone.world.ChunkAccess;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * Torch-free illumination aura.
 *
 * <p>While {@link DroneController#autoTorch} is enabled the drone raises the block
 * light around itself, exactly like a moving light source, without consuming a
 * torch or placing any block. The original light values of every touched voxel
 * are remembered so switching the aura off restores the world byte-for-byte.
 *
 * <p>1.7.10 only stores a scalar (0..15) block-light value, so the RGB colour is a
 * presentation colour (drone glow / aura particles) while the illumination itself
 * is white. The colour is still stored and reported so callers can drive it.
 */
@RequiredArgsConstructor
public class LightingAura {
    private static final int MIN_RADIUS = 2;
    private static final int MAX_RADIUS = 12;
    private static final int MIN_LEVEL = 4;
    private static final int MAX_LEVEL = 15;
    private static final int APPLY_INTERVAL_TICKS = 10;
    private static final double CLIENT_SYNC_RANGE = 96.0;

    private final DroneLogger logger;

    /** Handles {@code torch} / {@code light} RPC: toggle, or set colour/radius/level. */
    public LightResult configure(DroneController ctrl, LightConfig config) {
        if (ctrl == null || ctrl.drone == null) {
            return LightResult.failure("No active drone");
        }
        boolean wasActive = ctrl.autoTorch;
        if (config.getEnabled() != null) {
            ctrl.autoTorch = config.getEnabled().booleanValue();
        } else if (config.isToggle()) {
            ctrl.autoTorch = !ctrl.autoTorch;
        }
        if (config.getRadius() != null) {
            ctrl.lightRadius = clamp(config.getRadius().intValue(), MIN_RADIUS, MAX_RADIUS);
        }
        if (config.getLevel() != null) {
            ctrl.lightIntensity = clamp(config.getLevel().intValue(), MIN_LEVEL, MAX_LEVEL);
        }
        if (config.getColor() != null) {
            ctrl.lightColor = config.getColor().intValue();
        }

        if (ctrl.autoTorch) {
            ctrl.lightCooldown = 0;
            ctrl.lightAnchor = Long.MIN_VALUE;
            if (config.getColor() != null && (config.getColor().intValue() & 0xFFFFFF) != 0xFFFFFF) {
                ctrl.customLedColor = Integer.valueOf(config.getColor().intValue() & 0xFFFFFF);
                ctrl.ledEffect = "NONE";
                ctrl.drone.lightColor_$eq(config.getColor().intValue() & 0xFFFFFF);
            }
            applyAura(ctrl);
        } else if (wasActive) {
            clearAura(ctrl);
        }

        return LightResult.builder()
            .ok(true)
            .active(ctrl.autoTorch)
            .radius(ctrl.lightRadius)
            .level(ctrl.lightIntensity)
            .color(ctrl.lightColor)
            .cells(ctrl.lightCells.size())
            .torchFree(true)
            .build();
    }

    /** Called every server tick by the coordinator for each active controller. */
    public void tick(DroneController ctrl) {
        if (ctrl == null || ctrl.drone == null) return;
        World world = ctrl.drone.worldObj;
        if (world == null || world.isRemote) return;
        if (!ctrl.autoTorch) {
            if (!ctrl.lightCells.isEmpty()) clearAura(ctrl);
            return;
        }
        if (ctrl.lightCooldown > 0) {
            ctrl.lightCooldown--;
            return;
        }
        ctrl.lightCooldown = APPLY_INTERVAL_TICKS;
        long anchor = packAnchor(ctrl.drone);
        if (anchor == ctrl.lightAnchor && !ctrl.lightCells.isEmpty()) {
            return;
        }
        applyAura(ctrl);
    }

    /** Restores every voxel this aura touched. Safe to call repeatedly. */
    public void clearAura(DroneController ctrl) {
        if (ctrl == null) return;
        if (ctrl.drone == null || ctrl.drone.worldObj == null) {
            ctrl.lightCells.clear();
            ctrl.lightAnchor = Long.MIN_VALUE;
            return;
        }
        World world = ctrl.drone.worldObj;
        Map<Long, Integer> chunkMasks = new HashMap<Long, Integer>();
        for (Map.Entry<Long, Integer> e : ctrl.lightCells.entrySet()) {
            long key = e.getKey().longValue();
            int x = unpackX(key);
            int y = unpackY(key);
            int z = unpackZ(key);
            world.setLightValue(EnumSkyBlock.Block, x, y, z, e.getValue().intValue());
            addChunkMask(chunkMasks, x, y, z);
        }
        int restored = ctrl.lightCells.size();
        ctrl.lightCells.clear();
        ctrl.lightAnchor = Long.MIN_VALUE;
        syncChunks(world, chunkMasks);
        if (restored > 0) {
            this.logger.log("LIGHT", ctrl, "Light aura off, restored " + restored + " voxels", "");
        }
    }

    private void applyAura(DroneController ctrl) {
        Drone d = ctrl.drone;
        World world = d.worldObj;
        if (world == null) return;

        int cx = MathHelper.floor_double(d.posX);
        int cy = MathHelper.floor_double(d.posY);
        int cz = MathHelper.floor_double(d.posZ);
        int radius = clamp(ctrl.lightRadius, MIN_RADIUS, MAX_RADIUS);
        int level = clamp(ctrl.lightIntensity, MIN_LEVEL, MAX_LEVEL);

        Map<Long, Integer> previous = new HashMap<Long, Integer>(ctrl.lightCells);
        ctrl.lightCells.clear();
        Map<Long, Integer> chunkMasks = new HashMap<Long, Integer>();
        int r2 = radius * radius;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    int d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 > r2) continue;
                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;
                    if (y < 2 || y > 254) continue;
                    // Only illuminate open space; never rewrite light inside solids.
                    if (!world.isAirBlock(x, y, z)) continue;
                    long key = pack(x, y, z);
                    Integer original = previous.remove(key);
                    int before = original != null
                        ? original.intValue()
                        : world.getSavedLightValue(EnumSkyBlock.Block, x, y, z);
                    ctrl.lightCells.put(key, Integer.valueOf(before));
                    int want = Math.max(1, level - (int) Math.sqrt((double) d2));
                    if (before < want) {
                        world.setLightValue(EnumSkyBlock.Block, x, y, z, want);
                        addChunkMask(chunkMasks, x, y, z);
                    }
                }
            }
        }

        // Anything left in `previous` fell out of the aura: put the world back.
        for (Iterator<Map.Entry<Long, Integer>> it = previous.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Integer> e = it.next();
            long key = e.getKey().longValue();
            int x = unpackX(key);
            int y = unpackY(key);
            int z = unpackZ(key);
            world.setLightValue(EnumSkyBlock.Block, x, y, z, e.getValue().intValue());
            addChunkMask(chunkMasks, x, y, z);
        }

        ctrl.lightAnchor = packAnchor(d);
        if (!chunkMasks.isEmpty()) {
            syncChunks(world, chunkMasks);
        }
    }

    /**
     * Re-sends only the affected chunk sections (blocks + light) so remote clients
     * actually see the aura. Vanilla block-change packets do not carry light data,
     * so a light-only edit is invisible to a connected player otherwise.
     */
    private void syncChunks(World world, Map<Long, Integer> chunkMasks) {
        if (world.playerEntities == null || world.playerEntities.isEmpty()) return;

        for (Map.Entry<Long, Integer> ce : chunkMasks.entrySet()) {
            int cx = unpackChunkX(ce.getKey().longValue());
            int cz = unpackChunkZ(ce.getKey().longValue());
            int mask = ce.getValue().intValue() & 0xFFFF;
            if (mask == 0) continue;
            if (!ChunkAccess.isLoaded(world, cx, cz)) continue;
            Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
            if (chunk == null) continue;
            S21PacketChunkData packet = new S21PacketChunkData(chunk, true, mask);
            double px = cx * 16 + 8;
            double pz = cz * 16 + 8;
            for (Object o : world.playerEntities) {
                if (!(o instanceof EntityPlayerMP)) continue;
                EntityPlayerMP p = (EntityPlayerMP) o;
                if (p.worldObj != world) continue;
                if (p.getDistance(px, p.posY, pz) > CLIENT_SYNC_RANGE) continue;
                try {
                    p.playerNetServerHandler.sendPacket(packet);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void addChunkMask(Map<Long, Integer> chunkMasks, int x, int y, int z) {
        long key = chunkKey(x, z);
        int bit = 1 << (y >> 4);
        Integer old = chunkMasks.get(Long.valueOf(key));
        chunkMasks.put(Long.valueOf(key), Integer.valueOf((old == null ? 0 : old.intValue()) | bit));
    }


    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    private static int unpackX(long k) {
        int x = (int) (k >> 38);
        if (x >= 0x2000000) x -= 0x4000000;
        return x;
    }

    private static int unpackY(long k) {
        return (int) ((k >> 26) & 0xFFF);
    }

    private static int unpackZ(long k) {
        int z = (int) (k & 0x3FFFFFF);
        if (z >= 0x2000000) z -= 0x4000000;
        return z;
    }

    private static long chunkKey(int x, int z) {
        return ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xFFFFFFFFL);
    }

    private static int unpackChunkX(long k) {
        return (int) (k >> 32);
    }

    private static int unpackChunkZ(long k) {
        return (int) k;
    }

    private static long packAnchor(Drone d) {
        int x = MathHelper.floor_double(d.posX) >> 1;
        int y = MathHelper.floor_double(d.posY) >> 1;
        int z = MathHelper.floor_double(d.posZ) >> 1;
        return pack(x, y, z);
    }

}
