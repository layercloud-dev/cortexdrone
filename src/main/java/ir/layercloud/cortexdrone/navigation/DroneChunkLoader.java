package ir.layercloud.cortexdrone.navigation;

import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.AIConfig;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.BuildTask;
import ir.layercloud.cortexdrone.model.DroneController;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.entity.Entity;
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
 * Forge 1.7.10 chunk manager for drones and persistent anchors.
 *
 * <p>Implemented from the two proven 1.7.10 designs:
 * <ul>
 *   <li>Railcraft {@code EntityCartAnchor}: a {@code Type.ENTITY} ticket bound to
 *       the moving entity, chunks forced in priority order with
 *       {@code reorderChunk}, Forge loads the entity's own chunk on world start;</li>
 *   <li>ChickenChunks {@code ChunkLoaderManager}: ticket pooled chunk ownership and a
 *       timed release queue, so chunks are not dropped the instant an entity crosses
 *       a border.</li>
 * </ul>
 *
 * <p>Forge's {@code forceChunk} does <b>not</b> load a chunk; it only protects an
 * already-loaded chunk from the unload queue (see Forge's
 * {@code ChunkProviderServer.unloadQueuedChunks} patch). The manager therefore
 * explicitly loads a small, budgeted "hot" set around the drone and lets the rest
 * of the plan be protected if/when the drone reaches it.
 *
 * <p>Player ownership is exact: chunks within a player's vanilla view distance are
 * left entirely to vanilla. The manager never forces, loads or unloads them, and
 * drops its own redundant claim when a player takes over.
 */
public class DroneChunkLoader implements ForgeChunkManager.LoadingCallback {
    private static final int DRONE_CHUNK_RADIUS = 1;       // 3x3 around the drone
    private static final int ANCHOR_CHUNK_RADIUS = 1;      // 3x3 around a base/waypoint
    private static final int MAX_CHUNKS = 25;              // Forge default ticket depth
    private static final int CORRIDOR_LOOKAHEAD_WAYPOINTS = 4;
    private static final int CORRIDOR_MAX_CHUNKS = 8;
    private static final int MINE_QUEUE_LOOKAHEAD = 4;
    private static final int BUILD_QUEUE_LOOKAHEAD = 2;
    /** Keep a chunk this long after it stops being needed (ChickenChunks uses 100). */
    private static final int RELEASE_GRACE_TICKS = 200;
    /** New chunk loads per 5-tick drone plan update. Prevents flight stutter. */
    private static final int DRONE_LOAD_BUDGET = 2;
    /** New chunk loads per 20-tick anchor refresh, shared by all anchors. */
    private static final int ANCHOR_LOAD_BUDGET = 2;

    private static volatile DroneChunkLoader instance;

    private final CortexDroneMod mod;
    private final DroneLogger logger;
    private final Map<Integer, Ticket> droneTickets = new ConcurrentHashMap<Integer, Ticket>();
    private final Map<String, Ticket> anchorTickets = new ConcurrentHashMap<String, Ticket>();
    /** ENTITY tickets restored by Forge on world load, keyed by drone UUID. */
    private final Map<UUID, Ticket> restoredEntityTickets = new ConcurrentHashMap<UUID, Ticket>();
    /** Chunks whose delayed release is pending, per ticket. */
    private final Map<Ticket, Map<ChunkCoordIntPair, Long>> releaseAt =
        new ConcurrentHashMap<Ticket, Map<ChunkCoordIntPair, Long>>();
    /** Chunks this manager explicitly loaded; avoids re-loading them every update. */
    private final Map<Ticket, Set<ChunkCoordIntPair>> explicitLoads =
        new ConcurrentHashMap<Ticket, Set<ChunkCoordIntPair>>();

    public DroneChunkLoader(CortexDroneMod mod, DroneLogger logger) {
        this.mod = mod;
        this.logger = logger;
        instance = this;
    }

    public static DroneChunkLoader getInstance() {
        return instance;
    }

    // ------------------------------------------------------------------
    // Forge callback
    // ------------------------------------------------------------------

