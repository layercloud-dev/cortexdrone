package ir.layercloud.cortexdrone.navigation;

import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.harness.PlayerChunkOwnership;
import ir.layercloud.cortexdrone.harness.PlayerChunkWatchRegistry;
import ir.layercloud.cortexdrone.model.DroneController;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import static ir.layercloud.cortexdrone.harness.PlayerChunkOwnership.isPlayerManaged;

/**
 * Player-driven AI chunk manager.
 *
 * <p>Policy:
 * <ul>
 *   <li>AI loading is closed while nobody is online and for the first 10s
 *       after a player logs in;</li>
 *   <li>while loading is open, the AI may load and force chunks only inside a
 *       player's server view distance;</li>
 *   <li>AI-loaded chunks outside every player's view distance are dropped;</li>
 *   <li>after the last player logs out, AI chunks are kept for 10s, then all
 *       AI tickets and AI-loaded chunks are released.</li>
 * </ul>
 *
 * <p>Chunks loaded by players (or by another mod) are never dropped here. The
 * AI only drops chunks it explicitly loaded and only when no player can see
 * them.
 */
public class DroneChunkLoader implements ForgeChunkManager.LoadingCallback {
    private static final int RELEASE_GRACE_TICKS = 60;
    private static final int DRONE_LOAD_BUDGET = 4;

    private static volatile DroneChunkLoader instance;

    private final CortexDroneMod mod;
    private final DroneLogger logger;
    private final ChunkLoadPolicy policy = new ChunkLoadPolicy();
    private final Map<Integer, Ticket> droneTickets = new ConcurrentHashMap<Integer, Ticket>();
    private final Map<Ticket, Map<ChunkCoordIntPair, Long>> releaseAt =
        new ConcurrentHashMap<Ticket, Map<ChunkCoordIntPair, Long>>();
    /** Chunks this manager explicitly loaded (safe to drop when out of view). */
    private final Map<World, Set<ChunkCoordIntPair>> aiLoaded =
        new ConcurrentHashMap<World, Set<ChunkCoordIntPair>>();
    private boolean unloadedAll;

    public DroneChunkLoader(CortexDroneMod mod, DroneLogger logger) {
        this.mod = mod;
        this.logger = logger;
        instance = this;
    }

    public static DroneChunkLoader getInstance() {
        return instance;
    }

    // ------------------------------------------------------------------
    // Policy / lifecycle
    // ------------------------------------------------------------------

    /** Called once per server tick from AIDroneManager. */
    public synchronized void tickPolicy() {
        this.policy.tick(MinecraftServer.func_71276_C());
        if (this.policy.isUnloadAll()) {
            if (!this.unloadedAll) {
                releaseAllAiChunks();
                this.unloadedAll = true;
            }
        } else {
            this.unloadedAll = false;
            // Unloading AI chunks outside view is not delayed by the login
            // grace; only new loading waits for the 10s gate.
            if (this.policy.hasPlayerOnline()) unloadAiOutsideView();
        }
    }

    public boolean isLoadAllowed() {
        return this.policy.isLoadAllowed();
    }

    /** Gate + player-view check used by movement/action ticking. */
    public boolean isAiAllowedAt(World world, double x, double z) {
        if (!this.policy.isLoadAllowed() || !(world instanceof WorldServer)) return false;
        int cx = MathHelper.func_76128_c(x) >> 4;
        int cz = MathHelper.func_76128_c(z) >> 4;
        return PlayerChunkOwnership.isWithinAnyPlayerView(world, cx, cz);
    }

    /**
     * Saved tickets are deliberately not re-adopted: they would keep AI chunks
     * loaded outside the current player's view after a restart. When a player
     * loads the area, the drone entity loads through vanilla and the normal
     * reconnect path recreates the controller and its ticket.
     */
    @Override
    public void ticketsLoaded(List<Ticket> tickets, World world) {
        if (tickets == null) return;
        for (Ticket ticket : tickets) {
            release(ticket);
        }
    }

    /** Releases all AI tickets/chunks for a world that is being unloaded. */
    public synchronized void releaseWorld(World world) {
        if (world == null) return;
        List<Ticket> tickets = new ArrayList<Ticket>(this.droneTickets.values());
        for (Ticket ticket : tickets) {
            if (ticket != null && ticket.world == world) release(ticket);
        }
        this.aiLoaded.remove(world);
    }

