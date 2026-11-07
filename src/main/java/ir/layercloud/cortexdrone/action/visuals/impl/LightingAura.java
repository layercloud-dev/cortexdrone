package ir.layercloud.cortexdrone.action.visuals.impl;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.action.visuals.LightConfig;
import ir.layercloud.cortexdrone.action.visuals.LightResult;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.action.visuals.VisualsState;
import ir.layercloud.cortexdrone.service.world.ChunkAccess;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.util.HashMap;
import java.util.Map;

/**
 * Torch-free illumination aura.
 *
 * <p>While {@link VisualsState#lightAura} is enabled the drone raises the block
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

    /**
     * Applies a light tool request: toggle, or set colour/radius/level.
     */
    public LightResult configure(DroneController ctrl, VisualsState state, LightConfig config) {
        if (ctrl == null || ctrl.drone == null) {
            return LightResult.failure("No active drone");
        }
        boolean wasActive = state.lightAura;
        if (config.getEnabled() != null) {
            state.lightAura = config.getEnabled();
        } else if (config.isToggle()) {
            state.lightAura = !state.lightAura;
        }
        if (config.getRadius() != null) {
            state.lightRadius = clamp(config.getRadius(), MIN_RADIUS, MAX_RADIUS);
        }
        if (config.getLevel() != null) {
            state.lightIntensity = clamp(config.getLevel(), MIN_LEVEL, MAX_LEVEL);
        }
        if (config.getColor() != null) {
            state.lightColor = config.getColor();
        }

        if (state.lightAura) {
            state.lightCooldown = 0;
            state.lightAnchor = Long.MIN_VALUE;
            applyAura(ctrl, state);
        } else if (wasActive) {
            clearAura(ctrl, state);
        }

        return LightResult.builder()
                .ok(true)
                .active(state.lightAura)
                .radius(state.lightRadius)
                .level(state.lightIntensity)
                .color(state.lightColor)
                .cells(state.lightCells.size())
                .torchFree(true)
                .build();
    }

    /**
     * Called every server tick by the coordinator for each active controller.
     */
    public void tick(DroneController ctrl, VisualsState state) {
        if (ctrl == null || ctrl.drone == null) return;
        World world = ctrl.drone.worldObj;
        if (world == null || world.isRemote) return;
        if (!state.lightAura) {
            if (!state.lightCells.isEmpty()) clearAura(ctrl, state);
            return;
        }
        if (state.lightCooldown > 0) {
            state.lightCooldown--;
            return;
        }
        state.lightCooldown = APPLY_INTERVAL_TICKS;
        long anchor = packAnchor(ctrl.drone);
        if (anchor == state.lightAnchor && !state.lightCells.isEmpty()) {
            return;
        }
        applyAura(ctrl, state);
    }

    /**
     * Restores every voxel this aura touched. Safe to call repeatedly.
     */
    public void clearAura(DroneController ctrl, VisualsState state) {
        if (ctrl == null) return;
        if (ctrl.drone == null || ctrl.drone.worldObj == null) {
            state.lightCells.clear();
            state.lightAnchor = Long.MIN_VALUE;
            return;
        }
        World world = ctrl.drone.worldObj;
        Map<Long, Integer> chunkMasks = new HashMap<>();
        for (Map.Entry<Long, Integer> e : state.lightCells.entrySet()) {
            long key = e.getKey();
            int x = unpackX(key);
            int y = unpackY(key);
            int z = unpackZ(key);
            world.setLightValue(EnumSkyBlock.Block, x, y, z, e.getValue());
            addChunkMask(chunkMasks, x, y, z);
        }
        int restored = state.lightCells.size();
        state.lightCells.clear();
        state.lightAnchor = Long.MIN_VALUE;
        syncChunks(world, chunkMasks);
        if (restored > 0) {
            this.logger.log("LIGHT", ctrl, "Light aura off, restored " + restored + " voxels", "");
        }
    }

    private void applyAura(DroneController ctrl, VisualsState state) {
        Drone d = ctrl.drone;
        World world = d.worldObj;
        if (world == null) return;

        int cx = MathHelper.floor_double(d.posX);
        int cy = MathHelper.floor_double(d.posY);
        int cz = MathHelper.floor_double(d.posZ);
        int radius = clamp(state.lightRadius, MIN_RADIUS, MAX_RADIUS);
        int level = clamp(state.lightIntensity, MIN_LEVEL, MAX_LEVEL);

        Map<Long, Integer> previous = new HashMap<>(state.lightCells);
        state.lightCells.clear();
        Map<Long, Integer> chunkMasks = new HashMap<>();
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
                            ? original
                            : world.getSavedLightValue(EnumSkyBlock.Block, x, y, z);
                    state.lightCells.put(key, before);
                    int want = Math.max(1, level - (int) Math.sqrt(d2));
                    if (before < want) {
                        world.setLightValue(EnumSkyBlock.Block, x, y, z, want);
                        addChunkMask(chunkMasks, x, y, z);
                    }
                }
            }
        }

        // Anything left in `previous` fell out of the aura: put the world back.
        for (Map.Entry<Long, Integer> e : previous.entrySet()) {
            long key = e.getKey();
            int x = unpackX(key);
            int y = unpackY(key);
            int z = unpackZ(key);
            world.setLightValue(EnumSkyBlock.Block, x, y, z, e.getValue());
            addChunkMask(chunkMasks, x, y, z);
        }

        state.lightAnchor = packAnchor(d);
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
            int cx = unpackChunkX(ce.getKey());
            int cz = unpackChunkZ(ce.getKey());
            int mask = ce.getValue() & 0xFFFF;
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
        chunkMasks.compute(key, (k, old) -> (old == null ? 0 : old) | bit);
    }


    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    private static int unpackX(long k) {
        int x = (int) ((k >> 38) & 0x3FFFFFF);
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
