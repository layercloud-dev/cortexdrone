package ir.layercloud.cortexdrone.navigation;

import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.AIConfig;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;

/**
 * Persistent chunkloader manager using Minecraft Forge's {@link ForgeChunkManager}
 * (the same ticket/forceChunk mechanism used by ChickenChunks and OpenComputers'
 * ChunkloaderUpgradeHandler). Keeps a 3x3 chunk grid around every active drone,
 * saved drone, player base, and saved waypoint permanently loaded and ticking even
 * when players are offline or far away.
 */
public class DroneChunkLoader implements ForgeChunkManager.LoadingCallback {
    private static final int DRONE_CHUNK_RADIUS = 1; // 3x3 = 9 chunks per drone
    private static final int ANCHOR_CHUNK_RADIUS = 1; // 3x3 = 9 chunks per base/saved drone
    private static final int ROLLING_MAX_CHUNKS = 24;

    private static volatile DroneChunkLoader instance;

    private final CortexDroneMod mod;
    private final DroneLogger logger;
    private final Map<Integer, Ticket> droneTickets = new ConcurrentHashMap<Integer, Ticket>();
    private final Map<String, Ticket> anchorTickets = new ConcurrentHashMap<String, Ticket>();
    private final Map<Integer, Ticket> rollingTickets = new ConcurrentHashMap<Integer, Ticket>();
    private final Map<Integer, Deque<ChunkCoordIntPair>> rollingOrder = new ConcurrentHashMap<Integer, Deque<ChunkCoordIntPair>>();

    public DroneChunkLoader(CortexDroneMod mod, DroneLogger logger) {
        this.mod = mod;
        this.logger = logger;
        instance = this;
    }

    public static DroneChunkLoader getInstance() {
        return instance;
    }

