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
    /** New chunk loads per 5-tick drone plan update. Prevents flight stutter. */
    private static final int DRONE_LOAD_BUDGET = 3;
    /** Hard cap for synchronous ray/path reads in one server tick. */
    private static final int RAYCAST_LOAD_BUDGET = 2;

    private final AIDroneManager manager;
    private final CortexDroneMod mod;
    private final DroneLogger logger;
    private final ChunkLoadPolicy policy = new ChunkLoadPolicy();
    private final ForgeTicketOps ticketOps;
    private final TicketPlanApplier planApplier;
    private final AiChunkRelease release;
    private final ChunkPlanLog planLog;
    private final Set<String> reportedTicketFailures = new HashSet<>();

    private final Map<Integer, Ticket> droneTickets = new ConcurrentHashMap<>();
    private final Map<Integer, DroneChunkCursor> cursors = new ConcurrentHashMap<>();
    private final Map<Ticket, Map<ChunkCoordIntPair, Long>> releaseAt = new ConcurrentHashMap<>();
    /** Chunks this manager explicitly generated/loaded. Safe to queue for unload. */
    private final Map<World, Set<ChunkCoordIntPair>> aiLoaded = new ConcurrentHashMap<>();

    private boolean unloadedAll;
    private int raycastLoads;

    public ChunkLoadingManager(AIDroneManager manager, CortexDroneMod mod, DroneLogger logger) {
        this.manager = manager;
        this.mod = mod;
        this.logger = logger;
        this.ticketOps = new ForgeTicketOps(this::reportTicketFailure);
        this.planLog = new ChunkPlanLog(logger);
        this.release = new AiChunkRelease(new AiChunkRelease.Host() {
            @Override
            public Map<World, Set<ChunkCoordIntPair>> aiLoaded() {
                return ChunkLoadingManager.this.aiLoaded;
            }

            @Override
            public boolean isAnyDroneNear(World world, ChunkCoordIntPair pair) {
                return ChunkLoadingManager.this.isAnyDroneNear(world, pair);
            }

            @Override
            public void dropChunk(WorldServer world, ChunkCoordIntPair pair) {
                ChunkLoadingManager.dropChunk(world, pair);
            }
        });
        this.planApplier = new TicketPlanApplier(new TicketPlanApplier.Host() {
            @Override
            public ForgeTicketOps ticketOps() {
                return ChunkLoadingManager.this.ticketOps;
            }

            @Override
            public boolean isAnyDroneNear(net.minecraft.world.World world, ChunkCoordIntPair pair) {
                return ChunkLoadingManager.this.isAnyDroneNear(world, pair);
            }

            @Override
            public void loadChunk(WorldServer world, ChunkCoordIntPair pair) {
                ChunkLoadingManager.this.loadChunk(world, pair);
            }

            @Override
            public Map<ForgeChunkManager.Ticket, Map<ChunkCoordIntPair, Long>> releaseAt() {
                return ChunkLoadingManager.this.releaseAt;
            }
        });
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

    /** Drops every AI chunk that is genuinely unowned. */
    private boolean releaseAllAiChunks() {
        boolean fullyReleased = releaseAiChunks(true);
        this.releaseAt.clear();
        return fullyReleased;
    }

    /** @see AiChunkRelease#releaseAll() */
    private boolean releaseAiChunks(boolean dropTickets) {
        if (dropTickets) {
            for (Ticket ticket : new ArrayList<>(this.droneTickets.values())) {
                release(ticket);
            }
        }
        boolean fullyReleased = this.release.releaseAll();
        if (fullyReleased) this.cursors.clear();
        return fullyReleased;
    }

    /** @see AiChunkRelease#releaseOutsideView() */
    private void unloadAiOutsideView() {
        this.release.releaseOutsideView();
        for (Ticket ticket : new ArrayList<>(this.droneTickets.values())) {
            if (ticket == null || !(ticket.world instanceof WorldServer)) continue;
            for (ChunkCoordIntPair pair : new ArrayList<>(ticket.getChunkList())) {
                if (this.release.isOwned(ticket.world, pair)) continue;
                this.ticketOps.unforce(ticket, pair);
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
        if (!ForgeTicketOps.isAvailable()) return;

        int dim = world.provider != null ? world.provider.dimensionId : 0;
        int cx = MathHelper.floor_double(ctrl.drone.posX) >> 4;
        int cz = MathHelper.floor_double(ctrl.drone.posZ) >> 4;
        long now = world.getTotalWorldTime();

        DroneChunkCursor cursor = this.cursors.computeIfAbsent(ctrl.id, id -> new DroneChunkCursor());
        if (!cursor.shouldReplan(cx, cz, dim, now, this.droneTickets.containsKey(ctrl.id))) return;
        cursor.recordPlan(cx, cz, dim, now);

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

        Ticket created = this.ticketOps.requestEntityTicket(this.mod, world);
        if (created == null) return null;
        try {
            created.bindEntity(ctrl.drone);
        } catch (Throwable failure) {
            this.ticketOps.report("bind", failure);
            this.ticketOps.release(created);
            return null;
        }
        this.droneTickets.put(ctrl.id, created);
        return created;
    }

    // ------------------------------------------------------------------
    // Ticket application
    // ------------------------------------------------------------------

    private int applyTicketChunks(WorldServer world, DroneController ctrl, Ticket ticket,
                                  List<ChunkCoordIntPair> plan) {
        return this.planApplier.apply(world, ctrl, ticket, plan);
    }

    private int loadHotChunks(WorldServer world, List<ChunkCoordIntPair> plan, int budget) {
        return this.planApplier.loadHotChunks(world, plan, budget);
    }

    private void logPlan(DroneController ctrl, DroneChunkCursor cursor, Ticket ticket,
                         List<ChunkCoordIntPair> plan, int loadedNow, long now) {
        this.planLog.log(ctrl, cursor, ticket, plan, loadedNow, now, this.policy.isLoadAllowed());
    }

    // ------------------------------------------------------------------
    // ChunkAccess.Loader (pathfinding / raycast entry point)
    // ------------------------------------------------------------------

    @Override
    public synchronized boolean ensureLoaded(World world, int chunkX, int chunkZ) {
        if (!(world instanceof WorldServer)) return false;
        if (!this.policy.isLoadAllowed() || !ForgeTicketOps.isAvailable()) return false;
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
        for (DroneController ctrl : this.manager.controllers()) {
            if (ctrl == null || ctrl.drone == null || ctrl.drone.isDead) continue;
            if (ctrl.drone.worldObj != world) continue;
            if (PlayerChunkOwnership.isDroneNear(ctrl.drone, pair.chunkXPos, pair.chunkZPos,
                    NavigationChunkPlanner.DRONE_KEEP_RADIUS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports a swallowed Forge ticket failure once per operation.
     *
     * <p>A chunk-ticket regression used to be invisible: every call site caught
     * {@code Throwable} and continued. One line per distinct operation keeps the
     * log useful without repeating every tick.</p>
     */
    private void reportTicketFailure(String operation, Throwable cause) {
        if (!this.reportedTicketFailures.add(operation)) return;
        this.logger.log("CHUNK", null, "Forge chunk ticket " + operation + " failed: " + cause,
                "further '" + operation + "' failures are suppressed");
    }

    // ------------------------------------------------------------------
    // Ticket primitives
    // ------------------------------------------------------------------

    private void release(Ticket ticket) {
        if (ticket == null) return;
        this.releaseAt.remove(ticket);
        this.droneTickets.values().remove(ticket);
        this.ticketOps.release(ticket);
    }

}