    private void releaseAllAiChunks() {
        List<Ticket> tickets = new ArrayList<Ticket>(this.droneTickets.values());
        for (Ticket ticket : tickets) {
            release(ticket);
        }
        for (Map.Entry<World, Set<ChunkCoordIntPair>> e : this.aiLoaded.entrySet()) {
            World world = e.getKey();
            if (!(world instanceof WorldServer)) continue;
            for (ChunkCoordIntPair pair : new ArrayList<ChunkCoordIntPair>(e.getValue())) {
                if (!PlayerChunkWatchRegistry.isWatched(world, pair.field_77276_a, pair.field_77275_b)) {
                    dropChunk((WorldServer) world, pair);
                }
            }
        }
        this.aiLoaded.clear();
        this.releaseAt.clear();
    }

    private void unloadAiOutsideView() {
        for (Map.Entry<World, Set<ChunkCoordIntPair>> e : this.aiLoaded.entrySet()) {
            World world = e.getKey();
            if (!(world instanceof WorldServer)) continue;
            WorldServer ws = (WorldServer) world;
            for (ChunkCoordIntPair pair : new ArrayList<ChunkCoordIntPair>(e.getValue())) {
                if (PlayerChunkOwnership.isWithinAnyPlayerView(world, pair.field_77276_a, pair.field_77275_b)) {
                    continue;
                }
                if (PlayerChunkWatchRegistry.isWatched(world, pair.field_77276_a, pair.field_77275_b)) {
                    continue; // still player-kept at the border; never force it out
                }
                dropChunk(ws, pair);
                e.getValue().remove(pair);
            }
        }
        for (Ticket ticket : new ArrayList<Ticket>(this.droneTickets.values())) {
            if (ticket == null || !(ticket.world instanceof WorldServer)) continue;
            for (ChunkCoordIntPair pair : new ArrayList<ChunkCoordIntPair>(ticket.getChunkList())) {
                if (!PlayerChunkOwnership.isWithinAnyPlayerView(ticket.world,
                    pair.field_77276_a, pair.field_77275_b)) {
                    unforce(ticket, pair);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Drone ticket
    // ------------------------------------------------------------------

    public synchronized void updateDroneTicket(DroneController ctrl) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70128_L) return;
        World w = ctrl.drone.field_70170_p;
        if (!(w instanceof WorldServer)) return;
        if (!this.policy.isLoadAllowed()) return;
        WorldServer world = (WorldServer) w;
        int dim = world.field_73011_w != null ? world.field_73011_w.field_76574_g : 0;
        int cx = MathHelper.func_76128_c(ctrl.drone.field_70165_t) >> 4;
        int cz = MathHelper.func_76128_c(ctrl.drone.field_70161_v) >> 4;

        if (!PlayerChunkOwnership.isWithinAnyPlayerView(world, cx, cz)) {
            releaseDroneTicket(ctrl);
            return;
        }

        boolean movedChunk = ctrl.forcedChunkX != cx || ctrl.forcedChunkZ != cz || ctrl.forcedDim != dim;
        boolean periodic = ctrl.tickClock - ctrl.lastChunkPlanTick >= 5;
        if (!movedChunk && !periodic && this.droneTickets.containsKey(ctrl.id)) return;
        ctrl.lastChunkPlanTick = ctrl.tickClock;

        Ticket ticket = adoptOrCreateTicket(ctrl, world);
        List<ChunkCoordIntPair> plan = DroneChunkPlanner.buildDronePlan(ctrl, cx, cz);
        int playerOwned = 0;
        for (ChunkCoordIntPair pair : plan) {
            if (isPlayerManaged(world, pair.field_77276_a, pair.field_77275_b)) playerOwned++;
        }
        int managed = plan.size() - playerOwned;

        if (ticket != null) {
            int loadedNow = applyTicketChunks(world, ticket, plan, DRONE_LOAD_BUDGET);
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
                            + " policy=" + (this.policy.isLoadAllowed() ? "open" : "closed"));
                }
            }
        } else {
            loadHotChunks(world, plan, DRONE_LOAD_BUDGET);
        }
        ctrl.forcedChunkX = cx;
        ctrl.forcedChunkZ = cz;
        ctrl.forcedDim = dim;
    }

    /**
     * Guarantees the drone's own chunk is claimed and loaded while the player
     * policy is open. Forge only ticks an entity in a non-forced chunk if all
     * chunks in a +/-32-block box are loaded; the current-chunk claim is what
     * keeps a drone alive at the edge of a player's view.
     */
    public synchronized void ensureDroneChunk(DroneController ctrl) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70128_L) return;
        World w = ctrl.drone.field_70170_p;
        if (!(w instanceof WorldServer) || !this.policy.isLoadAllowed()) return;
        WorldServer world = (WorldServer) w;
        int cx = MathHelper.func_76128_c(ctrl.drone.field_70165_t) >> 4;
        int cz = MathHelper.func_76128_c(ctrl.drone.field_70161_v) >> 4;
        if (!PlayerChunkOwnership.isWithinAnyPlayerView(world, cx, cz)) return;
        Ticket ticket = this.droneTickets.get(ctrl.id);
        if (ticket == null || ticket.world != world) return;
        ChunkCoordIntPair pair = new ChunkCoordIntPair(cx, cz);
        if (!force(ticket, pair)) {
            release(ticket);
            ctrl.lastChunkPlanTick = -1000;
            return;
        }
        reorder(ticket, pair);
        if (!world.field_73059_b.func_73149_a(cx, cz)) {
            loadChunk(world, pair);
        }
    }

    private Ticket adoptOrCreateTicket(DroneController ctrl, WorldServer world) {
        Ticket ticket = this.droneTickets.get(ctrl.id);
        if (ticket != null && ticket.world == world) return ticket;
        if (ticket != null) release(ticket);
        Ticket created = null;
        try {
            created = ForgeChunkManager.requestTicket(this.mod, world, ForgeChunkManager.Type.ENTITY);
            if (created != null) {
                created.bindEntity(ctrl.drone);
                configureTicket(created);
                this.droneTickets.put(ctrl.id, created);
            }
        } catch (Throwable t) {
            if (created != null) release(created);
        }
        return created;
    }

    public synchronized void releaseDroneTicket(DroneController ctrl) {
        if (ctrl == null) return;
        release(this.droneTickets.remove(ctrl.id));
        ctrl.forcedChunkX = Integer.MIN_VALUE;
        ctrl.forcedChunkZ = Integer.MIN_VALUE;
        ctrl.forcedDim = Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------
    // Ticket application
    // ------------------------------------------------------------------

    private int applyTicketChunks(WorldServer world, Ticket ticket, List<ChunkCoordIntPair> plan,
                                  int loadBudget) {
        if (world == null || ticket == null || plan == null || plan.isEmpty()) return 0;
        if (!this.policy.isLoadAllowed()) return 0;
        configureTicket(ticket);
        int depth = ticket.getChunkListDepth();
        if (depth <= 0 || depth > DroneChunkPlanner.MAX_TICKET_CHUNKS) {
            depth = DroneChunkPlanner.MAX_TICKET_CHUNKS;
        }
        List<ChunkCoordIntPair> desired = new ArrayList<ChunkCoordIntPair>();
        for (int i = 0; i < plan.size() && desired.size() < depth; i++) {
            ChunkCoordIntPair pair = plan.get(i);
            if (PlayerChunkOwnership.isWithinAnyPlayerView(world,
                pair.field_77276_a, pair.field_77275_b)) {
                desired.add(pair);
            }
        }
        if (desired.isEmpty()) {
            releaseClaimsOutsideView(world, ticket);
            return 0;
        }
        Set<ChunkCoordIntPair> desiredSet = new HashSet<ChunkCoordIntPair>(desired);
        ChunkCoordIntPair center = desired.get(0);

        Map<ChunkCoordIntPair, Long> pending = this.releaseAt.get(ticket);
        if (pending == null) {
            pending = new java.util.HashMap<ChunkCoordIntPair, Long>();
            this.releaseAt.put(ticket, pending);
        }
        long now = world.func_82737_E();
        Set<ChunkCoordIntPair> current = new HashSet<ChunkCoordIntPair>(ticket.getChunkList());

        for (ChunkCoordIntPair old : current) {
            if (!PlayerChunkOwnership.isWithinAnyPlayerView(world,
                old.field_77276_a, old.field_77275_b)) {
                pending.remove(old);
                unforce(ticket, old);
                unloadIfAiLoaded(world, old);
                continue;
            }
            if (desiredSet.contains(old)) {
                pending.remove(old);
                continue;
            }
            Long due = pending.get(old);
            if (due == null) {
                pending.put(old, Long.valueOf(now + RELEASE_GRACE_TICKS));
            } else if (now >= due.longValue()) {
                pending.remove(old);
                unforce(ticket, old);
            }
        }

        Set<ChunkCoordIntPair> held = new HashSet<ChunkCoordIntPair>(ticket.getChunkList());
        for (int di = 0; di < desired.size(); di++) {
            ChunkCoordIntPair pair = desired.get(di);
            boolean core = Math.abs(pair.field_77276_a - center.field_77276_a)
                <= DroneChunkPlanner.DRONE_CHUNK_RADIUS
                && Math.abs(pair.field_77275_b - center.field_77275_b)
                <= DroneChunkPlanner.DRONE_CHUNK_RADIUS;
            if (!core && isPlayerManaged(world, pair.field_77276_a, pair.field_77275_b)) {
                continue;
            }
            if (held.contains(pair)) continue;
            while (held.size() >= depth) {
                ChunkCoordIntPair evict = firstNotIn(ticket.getChunkList(), desiredSet);
                if (evict == null) break;
                pending.remove(evict);
                unforce(ticket, evict);
                held.remove(evict);
            }
            if (held.size() >= depth) break;
            if (force(ticket, pair)) held.add(pair);
        }
        pending.keySet().retainAll(held);

        for (int i = desired.size() - 1; i >= 0; i--) {
            ChunkCoordIntPair pair = desired.get(i);
            if (held.contains(pair)) reorder(ticket, pair);
        }

        int loads = 0;
        for (ChunkCoordIntPair pair : desired) {
            if (loadBudget <= 0) break;
            if (!held.contains(pair)) continue;
            boolean core = Math.abs(pair.field_77276_a - center.field_77276_a)
                <= DroneChunkPlanner.DRONE_CHUNK_RADIUS
                && Math.abs(pair.field_77275_b - center.field_77275_b)
                <= DroneChunkPlanner.DRONE_CHUNK_RADIUS;
            if (!core && isPlayerManaged(world, pair.field_77276_a, pair.field_77275_b)) {
                continue;
            }
            if (world.field_73059_b.func_73149_a(pair.field_77276_a, pair.field_77275_b)) {
                continue;
            }
            loadChunk(world, pair);
            loadBudget--;
            loads++;
        }
        return loads;
    }

    private void releaseClaimsOutsideView(WorldServer world, Ticket ticket) {
        for (ChunkCoordIntPair pair : new ArrayList<ChunkCoordIntPair>(ticket.getChunkList())) {
            if (!PlayerChunkOwnership.isWithinAnyPlayerView(world,
                pair.field_77276_a, pair.field_77275_b)) {
                unforce(ticket, pair);
                unloadIfAiLoaded(world, pair);
            }
        }
    }

    private int loadHotChunks(WorldServer world, List<ChunkCoordIntPair> plan, int budget) {
        int loaded = 0;
        for (ChunkCoordIntPair pair : plan) {
            if (budget <= 0) break;
            if (!PlayerChunkOwnership.isWithinAnyPlayerView(world,
                pair.field_77276_a, pair.field_77275_b)) {
                continue;
            }
            if (isPlayerManaged(world, pair.field_77276_a, pair.field_77275_b)) continue;
            if (world.field_73059_b.func_73149_a(pair.field_77276_a, pair.field_77275_b)) continue;
            loadChunk(world, pair);
            budget--;
            loaded++;
        }
        return loaded;
    }

    // ------------------------------------------------------------------
    // AI-loaded chunk tracking
    // ------------------------------------------------------------------

    private void loadChunk(WorldServer world, ChunkCoordIntPair pair) {
        try {
            boolean wasLoaded = world.field_73059_b.func_73149_a(
                pair.field_77276_a, pair.field_77275_b);
            world.func_72964_e(pair.field_77276_a, pair.field_77275_b);
            if (!wasLoaded) aiLoadedSet(world).add(pair);
        } catch (Throwable ignored) {
        }
    }

    private void unloadIfAiLoaded(WorldServer world, ChunkCoordIntPair pair) {
        Set<ChunkCoordIntPair> loaded = this.aiLoaded.get(world);
        if (loaded == null || !loaded.remove(pair)) return;
        if (!PlayerChunkWatchRegistry.isWatched(world,
            pair.field_77276_a, pair.field_77275_b)) {
            dropChunk(world, pair);
        }
    }

    private static void dropChunk(WorldServer world, ChunkCoordIntPair pair) {
        try {
            world.field_73059_b.func_73241_b(pair.field_77276_a, pair.field_77275_b);
        } catch (Throwable ignored) {
        }
    }

    private Set<ChunkCoordIntPair> aiLoadedSet(World world) {
        Set<ChunkCoordIntPair> set = this.aiLoaded.get(world);
        if (set == null) {
            set = new HashSet<ChunkCoordIntPair>();
            this.aiLoaded.put(world, set);
        }
        return set;
    }

    // ------------------------------------------------------------------
    // Static helpers used by the world scanner / pathfinder
    // ------------------------------------------------------------------

    /**
     * Loads a chunk on demand only when the player policy is open and the
     * chunk is inside a player's view. Outside that window the AI must not
     * pull new chunks in.
     */
    public static void ensureChunkLoadedStatic(World world, double x, double z) {
        int cx = MathHelper.func_76128_c(x) >> 4;
        int cz = MathHelper.func_76128_c(z) >> 4;
        requestChunk(world, cx, cz);
    }

    /** Requests a chunk only inside the open player-view window. */
    public static boolean requestChunk(World world, int cx, int cz) {
        DroneChunkLoader loader = instance;
        return loader != null && loader.requestChunkInternal(world, cx, cz);
    }

    private synchronized boolean requestChunkInternal(World world, int cx, int cz) {
        if (!(world instanceof WorldServer) || !this.policy.isLoadAllowed()) return false;
        if (!PlayerChunkOwnership.isWithinAnyPlayerView(world, cx, cz)) return false;
        WorldServer ws = (WorldServer) world;
        if (ws.field_73059_b.func_73149_a(cx, cz)) return true;
        loadChunk(ws, new ChunkCoordIntPair(cx, cz));
        return ws.field_73059_b.func_73149_a(cx, cz);
    }

    /**
     * True when a pathfinder may read this chunk: already loaded, or inside the
     * open player-view loading window.
     */
    public static boolean canUseChunk(World world, int cx, int cz) {
        if (!(world instanceof WorldServer)) return false;
        WorldServer ws = (WorldServer) world;
        if (ws.field_73059_b.func_73149_a(cx, cz)) return true;
        DroneChunkLoader loader = instance;
        return loader != null && loader.policy.isLoadAllowed()
            && PlayerChunkOwnership.isWithinAnyPlayerView(world, cx, cz);
    }

    // ------------------------------------------------------------------
    // Ticket primitives
    // ------------------------------------------------------------------

    private static ChunkCoordIntPair firstNotIn(java.util.Collection<ChunkCoordIntPair> chunks,
                                                Set<ChunkCoordIntPair> keep) {
        for (ChunkCoordIntPair pair : chunks) {
            if (!keep.contains(pair)) return pair;
        }
        return null;
    }

    private static void configureTicket(Ticket ticket) {
        if (ticket == null) return;
        try {
            int max = ticket.getMaxChunkListDepth();
            if (max > 0) {
                int depth = Math.min(DroneChunkPlanner.MAX_TICKET_CHUNKS, max);
                if (ticket.getChunkListDepth() != depth) ticket.setChunkListDepth(depth);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean force(Ticket ticket, ChunkCoordIntPair pair) {
        try {
            ForgeChunkManager.forceChunk(ticket, pair);
            return ticket.getChunkList().contains(pair);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void unforce(Ticket ticket, ChunkCoordIntPair pair) {
        if (ticket == null || pair == null) return;
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
        this.releaseAt.remove(ticket);
        this.droneTickets.values().remove(ticket);
        try {
            ForgeChunkManager.releaseTicket(ticket);
        } catch (Throwable ignored) {
        }
    }
}