    @Override
    public void ticketsLoaded(List<Ticket> tickets, World world) {
        if (tickets == null || world == null) return;
        for (Ticket ticket : tickets) {
            if (ticket == null) continue;
            try {
                NBTTagCompound data = ticket.getModData();
                if (data != null && data.func_74764_b("cx") && data.func_74764_b("cz")) {
                    int cx = data.func_74762_e("cx");
                    int cz = data.func_74762_e("cz");
                    int radius = data.func_74764_b("radius") ? Math.min(2, Math.max(0, data.func_74762_e("radius"))) : 1;
                    String anchorKey = data.func_74764_b("anchorKey") ? data.func_74779_i("anchorKey") : "";
                    if (!anchorKey.isEmpty() && !this.anchorTickets.containsKey(anchorKey)) {
                        this.anchorTickets.put(anchorKey, ticket);
                        forceGridOnTicket(world, ticket, cx, cz, radius);
                    } else {
                        ForgeChunkManager.releaseTicket(ticket);
                    }
                } else {
                    ForgeChunkManager.releaseTicket(ticket);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public void forceSavedStateChunks(AIConfig config) {
        if (config == null) return;
        MinecraftServer srv = MinecraftServer.func_71276_C();
        if (srv == null || srv.field_71305_c == null || srv.field_71305_c.length == 0) return;

        WorldServer overworld = srv.func_71218_a(0);
        if (overworld == null) overworld = srv.field_71305_c[0];
        if (overworld == null) return;

        int forcedAnchors = 0;
        if (config.savedDrones != null) {
            for (AIConfig.SavedDroneInfo sd : config.savedDrones) {
                if (sd == null || sd.x == null || sd.z == null) continue;
                WorldServer ws = srv.func_71218_a(sd.dimension);
                if (ws == null) ws = overworld;
                String key = "saved_drone:" + (sd.uuid != null ? sd.uuid : sd.owner);
                if (forceAnchorArea(ws, key, sd.x.doubleValue(), sd.z.doubleValue(), ANCHOR_CHUNK_RADIUS)) {
                    forcedAnchors++;
                }
            }
        }
        if (config.playerBases != null) {
            for (Map.Entry<String, double[]> e : config.playerBases.entrySet()) {
                double[] coords = e.getValue();
                if (coords != null && coords.length >= 3) {
                    if (forceAnchorArea(overworld, "base:" + e.getKey(), coords[0], coords[2], ANCHOR_CHUNK_RADIUS)) {
                        forcedAnchors++;
                    }
                }
            }
        }
        if (config.savedLocations != null) {
            for (Map.Entry<String, AIConfig.SavedLocation> e : config.savedLocations.entrySet()) {
                AIConfig.SavedLocation loc = e.getValue();
                if (loc != null) {
                    if (forceAnchorArea(overworld, "loc:" + e.getKey(), loc.x, loc.z, ANCHOR_CHUNK_RADIUS)) {
                        forcedAnchors++;
                    }
                }
            }
        }
        if (forcedAnchors > 0 && this.logger != null) {
            this.logger.log("SYSTEM", null, "ForgeChunkManager forced " + forcedAnchors + " persistent drone/base chunk anchor(s)", "");
        }
    }

    public synchronized boolean forceAnchorArea(WorldServer world, String anchorKey, double x, double z, int radius) {
        if (world == null || anchorKey == null || anchorKey.isEmpty()) return false;
        try {
            int cx = MathHelper.func_76128_c(x) >> 4;
            int cz = MathHelper.func_76128_c(z) >> 4;
            Ticket ticket = this.anchorTickets.get(anchorKey);
            if (ticket != null && ticket.world != world) {
                try { ForgeChunkManager.releaseTicket(ticket); } catch (Throwable ignored) {}
                ticket = null;
            }
            if (ticket == null) {
                ticket = ForgeChunkManager.requestTicket(this.mod, world, ForgeChunkManager.Type.NORMAL);
                if (ticket == null) {
                    loadGridImmediate(world, cx, cz, radius);
                    return false;
                }
                this.anchorTickets.put(anchorKey, ticket);
            }
            NBTTagCompound data = ticket.getModData();
            if (data != null) {
                data.func_74778_a("anchorKey", anchorKey);
                data.func_74768_a("cx", cx);
                data.func_74768_a("cz", cz);
                data.func_74768_a("radius", radius);
            }
            forceGridOnTicket(world, ticket, cx, cz, radius);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public synchronized void updateDroneTicket(DroneController ctrl) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70128_L) return;
        World w = ctrl.drone.field_70170_p;
        if (!(w instanceof WorldServer)) return;
        WorldServer world = (WorldServer) w;
        int dim = world.field_73011_w != null ? world.field_73011_w.field_76574_g : 0;
        int cx = MathHelper.func_76128_c(ctrl.drone.field_70165_t) >> 4;
        int cz = MathHelper.func_76128_c(ctrl.drone.field_70161_v) >> 4;

        if (ctrl.forcedChunkX == cx && ctrl.forcedChunkZ == cz && ctrl.forcedDim == dim && this.droneTickets.containsKey(ctrl.id)) {
            return;
        }
        try {
            Ticket ticket = this.droneTickets.get(ctrl.id);
            if (ticket != null && ticket.world != world) {
                try { ForgeChunkManager.releaseTicket(ticket); } catch (Throwable ignored) {}
                ticket = null;
            }
            if (ticket == null) {
                ticket = ForgeChunkManager.requestTicket(this.mod, world, ForgeChunkManager.Type.NORMAL);
                if (ticket != null) {
                    this.droneTickets.put(ctrl.id, ticket);
                }
            }
            if (ticket != null) {
                NBTTagCompound data = ticket.getModData();
                if (data != null) {
                    data.func_74778_a("anchorKey", "drone:" + (ctrl.entityUuid != null ? ctrl.entityUuid.toString() : ctrl.id));
                    data.func_74768_a("cx", cx);
                    data.func_74768_a("cz", cz);
                    data.func_74768_a("radius", DRONE_CHUNK_RADIUS);
                }
                forceGridOnTicket(world, ticket, cx, cz, DRONE_CHUNK_RADIUS);
            } else {
                loadGridImmediate(world, cx, cz, DRONE_CHUNK_RADIUS);
            }
            ctrl.forcedChunkX = cx;
            ctrl.forcedChunkZ = cz;
            ctrl.forcedDim = dim;
        } catch (Throwable ignored) {
        }
    }

    public synchronized void releaseDroneTicket(DroneController ctrl) {
        if (ctrl == null) return;
        Ticket ticket = this.droneTickets.remove(ctrl.id);
        if (ticket != null) {
            try {
                ForgeChunkManager.releaseTicket(ticket);
            } catch (Throwable ignored) {
            }
        }
        if (ctrl.entityUuid != null) {
            Ticket savedTicket = this.anchorTickets.remove("saved_drone:" + ctrl.entityUuid.toString());
            if (savedTicket != null) {
                try {
                    ForgeChunkManager.releaseTicket(savedTicket);
                } catch (Throwable ignored) {
                }
            }
        }
        ctrl.forcedChunkX = Integer.MIN_VALUE;
        ctrl.forcedChunkZ = Integer.MIN_VALUE;
        ctrl.forcedDim = Integer.MIN_VALUE;
    }

    public synchronized void pinChunk(World world, int cx, int cz) {
        if (!(world instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) world;
        try {
            ws.field_73059_b.func_73158_c(cx, cz);
            int dim = ws.field_73011_w != null ? ws.field_73011_w.field_76574_g : 0;
            Ticket ticket = this.rollingTickets.get(dim);
            if (ticket != null && ticket.world != ws) {
                try { ForgeChunkManager.releaseTicket(ticket); } catch (Throwable ignored) {}
                ticket = null;
                this.rollingOrder.remove(dim);
            }
            if (ticket == null) {
                ticket = ForgeChunkManager.requestTicket(this.mod, ws, ForgeChunkManager.Type.NORMAL);
                if (ticket == null) return;
                this.rollingTickets.put(dim, ticket);
            }
            Deque<ChunkCoordIntPair> queue = this.rollingOrder.get(dim);
            if (queue == null) {
                queue = new ArrayDeque<ChunkCoordIntPair>();
                this.rollingOrder.put(dim, queue);
            }
            ChunkCoordIntPair pair = new ChunkCoordIntPair(cx, cz);
            if (queue.contains(pair)) return;
            int cap = Math.min(ROLLING_MAX_CHUNKS, Math.max(9, ticket.getMaxChunkListDepth()));
            while (queue.size() >= cap) {
                ChunkCoordIntPair evicted = queue.pollFirst();
                if (evicted != null) {
                    try { ForgeChunkManager.unforceChunk(ticket, evicted); } catch (Throwable ignored) {}
                }
            }
            ForgeChunkManager.forceChunk(ticket, pair);
            queue.addLast(pair);
        } catch (Throwable ignored) {
        }
    }

    public static void ensureChunkLoadedStatic(World world, double x, double z) {
        if (world instanceof WorldServer) {
            try {
                int cx = MathHelper.func_76128_c(x) >> 4;
                int cz = MathHelper.func_76128_c(z) >> 4;
                DroneChunkLoader loader = instance;
                if (loader != null) {
                    loader.pinChunk(world, cx, cz);
                } else {
                    ((WorldServer) world).field_73059_b.func_73158_c(cx, cz);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void forceGridOnTicket(World world, Ticket ticket, int cx, int cz, int radius) {
        Set<ChunkCoordIntPair> desired = new HashSet<ChunkCoordIntPair>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                desired.add(new ChunkCoordIntPair(cx + dx, cz + dz));
            }
        }
        try {
            Set<ChunkCoordIntPair> current = new HashSet<ChunkCoordIntPair>(ticket.getChunkList());
            for (ChunkCoordIntPair old : current) {
                if (!desired.contains(old)) {
                    try { ForgeChunkManager.unforceChunk(ticket, old); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {
        }
        for (ChunkCoordIntPair pair : desired) {
            try {
                if (world instanceof WorldServer) {
                    ((WorldServer) world).field_73059_b.func_73158_c(pair.field_77276_a, pair.field_77275_b);
                }
                ForgeChunkManager.forceChunk(ticket, pair);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void loadGridImmediate(WorldServer world, int cx, int cz, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                try {
                    world.field_73059_b.func_73158_c(cx + dx, cz + dz);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
