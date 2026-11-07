package ir.layercloud.cortexdrone.manager.chunk.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.manager.chunk.ChunkLoadPolicy;
import ir.layercloud.cortexdrone.manager.chunk.PlayerChunkOwnership;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.service.world.ChunkAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Navigation chunk manager built on vanilla/Forge chunk ownership.
 *
 * <p>Forge's contract (1.7.10 {@code ForgeChunkManager}) is used exactly as
 * designed: {@code forceChunk} only records a ticket claim, {@code reorderChunk}
 * only changes eviction order, and {@code releaseTicket}/{@code unforceChunk}
 * only drop this mod's own claims. Chunks are generated with
 * {@code World.getChunkFromChunkCoords} and unloaded through vanilla's normal
 * queue ({@code ChunkProviderServer.unloadChunksIfNotNearSpawn}); this class
 * never removes a chunk from {@code loadedChunkHashMap} itself.</p>
 *
 * <p>Policy (see {@link ChunkLoadPolicy} and {@link PlayerChunkOwnership}):</p>
 * <ul>
 *   <li>no AI loading for the first 10s after a player logs in;</li>
 *   <li>chunks inside a player's render/view distance are never loaded or
 *       dropped by the AI;</li>
 *   <li>an AI chunk is released only when the drone is away from it and no
 *       player can see it;</li>
 *   <li>after the last logout, AI chunks are kept for 10s and then released.</li>
 * </ul>
 */
public final class ChunkLoadingManager implements ForgeChunkManager.LoadingCallback, ChunkAccess.Loader {
    /** Keep a chunk this long after it stops being needed (5s at 20 TPS). */
    private static final int RELEASE_GRACE_TICKS = 60;
    /** New chunk loads per 5-tick drone plan update. Prevents flight stutter. */
    private static final int DRONE_LOAD_BUDGET = 4;
    /** Hard cap for synchronous ray/path reads in one server tick. */
    private static final int RAYCAST_LOAD_BUDGET = 4;

    private final AIDroneManager manager;
    private final CortexDroneMod mod;
    private final DroneLogger logger;
    private final ChunkLoadPolicy policy = new ChunkLoadPolicy();

    private final Map<Integer, Ticket> droneTickets = new ConcurrentHashMap<>();
    private final Map<Integer, Cursor> cursors = new ConcurrentHashMap<>();
    private final Map<Ticket, Map<ChunkCoordIntPair, Long>> releaseAt = new ConcurrentHashMap<>();
    /** Chunks this manager explicitly generated/loaded. Safe to queue for unload. */
    private final Map<World, Set<ChunkCoordIntPair>> aiLoaded = new ConcurrentHashMap<>();

    private boolean unloadedAll;
    private int raycastLoads;

    public ChunkLoadingManager(AIDroneManager manager, CortexDroneMod mod, DroneLogger logger) {
        this.manager = manager;
        this.mod = mod;
        this.logger = logger;
        attach();
    }

    /**
     * Re-attaches the policy-gated loading bridge for a server start. The mod
     * instance lives in the JVM across an integrated-server restart, while the
     * bridge is detached on shutdown.
     */
    public synchronized void attach() {
        this.policy.reset();
        this.unloadedAll = false;
        this.releaseAt.clear();
        this.aiLoaded.clear();
        this.cursors.clear();
        ChunkAccess.setLoader(this);
    }

    // ------------------------------------------------------------------
    // Policy / lifecycle
    // ------------------------------------------------------------------

    /** Called once per server tick from AIDroneManager. */
    public synchronized void tickPolicy(boolean anyDroneBusy) {
        this.raycastLoads = 0;
        this.policy.tick(MinecraftServer.getServer());
        if (this.policy.isUnloadAll()) {
            if (!this.unloadedAll) {
                this.unloadedAll = releaseAllAiChunks();
            }
            return;
        }
        this.unloadedAll = false;
        if (this.policy.hasPlayerOnline() && !anyDroneBusy) {
            unloadAiOutsideView();
        }
    }

    public boolean isLoadAllowed() {
        return this.policy.isLoadAllowed();
    }