    @Override
    public void ticketsLoaded(List<Ticket> tickets, World world) {
        if (tickets == null || world == null || !(world instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) world;
        for (Ticket ticket : tickets) {
            if (ticket == null) continue;
            try {
                // Railcraft pattern: Forge has already loaded and bound the entity
                // chunk for ENTITY tickets. Re-adopt the ticket and protect its 3x3.
                Entity entity = ticket.getEntity();
                if (entity != null) {
                    UUID id = entity.func_110124_au();
                    if (id != null) {
                        this.restoredEntityTickets.put(id, ticket);
                        int ecx = MathHelper.func_76128_c(entity.field_70165_t) >> 4;
                        int ecz = MathHelper.func_76128_c(entity.field_70161_v) >> 4;
                        applyTicketChunks(ws, ticket, buildGrid(ecx, ecz, DRONE_CHUNK_RADIUS), true, 0);
                        continue;
                    }
                }
                NBTTagCompound data = ticket.getModData();
                if (data != null && data.func_74764_b("cx") && data.func_74764_b("cz")) {
                    int cx = data.func_74762_e("cx");
                    int cz = data.func_74762_e("cz");
                    int radius = data.func_74764_b("radius")
                        ? Math.min(2, Math.max(0, data.func_74762_e("radius"))) : 1;
                    String anchorKey = data.func_74764_b("anchorKey") ? data.func_74779_i("anchorKey") : "";
                    if (!anchorKey.isEmpty() && !this.anchorTickets.containsKey(anchorKey)) {
                        this.anchorTickets.put(anchorKey, ticket);
                        applyTicketChunks(ws, ticket, buildGrid(cx, cz, radius), true, 0);
                    } else {
                        release(ticket);
                    }
                } else {
                    release(ticket);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Persistent anchors (bases, saved drones, saved locations)
    // ------------------------------------------------------------------

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
            this.logger.log("SYSTEM", null,
                "ForgeChunkManager forced " + forcedAnchors + " persistent drone/base chunk anchor(s)", "");
        }
    }

    /**
     * Re-applies player ownership to every anchor. Chunks inside a player's view
     * belong to vanilla; the anchor releases its claim while the player is there and
     * re-claims (and explicitly loads) them when the player leaves.
     */
    public synchronized void refreshAnchors() {
        if (this.anchorTickets.isEmpty()) return;
        int budget = ANCHOR_LOAD_BUDGET;
        for (Map.Entry<String, Ticket> e : this.anchorTickets.entrySet()) {
            Ticket ticket = e.getValue();
            if (ticket == null || !(ticket.world instanceof WorldServer)) continue;
            try {
                NBTTagCompound data = ticket.getModData();
                if (data == null || !data.func_74764_b("cx") || !data.func_74764_b("cz")) continue;
                int cx = data.func_74762_e("cx");
                int cz = data.func_74762_e("cz");
                int radius = data.func_74764_b("radius")
                    ? Math.min(2, Math.max(0, data.func_74762_e("radius"))) : ANCHOR_CHUNK_RADIUS;
                budget -= applyTicketChunks((WorldServer) ticket.world, ticket,
                    buildGrid(cx, cz, radius), true, budget);
                if (budget < 0) budget = 0;
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
                release(ticket);
                this.anchorTickets.remove(anchorKey);
                ticket = null;
            }
            if (ticket == null) {
                ticket = ForgeChunkManager.requestTicket(this.mod, world, ForgeChunkManager.Type.NORMAL);
                if (ticket == null) return false;
                this.anchorTickets.put(anchorKey, ticket);
            }
            NBTTagCompound data = ticket.getModData();
            if (data != null) {
                data.func_74778_a("anchorKey", anchorKey);
                data.func_74768_a("cx", cx);
                data.func_74768_a("cz", cz);
                data.func_74768_a("radius", radius);
            }
            applyTicketChunks(world, ticket, buildGrid(cx, cz, radius), true, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Drone ticket
    // ------------------------------------------------------------------

    /**
     * Maintains one ticket per drone. The ticket is {@code Type.ENTITY} so Forge
     * persists it with the drone UUID and reloads the drone's own chunk on server
     * start (Railcraft's anchor-cart design).
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
        boolean periodic = ctrl.tickClock - ctrl.lastChunkPlanTick >= 5;
        if (!movedChunk && !periodic && this.droneTickets.containsKey(ctrl.id)) return;
        ctrl.lastChunkPlanTick = ctrl.tickClock;

        Ticket ticket = adoptOrCreateTicket(ctrl, world);
        releaseDroneStartupAnchor(ctrl);

        List<ChunkCoordIntPair> plan = buildDroneChunkPlan(ctrl, cx, cz);
        int playerOwned = 0;
        for (ChunkCoordIntPair pair : plan) {
            if (isPlayerManaged(world, pair.field_77276_a, pair.field_77275_b)) playerOwned++;
        }
        int managed = plan.size() - playerOwned;

        if (ticket != null) {
            NBTTagCompound data = ticket.getModData();
            if (data != null) {
                data.func_74778_a("anchorKey",
                    "drone:" + (ctrl.entityUuid != null ? ctrl.entityUuid.toString() : ctrl.id));
                data.func_74768_a("cx", cx);
                data.func_74768_a("cz", cz);
                data.func_74768_a("radius", DRONE_CHUNK_RADIUS);
            }
            int loadedNow = applyTicketChunks(world, ticket, plan, true, DRONE_LOAD_BUDGET);
            int held = ticket.getChunkList().size();
            if (ctrl.tickClock - ctrl.lastChunkLogTick >= 100
                && (managed != ctrl.lastChunkManaged || playerOwned != ctrl.lastChunkPlayerOwned
                    || loadedNow > 0 || ctrl.tickClock - ctrl.lastChunkLogTick >= 600)) {
                ctrl.lastChunkLogTick = ctrl.tickClock;
                ctrl.lastChunkManaged = managed;
                ctrl.lastChunkPlayerOwned = playerOwned;
                if (this.logger != null) {
                    this.logger.log("CHUNK", ctrl, "Chunk ownership updated",
                        "modManaged=" + managed + " playerOwned(skipped)=" + playerOwned
                            + " plan=" + plan.size() + " held=" + held + " loadedNow=" + loadedNow
                            + " corridorWaypoints="
                            + Math.min(ctrl.pathWaypoints.size(), ctrl.pathIndex + CORRIDOR_LOOKAHEAD_WAYPOINTS));
                }
            }
        } else {
            // No ticket (mod ticket limit): at least keep the drone's own chunk loaded.
            loadHotChunks(world, plan, DRONE_LOAD_BUDGET);
        }
        ctrl.forcedChunkX = cx;
        ctrl.forcedChunkZ = cz;
        ctrl.forcedDim = dim;
    }

    private Ticket adoptOrCreateTicket(DroneController ctrl, WorldServer world) {
        Ticket ticket = this.droneTickets.get(ctrl.id);
        if (ticket != null && ticket.world == world) return ticket;
        if (ticket != null) {
            release(ticket);
            this.droneTickets.remove(ctrl.id);
        }
        if (ctrl.entityUuid != null) {
            Ticket restored = this.restoredEntityTickets.remove(ctrl.entityUuid);
            if (restored != null) {
                if (restored.world == world) {
                    try { restored.bindEntity(ctrl.drone); } catch (Throwable ignored) {}
                    this.droneTickets.put(ctrl.id, restored);
                    return restored;
                }
                release(restored);
            }
        }
        Ticket created = null;
        try {
            created = ForgeChunkManager.requestTicket(this.mod, world, ForgeChunkManager.Type.ENTITY);
            if (created != null) {
                created.bindEntity(ctrl.drone);
                this.droneTickets.put(ctrl.id, created);
            }
        } catch (Throwable t) {
            if (created != null) release(created);
        }
        return created;
    }

    /**
     * Priority plan (highest first). Forge's auto-eviction removes the oldest
     * requested chunk; {@link #applyTicketChunks} re-orders in this list's reverse
     * order so the drone's own 3x3 and nearest path are always at the tail.
     */
    private List<ChunkCoordIntPair> buildDroneChunkPlan(DroneController ctrl, int cx, int cz) {
        List<ChunkCoordIntPair> plan = new ArrayList<ChunkCoordIntPair>();
        Set<ChunkCoordIntPair> seen = new HashSet<ChunkCoordIntPair>();

        addChunk(plan, seen, cx, cz);
        for (int dx = -DRONE_CHUNK_RADIUS; dx <= DRONE_CHUNK_RADIUS; dx++) {
            for (int dz = -DRONE_CHUNK_RADIUS; dz <= DRONE_CHUNK_RADIUS; dz++) {
                if (dx == 0 && dz == 0) continue;
                addChunk(plan, seen, cx + dx, cz + dz);
            }
        }

        if (ctrl.currentMineTarget != null) {
            addChunk(plan, seen, ctrl.currentMineTarget[0] >> 4, ctrl.currentMineTarget[2] >> 4);
        }

        // Railcraft-style line chunks: a waypoint can be 100 blocks away but the
        // drone flies every chunk between here and there. The old planner only
        // added the two endpoint chunks, leaving the middle unprotected.
        double px = ctrl.drone.field_70165_t;
        double pz = ctrl.drone.field_70161_v;
        int added = 0;
        int limit = Math.min(ctrl.pathWaypoints.size(), ctrl.pathIndex + CORRIDOR_LOOKAHEAD_WAYPOINTS);
        for (int i = Math.max(0, ctrl.pathIndex); i < limit && added < CORRIDOR_MAX_CHUNKS; i++) {
            double[] wp = ctrl.pathWaypoints.get(i);
            if (wp == null) continue;
            added += addLineChunks(plan, seen, px, pz, wp[0], wp[2], CORRIDOR_MAX_CHUNKS - added);
            px = wp[0];
            pz = wp[2];
        }

        int queued = 0;
        for (int[] p : ctrl.mineQueue) {
            if (p == null) continue;
            addChunk(plan, seen, p[0] >> 4, p[2] >> 4);
            if (++queued >= MINE_QUEUE_LOOKAHEAD) break;
        }
        queued = 0;
        for (BuildTask bt : ctrl.buildQueue) {
            if (bt == null) continue;
            addChunk(plan, seen, bt.getX() >> 4, bt.getZ() >> 4);
            if (++queued >= BUILD_QUEUE_LOOKAHEAD) break;
        }

        if (!Double.isNaN(ctrl.goalX) && !Double.isNaN(ctrl.goalZ)) {
            addChunk(plan, seen, MathHelper.func_76128_c(ctrl.goalX) >> 4,
                MathHelper.func_76128_c(ctrl.goalZ) >> 4);
        }
        if (plan.size() > MAX_CHUNKS) {
            return new ArrayList<ChunkCoordIntPair>(plan.subList(0, MAX_CHUNKS));
        }
        return plan;
    }

    private static boolean addChunk(List<ChunkCoordIntPair> plan, Set<ChunkCoordIntPair> seen, int cx, int cz) {
        ChunkCoordIntPair pair = new ChunkCoordIntPair(cx, cz);
        if (!seen.add(pair)) return false;
        plan.add(pair);
        return true;
    }

    private static int addLineChunks(List<ChunkCoordIntPair> plan, Set<ChunkCoordIntPair> seen,
                                     double x1, double z1, double x2, double z2, int max) {
        if (max <= 0) return 0;
        double dx = x2 - x1;
        double dz = z2 - z1;
        double dist = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(dist / 4.0));
        int added = 0;
        for (int i = 0; i <= steps && added < max; i++) {
            double t = (double) i / (double) steps;
            int cx = MathHelper.func_76128_c(x1 + dx * t) >> 4;
            int cz = MathHelper.func_76128_c(z1 + dz * t) >> 4;
            if (addChunk(plan, seen, cx, cz)) added++;
        }
        return added;
    }

    public synchronized void releaseDroneTicket(DroneController ctrl) {
        if (ctrl == null) return;
        Ticket ticket = this.droneTickets.remove(ctrl.id);
        release(ticket);
        if (ctrl.entityUuid != null) {
            release(this.restoredEntityTickets.remove(ctrl.entityUuid));
            release(this.anchorTickets.remove("saved_drone:" + ctrl.entityUuid));
            release(this.anchorTickets.remove("drone:" + ctrl.entityUuid));
        }
        ctrl.forcedChunkX = Integer.MIN_VALUE;
        ctrl.forcedChunkZ = Integer.MIN_VALUE;
        ctrl.forcedDim = Integer.MIN_VALUE;
    }

    private void releaseDroneStartupAnchor(DroneController ctrl) {
        if (ctrl.entityUuid == null) return;
        String uuid = ctrl.entityUuid.toString();
        release(this.anchorTickets.remove("saved_drone:" + uuid));
        release(this.anchorTickets.remove("drone:" + uuid));
    }

    // ------------------------------------------------------------------
    // Ticket application
    // ------------------------------------------------------------------

    /**
     * Applies a high-to-low priority plan to a ticket.
     *
     * @return number of chunks explicitly loaded during this call
     */
    private int applyTicketChunks(WorldServer world, Ticket ticket, List<ChunkCoordIntPair> plan,
                                  boolean playerAware, int loadBudget) {
        if (world == null || ticket == null || plan == null || plan.isEmpty()) return 0;
        int depth = ticket.getChunkListDepth();
        if (depth <= 0 || depth > MAX_CHUNKS) depth = MAX_CHUNKS;
        List<ChunkCoordIntPair> desired = plan.size() > depth ? plan.subList(0, depth) : plan;
        Set<ChunkCoordIntPair> desiredSet = new HashSet<ChunkCoordIntPair>(desired);

        Map<ChunkCoordIntPair, Long> pending = this.releaseAt.get(ticket);
        if (pending == null) {
            pending = new java.util.HashMap<ChunkCoordIntPair, Long>();
            this.releaseAt.put(ticket, pending);
        }
        Set<ChunkCoordIntPair> loaded = this.explicitLoads.get(ticket);
        if (loaded == null) {
            loaded = new HashSet<ChunkCoordIntPair>();
            this.explicitLoads.put(ticket, loaded);
        }

        long now = world.func_72820_D();
        Set<ChunkCoordIntPair> current = new HashSet<ChunkCoordIntPair>(ticket.getChunkList());
        List<ChunkCoordIntPair> releaseNow = new ArrayList<ChunkCoordIntPair>();
        for (ChunkCoordIntPair old : current) {
            if (desiredSet.contains(old)) {
                pending.remove(old);
                continue;
            }
            if (playerAware && isPlayerManaged(world, old.field_77276_a, old.field_77275_b)) {
                // Vanilla owns it now; our claim is redundant.
                releaseNow.add(old);
                continue;
            }
            Long due = pending.get(old);
            if (due == null) {
                pending.put(old, Long.valueOf(now + RELEASE_GRACE_TICKS));
            } else if (now >= due.longValue()) {
                releaseNow.add(old);
            }
        }
        pending.keySet().retainAll(current);
        for (ChunkCoordIntPair old : releaseNow) {
            pending.remove(old);
            loaded.remove(old);
            unforce(ticket, old);
        }

        int loads = 0;
        Set<ChunkCoordIntPair> held = new HashSet<ChunkCoordIntPair>(ticket.getChunkList());
        for (int i = desired.size() - 1; i >= 0; i--) {
            ChunkCoordIntPair pair = desired.get(i);
            if (playerAware && isPlayerManaged(world, pair.field_77276_a, pair.field_77275_b)) {
                if (held.remove(pair)) {
                    loaded.remove(pair);
                    unforce(ticket, pair);
                }
                continue;
            }
            if (!held.contains(pair)) {
                while (held.size() >= depth) {
                    ChunkCoordIntPair evict = firstNotIn(ticket.getChunkList(), desiredSet);
                    if (evict == null) break;
                    pending.remove(evict);
                    loaded.remove(evict);
                    unforce(ticket, evict);
                    held.remove(evict);
                }
                if (held.size() >= depth) continue;
                force(ticket, pair);
                held.add(pair);
            }
            reorder(ticket, pair);
            if (loadBudget > 0 && !loaded.contains(pair)) {
                loaded.add(pair);
                world.func_72938_d(pair.field_77276_a, pair.field_77275_b);
                loadBudget--;
                loads++;
            }
        }
        return loads;
    }

    private static int loadHotChunks(WorldServer world, List<ChunkCoordIntPair> plan, int budget) {
        int loaded = 0;
        for (ChunkCoordIntPair pair : plan) {
            if (budget <= 0) break;
            world.func_72938_d(pair.field_77276_a, pair.field_77275_b);
            budget--;
            loaded++;
        }
        return loaded;
    }

    private static ChunkCoordIntPair firstNotIn(Collection<ChunkCoordIntPair> chunks,
                                                Set<ChunkCoordIntPair> keep) {
        for (ChunkCoordIntPair pair : chunks) {
            if (!keep.contains(pair)) return pair;
        }
        return null;
    }

    private static void force(Ticket ticket, ChunkCoordIntPair pair) {
        try {
            ForgeChunkManager.forceChunk(ticket, pair);
        } catch (Throwable ignored) {
        }
    }

    private static void unforce(Ticket ticket, ChunkCoordIntPair pair) {
        try {
            ForgeChunkManager.unforceChunk(ticket, pair);
        } catch (Throwable ignored) {
        }
    }

    private static void reorder(Ticket ticket, ChunkCoordIntPair pair) {
        try {
            ForgeChunkManager.reorderChunk(ticket, pair);
        } catch (Throwable ignored) {
        }
    }

    private void release(Ticket ticket) {
        if (ticket == null) return;
        try {
            ForgeChunkManager.releaseTicket(ticket);
        } catch (Throwable ignored) {
        }
        this.releaseAt.remove(ticket);
        this.explicitLoads.remove(ticket);
        this.droneTickets.values().remove(ticket);
        this.anchorTickets.values().remove(ticket);
        this.restoredEntityTickets.values().remove(ticket);
    }

    // ------------------------------------------------------------------
    // Player ownership
    // ------------------------------------------------------------------

    /**
     * True when a player's vanilla chunk view already keeps this chunk loaded.
     * Uses the exact view distance used by {@code PlayerManager.updatePlayerPertinentChunks};
     * no margin, so we never hand a chunk back to vanilla one chunk early.
     */
    private static boolean isPlayerManaged(World world, int cx, int cz) {
        if (!(world instanceof WorldServer)) return false;
        List<?> players = world.field_73010_i;
        if (players == null || players.isEmpty()) return false;
        int view = viewDistanceChunks();
        for (Object o : players) {
            if (!(o instanceof EntityPlayerMP)) continue;
            EntityPlayerMP p = (EntityPlayerMP) o;
            if (p.field_70170_p != world) continue;
            int pcx = MathHelper.func_76128_c(p.field_70165_t) >> 4;
            int pcz = MathHelper.func_76128_c(p.field_70161_v) >> 4;
            if (Math.abs(pcx - cx) <= view && Math.abs(pcz - cz) <= view) return true;
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

    // ------------------------------------------------------------------
    // Helpers used by navigation
    // ------------------------------------------------------------------

    /**
     * Loads the chunk under a coordinate on demand. This deliberately does not create
     * a rolling ticket: the drone's own ticket already covers the path plan. Loading
     * here makes the active waypoint readable before the drone enters it.
     */
    public static void ensureChunkLoadedStatic(World world, double x, double z) {
        if (!(world instanceof WorldServer)) return;
        try {
            int cx = MathHelper.func_76128_c(x) >> 4;
            int cz = MathHelper.func_76128_c(z) >> 4;
            world.func_72938_d(cx, cz);
        } catch (Throwable ignored) {
        }
    }

    private static List<ChunkCoordIntPair> buildGrid(int cx, int cz, int radius) {
        List<ChunkCoordIntPair> grid = new ArrayList<ChunkCoordIntPair>();
        Set<ChunkCoordIntPair> seen = new HashSet<ChunkCoordIntPair>();
        addChunk(grid, seen, cx, cz);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) continue;
                addChunk(grid, seen, cx + dx, cz + dz);
            }
        }
        return grid;
    }
}
