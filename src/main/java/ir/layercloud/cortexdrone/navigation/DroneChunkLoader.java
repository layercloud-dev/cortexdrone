package ir.layercloud.cortexdrone.navigation;

import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.AIConfig;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.BuildTask;
import ir.layercloud.cortexdrone.model.DroneController;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.entity.player.EntityPlayerMP;
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
    // Forge's default NORMAL-ticket depth is 25. Core 3x3 + a short travel
    // corridor must fit under it.
    private static final int MAX_TICKET_CHUNKS = 24;
    private static final int CORRIDOR_LOOKAHEAD_WAYPOINTS = 6;
    private static final int MINE_QUEUE_LOOKAHEAD = 8;
    private static final int BUILD_QUEUE_LOOKAHEAD = 4;

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
        if (config.getSavedDrones() != null) {
            for (AIConfig.SavedDroneInfo sd : config.getSavedDrones()) {
                if (sd == null || sd.getX() == null || sd.getZ() == null) continue;
                WorldServer ws = srv.func_71218_a(sd.getDimension());
                if (ws == null) ws = overworld;
                String key = "saved_drone:" + (sd.getUuid() != null ? sd.getUuid() : sd.getOwner());
                if (forceAnchorArea(ws, key, sd.getX().doubleValue(), sd.getZ().doubleValue(), ANCHOR_CHUNK_RADIUS)) {
                    forcedAnchors++;
                }
            }
        }
        if (config.getPlayerBases() != null) {
            for (Map.Entry<String, double[]> e : config.getPlayerBases().entrySet()) {
                double[] coords = e.getValue();
                if (coords != null && coords.length >= 3) {
                    if (forceAnchorArea(overworld, "base:" + e.getKey(), coords[0], coords[2], ANCHOR_CHUNK_RADIUS)) {
                        forcedAnchors++;
                    }
                }
            }
        }
        if (config.getSavedLocations() != null) {
            for (Map.Entry<String, AIConfig.SavedLocation> e : config.getSavedLocations().entrySet()) {
                AIConfig.SavedLocation loc = e.getValue();
                if (loc != null) {
                    if (forceAnchorArea(overworld, "loc:" + e.getKey(), loc.getX(), loc.getZ(), ANCHOR_CHUNK_RADIUS)) {
                        forcedAnchors++;
                    }
                }
            }
        }
        if (forcedAnchors > 0 && this.logger != null) {
            this.logger.log("SYSTEM", null, "ForgeChunkManager forced " + forcedAnchors + " persistent drone/base chunk anchor(s)", "");
        }
    }

    /**
     * Re-applies player ownership to every persistent anchor ticket. Anchors keep
     * bases/waypoints loaded when players are away, but a chunk inside a player's
     * view belongs to vanilla: we release our claim while the player is there and
     * re-claim it when they leave.
     */
    public synchronized void refreshAnchors() {
        if (this.anchorTickets.isEmpty()) return;
        for (Map.Entry<String, Ticket> e : this.anchorTickets.entrySet()) {
            Ticket ticket = e.getValue();
            if (ticket == null || ticket.world == null) continue;
            try {
                NBTTagCompound data = ticket.getModData();
                if (data == null || !data.func_74764_b("cx") || !data.func_74764_b("cz")) continue;
                int cx = data.func_74762_e("cx");
                int cz = data.func_74762_e("cz");
                int radius = data.func_74764_b("radius") ? Math.min(2, Math.max(0, data.func_74762_e("radius"))) : ANCHOR_CHUNK_RADIUS;
                applyTicketChunks(ticket.world, ticket, buildGrid(cx, cz, radius), true);
            } catch (Throwable ignored) {
            }
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

    /**
     * Maintains the force-loaded chunk set for one drone.
     *
     * <p>Policy: chunks inside any online player's view radius are vanilla-owned —
     * the mod never forces and never unforces them. Only chunks the player cannot
     * keep loaded (far-away drone routes) are managed here. The maintained set is
     * the drone's 3x3 plus a short corridor of chunks along its live path, so a
     * chunk cannot unload right after the drone reaches it while the drone still
     * has to fly through the next waypoints.
     */
    public synchronized void updateDroneTicket(DroneController ctrl) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70128_L) return;
        World w = ctrl.drone.field_70170_p;
        if (!(w instanceof WorldServer)) return;
        WorldServer world = (WorldServer) w;
        int dim = world.field_73011_w != null ? world.field_73011_w.field_76574_g : 0;
        int cx = MathHelper.func_76128_c(ctrl.drone.field_70165_t) >> 4;
        int cz = MathHelper.func_76128_c(ctrl.drone.field_70161_v) >> 4;

        boolean movedChunk = ctrl.forcedChunkX != cx || ctrl.forcedChunkZ != cz || ctrl.forcedDim != dim;
        // Re-evaluate often enough that a player walking in/out of view (or the
        // player's own chunk ticket shrinking) cannot leave a needed chunk in the
        // vanilla unload window. 5 ticks = 250 ms.
        boolean periodic = ctrl.tickClock - ctrl.lastChunkPlanTick >= 5;
        if (!movedChunk && !periodic && this.droneTickets.containsKey(ctrl.id)) {
            return;
        }
        ctrl.lastChunkPlanTick = ctrl.tickClock;

        Set<ChunkCoordIntPair> desired = buildDroneChunkPlan(ctrl, cx, cz);
        int managed = 0;
        int playerOwned = 0;
        for (ChunkCoordIntPair pair : desired) {
            if (isPlayerManaged(world, pair.field_77276_a, pair.field_77275_b)) playerOwned++;
            else managed++;
        }
        if (ctrl.tickClock - ctrl.lastChunkLogTick >= 200
            && (managed != ctrl.lastChunkManaged || playerOwned != ctrl.lastChunkPlayerOwned)) {
            ctrl.lastChunkLogTick = ctrl.tickClock;
            ctrl.lastChunkManaged = managed;
            ctrl.lastChunkPlayerOwned = playerOwned;
            if (this.logger != null) {
                this.logger.log("CHUNK", ctrl, "Chunk ownership updated",
                    "modManaged=" + managed + " playerOwned(skipped)=" + playerOwned
                        + " corridorWaypoints=" + Math.min(ctrl.pathWaypoints.size(), ctrl.pathIndex + CORRIDOR_LOOKAHEAD_WAYPOINTS));
            }
        }
        try {
            // A restored ticket for this drone is a startup anchor; once the live
            // drone owns its chunks, drop the duplicate so old positions are not
            // force-loaded forever.
            releaseDroneStartupAnchor(ctrl);

            Ticket ticket = this.droneTickets.get(ctrl.id);
            if (ticket != null && ticket.world != world) {
                try { ForgeChunkManager.releaseTicket(ticket); } catch (Throwable ignored) {}
                this.droneTickets.remove(ctrl.id);
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
                applyTicketChunks(world, ticket, desired, true);
            } else {
                loadGridImmediate(world, cx, cz, DRONE_CHUNK_RADIUS);
            }
            ctrl.forcedChunkX = cx;
            ctrl.forcedChunkZ = cz;
            ctrl.forcedDim = dim;
        } catch (Throwable ignored) {
        }
    }

    private Set<ChunkCoordIntPair> buildDroneChunkPlan(DroneController ctrl, int cx, int cz) {
        Set<ChunkCoordIntPair> desired = new LinkedHashSet<ChunkCoordIntPair>();
        for (int dx = -DRONE_CHUNK_RADIUS; dx <= DRONE_CHUNK_RADIUS; dx++) {
            for (int dz = -DRONE_CHUNK_RADIUS; dz <= DRONE_CHUNK_RADIUS; dz++) {
                desired.add(new ChunkCoordIntPair(cx + dx, cz + dz));
            }
        }
        // Working set first: the mine/build queues are state that must not be
        // dropped because the chunk carrying it unloads. The old plan only kept
        // the drone's 3x3, so queued blocks in the previous chunk looked "gone"
        // and the queue purged itself (the abandoned mining task).
        if (ctrl.currentMineTarget != null) {
            desired.add(new ChunkCoordIntPair(ctrl.currentMineTarget[0] >> 4, ctrl.currentMineTarget[2] >> 4));
        }
        int queuedChunks = 0;
        for (int[] p : ctrl.mineQueue) {
            if (p == null) continue;
            desired.add(new ChunkCoordIntPair(p[0] >> 4, p[2] >> 4));
            if (++queuedChunks >= MINE_QUEUE_LOOKAHEAD) break;
        }
        queuedChunks = 0;
        for (BuildTask bt : ctrl.buildQueue) {
            if (bt == null) continue;
            desired.add(new ChunkCoordIntPair(bt.getX() >> 4, bt.getZ() >> 4));
            if (++queuedChunks >= BUILD_QUEUE_LOOKAHEAD) break;
        }
        // Travel corridor: the chunks the drone is about to fly through.
        int from = Math.max(0, ctrl.pathIndex);
        int to = Math.min(ctrl.pathWaypoints.size(), from + CORRIDOR_LOOKAHEAD_WAYPOINTS);
        for (int i = from; i < to; i++) {
            double[] wp = ctrl.pathWaypoints.get(i);
            if (wp != null) {
                desired.add(new ChunkCoordIntPair(MathHelper.func_76128_c(wp[0]) >> 4, MathHelper.func_76128_c(wp[2]) >> 4));
            }
        }
        if (!Double.isNaN(ctrl.goalX) && !Double.isNaN(ctrl.goalZ)) {
            desired.add(new ChunkCoordIntPair(MathHelper.func_76128_c(ctrl.goalX) >> 4, MathHelper.func_76128_c(ctrl.goalZ) >> 4));
        }
        if (desired.size() <= MAX_TICKET_CHUNKS) return desired;
        Set<ChunkCoordIntPair> capped = new LinkedHashSet<ChunkCoordIntPair>();
        for (ChunkCoordIntPair pair : desired) {
            if (capped.size() >= MAX_TICKET_CHUNKS) break;
            capped.add(pair);
        }
        return capped;
    }

    private void releaseDroneStartupAnchor(DroneController ctrl) {
        if (ctrl.entityUuid == null) return;
        String uuid = ctrl.entityUuid.toString();
        Ticket t = this.anchorTickets.remove("saved_drone:" + uuid);
        if (t != null) {
            try { ForgeChunkManager.releaseTicket(t); } catch (Throwable ignored) {}
        }
        t = this.anchorTickets.remove("drone:" + uuid);
        if (t != null) {
            try { ForgeChunkManager.releaseTicket(t); } catch (Throwable ignored) {}
        }
    }

    /**
     * True when a player's normal chunk view already owns this chunk. The mod must
     * not force or unforce those chunks: it would fight vanilla's player chunk
     * manager (e.g. dropping chunks the user is standing in).
     */
    private static boolean isPlayerManaged(World world, int cx, int cz) {
        if (!(world instanceof WorldServer) || world.field_73010_i == null) return false;
        int view = viewDistanceChunks() + 1;
        for (Object o : world.field_73010_i) {
            if (!(o instanceof EntityPlayerMP)) continue;
            EntityPlayerMP p = (EntityPlayerMP) o;
            if (p.field_70170_p != world) continue;
            int pcx = MathHelper.func_76128_c(p.field_70165_t) >> 4;
            int pcz = MathHelper.func_76128_c(p.field_70161_v) >> 4;
            if (Math.abs(pcx - cx) <= view && Math.abs(pcz - cz) <= view) {
                return true;
            }
        }
        return false;
    }

    private static int viewDistanceChunks() {
        try {
            MinecraftServer srv = MinecraftServer.func_71276_C();
            if (srv != null && srv.func_71203_ab() != null) {
                int v = srv.func_71203_ab().func_72395_o();
                return Math.max(3, Math.min(15, v));
            }
        } catch (Throwable ignored) {
        }
        return 10;
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
            String uuid = ctrl.entityUuid.toString();
            Ticket savedTicket = this.anchorTickets.remove("saved_drone:" + uuid);
            if (savedTicket != null) {
                try {
                    ForgeChunkManager.releaseTicket(savedTicket);
                } catch (Throwable ignored) {
                }
            }
            Ticket liveAnchor = this.anchorTickets.remove("drone:" + uuid);
            if (liveAnchor != null) {
                try {
                    ForgeChunkManager.releaseTicket(liveAnchor);
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
            int dim = ws.field_73011_w != null ? ws.field_73011_w.field_76574_g : 0;
            Ticket ticket = this.rollingTickets.get(dim);
            if (ticket != null && ticket.world != ws) {
                try { ForgeChunkManager.releaseTicket(ticket); } catch (Throwable ignored) {}
                ticket = null;
                this.rollingOrder.remove(dim);
            }
            Deque<ChunkCoordIntPair> queue = this.rollingOrder.get(dim);

            // A chunk inside a player's view is vanilla-owned. Release our pin if
            // we had one, and never pin it again while the player is there.
            if (ticket != null && queue != null) {
                java.util.Iterator<ChunkCoordIntPair> it = queue.iterator();
                while (it.hasNext()) {
                    ChunkCoordIntPair pinned = it.next();
                    if (isPlayerManaged(world, pinned.field_77276_a, pinned.field_77275_b)) {
                        try { ForgeChunkManager.unforceChunk(ticket, pinned); } catch (Throwable ignored) {}
                        it.remove();
                    }
                }
            }
            if (isPlayerManaged(world, cx, cz)) return;

            ws.field_73059_b.func_73158_c(cx, cz);
            if (ticket == null) {
                ticket = ForgeChunkManager.requestTicket(this.mod, ws, ForgeChunkManager.Type.NORMAL);
                if (ticket == null) return;
                this.rollingTickets.put(dim, ticket);
            }
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
        applyTicketChunks(world, ticket, buildGrid(cx, cz, radius), true);
    }

    private static Set<ChunkCoordIntPair> buildGrid(int cx, int cz, int radius) {
        Set<ChunkCoordIntPair> desired = new LinkedHashSet<ChunkCoordIntPair>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                desired.add(new ChunkCoordIntPair(cx + dx, cz + dz));
            }
        }
        return desired;
    }

    /**
     * Applies a desired chunk set to a ticket.
     *
     * @param playerAware when true, chunks inside a player's view radius are left
     *                    entirely to vanilla: never forced by us and never
     *                    unforced by us.
     */
    private static void applyTicketChunks(World world, Ticket ticket, Set<ChunkCoordIntPair> desired, boolean playerAware) {
        if (world == null || ticket == null || desired == null) return;
        try {
            Set<ChunkCoordIntPair> current = new HashSet<ChunkCoordIntPair>(ticket.getChunkList());
            for (ChunkCoordIntPair old : current) {
                boolean playerOwns = playerAware && isPlayerManaged(world, old.field_77276_a, old.field_77275_b);
                if (desired.contains(old) && !playerOwns) continue;
                // Either the drone no longer needs it, or a player now owns it.
                // In both cases remove OUR ticket; vanilla keeps player chunks
                // loaded, so the chunk itself must never be unloaded here.
                if (!desired.contains(old) && playerAware && playerOwns) continue;
                try { ForgeChunkManager.unforceChunk(ticket, old); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }
        Set<ChunkCoordIntPair> already = new HashSet<ChunkCoordIntPair>();
        try {
            already.addAll(ticket.getChunkList());
        } catch (Throwable ignored) {
        }
        for (ChunkCoordIntPair pair : desired) {
            if (playerAware && isPlayerManaged(world, pair.field_77276_a, pair.field_77275_b)) continue;
            if (already.contains(pair)) continue;
            try {
                // Canonical Forge 1.7.10 pattern (same as OpenComputers'
                // ChunkloaderUpgradeHandler): forceChunk only. Calling
                // ChunkProviderServer.loadChunk here caused sync loads inside the
                // world-load callback and load/unload thrash at ticket edges.
                // On-demand loads (A* / the drone) bring the chunk in; the ticket
                // then protects it from the unload queue.
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
