package ir.layercloud.cortexdrone.action.effect;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
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
    public JsonObject configure(DroneController ctrl, JsonObject args) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        if (ctrl == null || ctrl.drone == null) {
            res.addProperty("ok", false);
            res.addProperty("error", "No active drone");
            return res;
        }
        if (args == null) {
            args = new JsonObject();
        }
        boolean wasActive = ctrl.autoTorch;
        if (args.has("enabled") && !args.get("enabled").isJsonNull()) {
            ctrl.autoTorch = args.get("enabled").getAsBoolean();
        } else if (args.has("toggle") && args.get("toggle").getAsBoolean()) {
            ctrl.autoTorch = !ctrl.autoTorch;
        }
        if (args.has("radius") && !args.get("radius").isJsonNull()) {
            ctrl.lightRadius = clamp(args.get("radius").getAsInt(), MIN_RADIUS, MAX_RADIUS);
        }
        if (args.has("level") && !args.get("level").isJsonNull()) {
            ctrl.lightIntensity = clamp(args.get("level").getAsInt(), MIN_LEVEL, MAX_LEVEL);
        }
        Integer color = parseColor(args);
        if (color != null) {
            ctrl.lightColor = color.intValue();
        }

        if (ctrl.autoTorch) {
            // Force an immediate refresh when toggled on or recoloured.
            ctrl.lightCooldown = 0;
            ctrl.lightAnchor = Long.MIN_VALUE;
            if (color != null && (color.intValue() & 0xFFFFFF) != 0xFFFFFF) {
                // Visibly tint the drone itself; 1.7.10 block light has no RGB channel.
                ctrl.customLedColor = Integer.valueOf(color.intValue() & 0xFFFFFF);
                ctrl.ledEffect = "NONE";
                ctrl.drone.lightColor_$eq(color.intValue() & 0xFFFFFF);
            }
            applyAura(ctrl);
        } else if (wasActive) {
            clearAura(ctrl);
        }

        res.addProperty("active", ctrl.autoTorch);
        res.addProperty("radius", ctrl.lightRadius);
        res.addProperty("level", ctrl.lightIntensity);
        res.addProperty("color", String.format("0x%06X", ctrl.lightColor & 0xFFFFFF));
        res.addProperty("cells", ctrl.lightCells.size());
        res.addProperty("torchFree", true);
        return res;
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

    private static Integer parseColor(JsonObject args) {
        if (!args.has("color") || args.get("color").isJsonNull()) return null;
        try {
            if (args.get("color").isJsonPrimitive()) {
                if (args.get("color").getAsJsonPrimitive().isNumber()) {
                    return Integer.valueOf(args.get("color").getAsInt() & 0xFFFFFF);
                }
                String s = args.get("color").getAsString().trim();
                if (s.startsWith("#")) s = s.substring(1);
                if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
                String named = namedColor(s);
                if (named != null) s = named;
                return Integer.valueOf((int) Long.parseLong(s, 16) & 0xFFFFFF);
            }
            if (args.get("color").isJsonArray()) {
                com.google.gson.JsonArray a = args.getAsJsonArray("color");
                if (a.size() >= 3) {
                    int r = clamp(a.get(0).getAsInt(), 0, 255);
                    int g = clamp(a.get(1).getAsInt(), 0, 255);
                    int b = clamp(a.get(2).getAsInt(), 0, 255);
                    return Integer.valueOf((r << 16) | (g << 8) | b);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String namedColor(String raw) {
        String n = raw == null ? "" : raw.toLowerCase().replace("-", "").replace("_", "").replace(" ", "");
        if (n.equals("white") || n.equals("normal") || n.equals("default") || n.equals("warm")) return "FFFFFF";
        if (n.equals("red")) return "FF3B30";
        if (n.equals("orange")) return "FF9500";
        if (n.equals("yellow")) return "FFD60A";
        if (n.equals("green")) return "34C759";
        if (n.equals("cyan") || n.equals("aqua")) return "32ADE6";
        if (n.equals("blue")) return "0A84FF";
        if (n.equals("purple") || n.equals("violet")) return "AF52DE";
        if (n.equals("pink")) return "FF2D55";
        return null;
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