    /**
     * Saved tickets are deliberately not re-adopted: they would keep AI chunks
     * loaded outside the current player view after a restart. When a player
     * loads the area the drone entity loads through vanilla, and the normal
     * reconnect path creates a fresh ticket.
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
        for (Ticket ticket : new ArrayList<>(this.droneTickets.values())) {
            if (ticket != null && ticket.world == world) release(ticket);
        }
        this.aiLoaded.remove(world);
        this.cursors.clear();
    }

    public synchronized void shutdown() {
        releaseAllAiChunks();
        this.aiLoaded.clear();
        this.releaseAt.clear();
        this.cursors.clear();
        ChunkAccess.setLoader(null);
    }

    /** @return true when every AI-loaded chunk could be dropped safely. */
    private boolean releaseAllAiChunks() {
        for (Ticket ticket : new ArrayList<>(this.droneTickets.values())) {
            release(ticket);
        }
        boolean fullyReleased = true;
        for (Map.Entry<World, Set<ChunkCoordIntPair>> entry : this.aiLoaded.entrySet()) {
            World world = entry.getKey();
            if (!(world instanceof WorldServer)) continue;
            WorldServer server = (WorldServer) world;
            for (ChunkCoordIntPair pair : new ArrayList<>(entry.getValue())) {
                if (PlayerChunkOwnership.isWithinAnyPlayerView(world, pair.chunkXPos, pair.chunkZPos)) {
                    fullyReleased = false;
                    continue;
                }
                if (isAnyDroneNear(world, pair)) {
                    fullyReleased = false;
                    continue;
                }
                dropChunk(server, pair);
                entry.getValue().remove(pair);
            }
        }
        this.releaseAt.clear();
        if (fullyReleased) {
            this.aiLoaded.clear();
            this.cursors.clear();
        }
        return fullyReleased;
    }

    private void unloadAiOutsideView() {
        for (Map.Entry<World, Set<ChunkCoordIntPair>> entry : this.aiLoaded.entrySet()) {
            World world = entry.getKey();
            if (!(world instanceof WorldServer)) continue;
            WorldServer server = (WorldServer) world;
            for (ChunkCoordIntPair pair : new ArrayList<>(entry.getValue())) {
                if (PlayerChunkOwnership.isWithinAnyPlayerView(world, pair.chunkXPos, pair.chunkZPos)) {
                    continue;
                }
                if (isAnyDroneNear(world, pair)) continue;
                dropChunk(server, pair);
                entry.getValue().remove(pair);
            }
        }
        for (Ticket ticket : new ArrayList<>(this.droneTickets.values())) {
            if (ticket == null || !(ticket.world instanceof WorldServer)) continue;
            for (ChunkCoordIntPair pair : new ArrayList<>(ticket.getChunkList())) {
                if (PlayerChunkOwnership.isWithinAnyPlayerView(ticket.world,
                        pair.chunkXPos, pair.chunkZPos)) {
                    continue;
                }
                if (isAnyDroneNear(ticket.world, pair)) continue;
                unforce(ticket, pair);
            }
        }
    }

    // ------------------------------------------------------------------
    // Drone ticket
    // ------------------------------------------------------------------

    public synchronized void updateDroneTicket(DroneController ctrl, NavigationState nav) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.isDead) return;
        World world = ctrl.drone.worldObj;
        if (!(world instanceof WorldServer)) return;
        if (!this.policy.isLoadAllowed()) return;
        if (!forgeLoadingAvailable()) return;

        int dim = world.provider != null ? world.provider.dimensionId : 0;
        int cx = MathHelper.floor_double(ctrl.drone.posX) >> 4;
        int cz = MathHelper.floor_double(ctrl.drone.posZ) >> 4;
        long now = world.getTotalWorldTime();

        Cursor cursor = this.cursors.get(ctrl.id);
        if (cursor == null) {
            cursor = new Cursor();
            this.cursors.put(ctrl.id, cursor);
        }
        boolean moved = cursor.chunkX != cx || cursor.chunkZ != cz || cursor.dim != dim;
        boolean periodic = now - cursor.lastPlanTick >= 5;
        if (!moved && !periodic && this.droneTickets.containsKey(ctrl.id)) return;
        cursor.chunkX = cx;
        cursor.chunkZ = cz;
        cursor.dim = dim;
        cursor.lastPlanTick = now;

        Ticket ticket = adoptOrCreateTicket(ctrl, (WorldServer) world);
        List<ChunkCoordIntPair> plan = NavigationChunkPlanner.buildDronePlan(ctrl, nav, cx, cz);
        if (ticket != null) {
            int loadedNow = applyTicketChunks((WorldServer) world, ctrl, ticket, plan);
            logPlan(ctrl, cursor, ticket, plan, loadedNow, now);
        } else {
            loadHotChunks((WorldServer) world, plan, DRONE_LOAD_BUDGET);
        }
    }

    public synchronized void releaseDroneTicket(DroneController ctrl) {
        if (ctrl == null) return;
        release(this.droneTickets.remove(ctrl.id));
        this.cursors.remove(ctrl.id);
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
        } catch (Throwable failure) {
            if (created != null) release(created);
            this.logger.log("CHUNK", ctrl, "Could not request Forge chunk ticket", String.valueOf(failure));
        }
        return created;
    }

    // ------------------------------------------------------------------
    // Ticket application
    // ------------------------------------------------------------------

    private int applyTicketChunks(WorldServer world, DroneController ctrl, Ticket ticket,
                                  List<ChunkCoordIntPair> plan) {
        if (world == null || ticket == null || plan == null || plan.isEmpty()) return 0;
        configureTicket(ticket);

        int depth = ticket.getChunkListDepth();
        if (depth <= 0) return 0;
        if (depth > NavigationChunkPlanner.MAX_TICKET_CHUNKS) {
            depth = NavigationChunkPlanner.MAX_TICKET_CHUNKS;
        }
        List<ChunkCoordIntPair> desired = plan.size() > depth ? plan.subList(0, depth) : plan;
        Set<ChunkCoordIntPair> desiredSet = new HashSet<>(desired);

        Map<ChunkCoordIntPair, Long> pending = this.releaseAt.get(ticket);
        if (pending == null) {
            pending = new HashMap<>();
            this.releaseAt.put(ticket, pending);
        }
        long now = world.getTotalWorldTime();
        Set<ChunkCoordIntPair> current = new HashSet<>(ticket.getChunkList());

        // 1. Drop claims we no longer need, but only after the drone is away and
        // no player can see the chunk. Player-view chunks keep their claim until
        // the player leaves; vanilla owns them anyway.
        for (ChunkCoordIntPair old : current) {
            if (desiredSet.contains(old)) {
                pending.remove(old);
                continue;
            }
            if (PlayerChunkOwnership.isWithinAnyPlayerView(world, old.chunkXPos, old.chunkZPos)
                    || isAnyDroneNear(world, old)) {
                pending.remove(old);
                continue;
            }
            Long due = pending.get(old);
            if (due == null) {
                pending.put(old, now + RELEASE_GRACE_TICKS);
            } else if (now >= due) {
                pending.remove(old);
                unforce(ticket, old);
            }
        }

        // 2. Claim the desired set, highest priority first. Player-view chunks
        // are left entirely to vanilla's PlayerManager.
        Set<ChunkCoordIntPair> held = new HashSet<>(ticket.getChunkList());
        for (ChunkCoordIntPair pair : desired) {
            if (PlayerChunkOwnership.isWithinAnyPlayerView(world, pair.chunkXPos, pair.chunkZPos)) {
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

        // 3. Highest-priority chunk at the tail, lowest at the head.
        for (int i = desired.size() - 1; i >= 0; i--) {
            ChunkCoordIntPair pair = desired.get(i);
            if (held.contains(pair)) reorder(ticket, pair);
        }

        // 4. Forge claims do not load anything. Load missing chunks nearest-first
        // with a per-update budget so flight never stalls on generation.
        int loads = 0;
        for (ChunkCoordIntPair pair : desired) {
            if (loads >= DRONE_LOAD_BUDGET) break;
            if (!held.contains(pair)) continue;
            if (PlayerChunkOwnership.isWithinAnyPlayerView(world, pair.chunkXPos, pair.chunkZPos)) {
                continue;
            }
            if (ChunkAccess.isLoaded(world, pair.chunkXPos, pair.chunkZPos)) continue;
            loadChunk(world, pair);
            loads++;
        }
        return loads;
    }

    private int loadHotChunks(WorldServer world, List<ChunkCoordIntPair> plan, int budget) {
        int loaded = 0;
        for (ChunkCoordIntPair pair : plan) {
            if (loaded >= budget) break;
            if (PlayerChunkOwnership.isWithinAnyPlayerView(world, pair.chunkXPos, pair.chunkZPos)) {
                continue;
            }
            if (ChunkAccess.isLoaded(world, pair.chunkXPos, pair.chunkZPos)) continue;
            loadChunk(world, pair);
            loaded++;
        }
        return loaded;
    }

    private void logPlan(DroneController ctrl, Cursor cursor, Ticket ticket,
                         List<ChunkCoordIntPair> plan, int loadedNow, long now) {
        int managed = 0;
        int playerOwned = 0;
        for (ChunkCoordIntPair pair : plan) {
            if (PlayerChunkOwnership.isWithinAnyPlayerView(ticket.world,
                    pair.chunkXPos, pair.chunkZPos)) {
                playerOwned++;
            } else {
                managed++;
            }
        }
        boolean changed = managed != cursor.lastManaged || playerOwned != cursor.lastPlayerOwned;
        if (loadedNow == 0 && !changed && now - cursor.lastLogTick < 600) return;
        cursor.lastLogTick = now;
        cursor.lastManaged = managed;
        cursor.lastPlayerOwned = playerOwned;
        this.logger.log("CHUNK", ctrl, "Navigation chunk ownership updated",
                "managed=" + managed + " playerOwned=" + playerOwned
                        + " plan=" + plan.size() + " held=" + ticket.getChunkList().size()
                        + " loadedNow=" + loadedNow
                        + " policy=" + (this.policy.isLoadAllowed() ? "open" : "closed"));
    }

    // ------------------------------------------------------------------
    // ChunkAccess.Loader (pathfinding / raycast entry point)
    // ------------------------------------------------------------------

    @Override
    public synchronized boolean ensureLoaded(World world, int chunkX, int chunkZ) {
        if (!(world instanceof WorldServer)) return false;
        if (!this.policy.isLoadAllowed() || !forgeLoadingAvailable()) return false;
        // Vanilla's PlayerManager owns the player view square. If one of those
        // chunks is not loaded yet, wait for vanilla instead of racing it.
        if (PlayerChunkOwnership.isWithinAnyPlayerView(world, chunkX, chunkZ)) return false;
        WorldServer server = (WorldServer) world;
        if (ChunkAccess.isLoaded(server, chunkX, chunkZ)) return true;
        // A single long fat-ray can touch many chunks. Generate at most a few
        // per tick; the rest stay opaque and pathfinding retries next tick.
        if (this.raycastLoads >= RAYCAST_LOAD_BUDGET) return false;
        this.raycastLoads++;
        loadChunk(server, new ChunkCoordIntPair(chunkX, chunkZ));
        return ChunkAccess.isLoaded(server, chunkX, chunkZ);
    }

    private void loadChunk(WorldServer world, ChunkCoordIntPair pair) {
        try {
            boolean wasLoaded = ChunkAccess.isLoaded(world, pair.chunkXPos, pair.chunkZPos);
            world.getChunkFromChunkCoords(pair.chunkXPos, pair.chunkZPos);
            if (!wasLoaded && ChunkAccess.isLoaded(world, pair.chunkXPos, pair.chunkZPos)) {
                aiLoadedSet(world).add(pair);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void dropChunk(WorldServer world, ChunkCoordIntPair pair) {
        try {
            world.theChunkProviderServer.unloadChunksIfNotNearSpawn(pair.chunkXPos, pair.chunkZPos);
        } catch (Throwable ignored) {
        }
    }

    private Set<ChunkCoordIntPair> aiLoadedSet(World world) {
        Set<ChunkCoordIntPair> set = this.aiLoaded.get(world);
        if (set == null) {
            set = new HashSet<>();
            this.aiLoaded.put(world, set);
        }
        return set;
    }

    private boolean isAnyDroneNear(World world, ChunkCoordIntPair pair) {
        for (DroneController ctrl : this.manager.getControllersById().values()) {
            if (ctrl == null || ctrl.drone == null || ctrl.drone.isDead) continue;
            if (ctrl.drone.worldObj != world) continue;
            if (PlayerChunkOwnership.isDroneNear(ctrl.drone, pair.chunkXPos, pair.chunkZPos,
                    NavigationChunkPlanner.DRONE_KEEP_RADIUS)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Ticket primitives
    // ------------------------------------------------------------------

    /** Respect Forge's per-mod chunkloading config (a zero disables it). */
    private static boolean forgeLoadingAvailable() {
        try {
            return ForgeChunkManager.getMaxChunkDepthFor(CortexDroneMod.MODID) > 0
                    && ForgeChunkManager.getMaxTicketLengthFor(CortexDroneMod.MODID) > 0;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static ChunkCoordIntPair firstNotIn(Collection<ChunkCoordIntPair> chunks,
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
            if (max <= 0) return;
            int depth = Math.min(NavigationChunkPlanner.MAX_TICKET_CHUNKS, max);
            if (ticket.getChunkListDepth() != depth) ticket.setChunkListDepth(depth);
        } catch (Throwable ignored) {
        }
    }

    private static boolean force(Ticket ticket, ChunkCoordIntPair pair) {
        try {
            ForgeChunkManager.forceChunk(ticket, pair);
            return ticket.getChunkList().contains(pair);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void unforce(Ticket ticket, ChunkCoordIntPair pair) {
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

    private static final class Cursor {
        private int chunkX = Integer.MIN_VALUE;
        private int chunkZ = Integer.MIN_VALUE;
        private int dim = Integer.MIN_VALUE;
        private long lastPlanTick = Long.MIN_VALUE;
        private long lastLogTick = Long.MIN_VALUE;
        private int lastManaged = -1;
        private int lastPlayerOwned = -1;
    }
}
