package ir.layercloud.cortexdrone.navigation;

import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.AIConfig;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import static ir.layercloud.cortexdrone.harness.PlayerChunkOwnership.isPlayerManaged;

/**
 * Forge 1.7.10 chunk manager for drones and persistent anchors.
 *
 * <p>Forge's contract (verified against the 1.7.10 {@code ForgeChunkManager} source):
 * <ul>
 *   <li>{@code forceChunk} only records a ticket claim; it never loads a chunk;</li>
 *   <li>{@code reorderChunk} only changes claim order (the first claim is evicted first);</li>
 *   <li>{@code releaseTicket} only drops this mod's claims. It cannot unload a chunk
 *       that vanilla's {@code PlayerManager} keeps loaded for a player;</li>
 *   <li>{@code unforceChunk} likewise only drops this mod's claim.</li>
 * </ul>
 *
 * <p>Therefore this manager explicitly loads a small, budgeted hot set with
 * {@code World.getChunkFromChunkCoords} (chunk coordinates, not block coordinates),
 * and keeps the ticket's claim set equal to that hot set. Corridor lookahead
 * leaves player-watched chunks to vanilla. The drone's own 3x3 is always
 * claimed and loaded: Forge only ticks entities in forced chunks once they are
 * more than 32 blocks from players, and an extra claim cannot unload a player
 * chunk. Ticket release is never deferred just because a player is near.
 *
 * <p>Priority is nearest-first: the drone's own chunk, then its 3x3, then the
 * next few corridor chunks, then nearby work targets. Loading uses the same
 * order, so a moving drone never spends its load budget on the far end of a path.
 */
public class DroneChunkLoader implements ForgeChunkManager.LoadingCallback {
    private static final int ANCHOR_CHUNK_RADIUS = 1;      // 3x3 around a base/waypoint
    /** Keep a nearby chunk this long after it stops being needed (3s at 20 TPS). */
    private static final int RELEASE_GRACE_TICKS = 60;
    /** Far-behind chunks are released quickly so a moving ticket cannot saturate. */
    private static final int FAR_RELEASE_GRACE_TICKS = 40;
    private static final int NEAR_RETENTION_CHUNKS = 1;
    /** New chunk loads per 5-tick drone plan update. Prevents flight stutter. */
    private static final int DRONE_LOAD_BUDGET = 2;
    /** Crossing a chunk border may need the next corridor chunk immediately. */
    private static final int DRONE_MOVE_LOAD_BUDGET = 4;
    /** New chunk loads per 20-tick anchor refresh, shared by all anchors. */
    private static final int ANCHOR_LOAD_BUDGET = 4;
    /** One centre chunk per anchor is loaded when the anchor is first created. */
    private static final int ANCHOR_CENTER_LOAD_BUDGET = 1;

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
    /** Anchor keys that the current config still wants; null until config is loaded. */
    private volatile Set<String> desiredAnchorKeys;
    private int lastForcedAnchorCount = -1;

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
                // Forge has already loaded the entity's own chunk and bound the
                // entity before this callback. Protect its 3x3, but do not load
                // the other eight chunks here; the drone ticket does that.
                Entity entity = ticket.getEntity();
                if (entity != null) {
                    UUID id = entity.func_110124_au();
                    if (id != null) {
                        configureTicket(ticket);
                        Ticket previous = this.restoredEntityTickets.put(id, ticket);
                        if (previous != null && previous != ticket) release(previous);
                        int ecx = MathHelper.func_76128_c(entity.field_70165_t) >> 4;
                        int ecz = MathHelper.func_76128_c(entity.field_70161_v) >> 4;
                        applyTicketChunks(ws, ticket,
                            DroneChunkPlanner.buildGrid(ecx, ecz, DroneChunkPlanner.DRONE_CHUNK_RADIUS),
                            true, 0);
                        continue;
                    }
                }

                NBTTagCompound data = ticket.getModData();
                String anchorKey = data != null && data.func_74764_b("anchorKey")
                    ? data.func_74779_i("anchorKey") : "";
                boolean hasCoords = data != null && data.func_74764_b("cx") && data.func_74764_b("cz");
                // "drone:" NORMAL tickets are from the old design and must not
                // survive: active drones use Type.ENTITY tickets now.
                if (!anchorKey.isEmpty() && hasCoords && !anchorKey.startsWith("drone:")) {
                    Set<String> desired = this.desiredAnchorKeys;
                    if (desired != null && !desired.contains(anchorKey)) {
                        release(ticket);
                        continue;
                    }
                    configureTicket(ticket);
                    Ticket previous = this.anchorTickets.put(anchorKey, ticket);
                    if (previous != null && previous != ticket) release(previous);
                    int acx = data.func_74762_e("cx");
                    int acz = data.func_74762_e("cz");
                    int aradius = data.func_74764_b("radius")
                        ? Math.min(2, Math.max(0, data.func_74762_e("radius")))
                        : ANCHOR_CHUNK_RADIUS;
                    // Restore the claims immediately; the centre chunk is
                    // loaded by forceSavedStateChunks()/refreshAnchors().
                    applyTicketChunks(ws, ticket, DroneChunkPlanner.buildGrid(acx, acz, aradius),
                        true, 0);
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

    /**
     * Reconciles the live anchor ticket set with the current config. Missing
     * anchors are created, moved anchors are re-pointed, and anchors that were
     * removed from the config (including legacy tickets from older builds) are
     * released instead of staying forced forever.
     */
    public synchronized void forceSavedStateChunks(AIConfig config) {
        if (config == null) return;
        MinecraftServer srv = MinecraftServer.func_71276_C();
        if (srv == null || srv.field_71305_c == null || srv.field_71305_c.length == 0) return;

        WorldServer overworld = srv.func_71218_a(0);
        if (overworld == null) overworld = srv.field_71305_c[0];
        if (overworld == null) return;

        Map<String, AnchorSpec> desired = new LinkedHashMap<String, AnchorSpec>();
        Set<String> desiredKeys = new LinkedHashSet<String>();
        if (config.getSavedDrones() != null) {
            for (AIConfig.SavedDroneInfo sd : config.getSavedDrones()) {
                if (sd == null || sd.getX() == null || sd.getZ() == null) continue;
                String key = "saved_drone:" + (sd.getUuid() != null ? sd.getUuid() : sd.getOwner());
                desiredKeys.add(key);
                WorldServer ws = sd.getDimension() == 0
                    ? overworld : srv.func_71218_a(sd.getDimension());
                if (ws == null) continue; // dimension not loaded yet; keep the key for later load
                desired.put(key, new AnchorSpec(ws, key, sd.getX().doubleValue(),
                    sd.getZ().doubleValue(), ANCHOR_CHUNK_RADIUS));
            }
        }
        if (config.getPlayerBases() != null) {
            for (Map.Entry<String, double[]> e : config.getPlayerBases().entrySet()) {
                double[] coords = e.getValue();
                if (coords != null && coords.length >= 3) {
                    String key = "base:" + e.getKey();
                    desiredKeys.add(key);
                    desired.put(key, new AnchorSpec(overworld, key, coords[0], coords[2],
                        ANCHOR_CHUNK_RADIUS));
                }
            }
        }
        if (config.getSavedLocations() != null) {
            for (Map.Entry<String, AIConfig.SavedLocation> e : config.getSavedLocations().entrySet()) {
                AIConfig.SavedLocation loc = e.getValue();
                if (loc == null) continue;
                String key = "loc:" + e.getKey();
                desiredKeys.add(key);
                desired.put(key, new AnchorSpec(overworld, key, loc.getX(), loc.getZ(),
                    ANCHOR_CHUNK_RADIUS));
            }
        }

        this.desiredAnchorKeys = Collections.unmodifiableSet(desiredKeys);

        List<Ticket> stale = new ArrayList<Ticket>();
        for (Map.Entry<String, Ticket> e : this.anchorTickets.entrySet()) {
            if (!desiredKeys.contains(e.getKey())) stale.add(e.getValue());
        }
        for (Ticket ticket : stale) {
            release(ticket);
        }

        int forced = 0;
        for (AnchorSpec spec : desired.values()) {
            if (forceAnchorArea(spec.world, spec.key, spec.x, spec.z, spec.radius)) {
                forced++;
            }
        }
        if (forced > 0 && forced != this.lastForcedAnchorCount && this.logger != null) {
            this.logger.log("SYSTEM", null,
                "ForgeChunkManager reconciled " + forced + " persistent chunk anchor(s)", "");
        }
        this.lastForcedAnchorCount = forced;
    }

    /**
     * Re-applies the hot set to every anchor. Chunks inside a player's view are
     * vanilla-owned; our claim on them is dropped and re-claimed when the
     * player leaves.
     */
    public synchronized void refreshAnchors() {
        if (this.anchorTickets.isEmpty()) return;
        int budget = ANCHOR_LOAD_BUDGET;
        for (Ticket ticket : new ArrayList<Ticket>(this.anchorTickets.values())) {
            if (budget <= 0) break;
            if (ticket == null || !(ticket.world instanceof WorldServer)) continue;
            try {
                NBTTagCompound data = ticket.getModData();
                if (data == null || !data.func_74764_b("cx") || !data.func_74764_b("cz")) continue;
                int cx = data.func_74762_e("cx");
                int cz = data.func_74762_e("cz");
                int radius = data.func_74764_b("radius")
                    ? Math.min(2, Math.max(0, data.func_74762_e("radius"))) : ANCHOR_CHUNK_RADIUS;
                budget -= applyTicketChunks((WorldServer) ticket.world, ticket,
                    DroneChunkPlanner.buildGrid(cx, cz, radius), true, budget);
                if (budget < 0) budget = 0;
            } catch (Throwable ignored) {
            }
        }
    }

    public synchronized boolean forceAnchorArea(WorldServer world, String anchorKey,
                                                double x, double z, int radius) {
        if (world == null || anchorKey == null || anchorKey.isEmpty()) return false;
        try {
            int cx = MathHelper.func_76128_c(x) >> 4;
            int cz = MathHelper.func_76128_c(z) >> 4;
            Ticket ticket = this.anchorTickets.get(anchorKey);
            if (ticket != null && ticket.world != world) {
                release(ticket);
                ticket = null;
            }
            if (ticket == null) {
                ticket = ForgeChunkManager.requestTicket(this.mod, world, ForgeChunkManager.Type.NORMAL);
                if (ticket == null) return false;
                configureTicket(ticket);
                this.anchorTickets.put(anchorKey, ticket);
            }
            NBTTagCompound data = ticket.getModData();
            if (data != null) {
                data.func_74778_a("anchorKey", anchorKey);
                data.func_74768_a("cx", cx);
                data.func_74768_a("cz", cz);
                data.func_74768_a("radius", radius);
            }
            applyTicketChunks(world, ticket, DroneChunkPlanner.buildGrid(cx, cz, radius), true,
                ANCHOR_CENTER_LOAD_BUDGET);
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
        if (!movedChunk && !periodic) return;
        ctrl.lastChunkPlanTick = ctrl.tickClock;

        Ticket ticket = adoptOrCreateTicket(ctrl, world);
        releaseDroneStartupAnchor(ctrl);

        List<ChunkCoordIntPair> plan = DroneChunkPlanner.buildDronePlan(ctrl, cx, cz);
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
                data.func_74768_a("radius", DroneChunkPlanner.DRONE_CHUNK_RADIUS);
            }
            int loadedNow = applyTicketChunks(world, ticket, plan, true,
                movedChunk ? DRONE_MOVE_LOAD_BUDGET : DRONE_LOAD_BUDGET);
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
                            + Math.min(ctrl.pathWaypoints.size(), ctrl.pathIndex
                                + DroneChunkPlanner.CORRIDOR_LOOKAHEAD_WAYPOINTS));
                }
            }
        } else {
            // No ticket (mod ticket limit): at least keep the drone's hot set loaded.
            loadHotChunks(world, plan, DRONE_LOAD_BUDGET);
        }
        ctrl.forcedChunkX = cx;
        ctrl.forcedChunkZ = cz;
        ctrl.forcedDim = dim;
    }

    /**
     * Guarantees the drone's own chunk is claimed and loaded every tick. The
     * 5-tick plan update is fine for lookahead, but the entity's current chunk
     * must never depend on that cadence, a full ticket, or a delayed plan.
     */
    public synchronized void ensureDroneChunk(DroneController ctrl) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70128_L) return;
        World w = ctrl.drone.field_70170_p;
        if (!(w instanceof WorldServer)) return;
        WorldServer world = (WorldServer) w;
        Ticket ticket = this.droneTickets.get(ctrl.id);
        if (ticket == null || ticket.world != world) return;
        int cx = MathHelper.func_76128_c(ctrl.drone.field_70165_t) >> 4;
        int cz = MathHelper.func_76128_c(ctrl.drone.field_70161_v) >> 4;
        ChunkCoordIntPair pair = new ChunkCoordIntPair(cx, cz);
        if (!force(ticket, pair)) {
            // Invalid/released ticket: recreate on the next update instead of
            // flying with an unclaimed current chunk.
            release(ticket);
            ctrl.lastChunkPlanTick = -1000;
            return;
        }
        reorder(ticket, pair);
        try {
            if (!world.field_73059_b.func_73149_a(cx, cz)) {
                world.func_72964_e(cx, cz);
            }
        } catch (Throwable ignored) {
        }
    }

    private Ticket adoptOrCreateTicket(DroneController ctrl, WorldServer world) {
        Ticket ticket = this.droneTickets.get(ctrl.id);
        if (ticket != null && ticket.world == world) return ticket;
        if (ticket != null) {
            release(ticket);
        }
        if (ctrl.entityUuid != null) {
            Ticket restored = this.restoredEntityTickets.remove(ctrl.entityUuid);
            if (restored != null) {
                if (restored.world == world) {
                    try {
                        restored.bindEntity(ctrl.drone);
                    } catch (Throwable ignored) {
                    }
                    configureTicket(restored);
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
        if (ctrl.entityUuid != null) {
            release(this.restoredEntityTickets.remove(ctrl.entityUuid));
            release(this.anchorTickets.remove("saved_drone:" + ctrl.entityUuid));
            release(this.anchorTickets.remove("drone:" + ctrl.entityUuid));
        }
        if (ctrl.ownerName != null) {
            release(this.anchorTickets.remove("saved_drone:" + ctrl.ownerName));
        }
        ctrl.forcedChunkX = Integer.MIN_VALUE;
        ctrl.forcedChunkZ = Integer.MIN_VALUE;
        ctrl.forcedDim = Integer.MIN_VALUE;
    }

    private void releaseDroneStartupAnchor(DroneController ctrl) {
        if (ctrl.entityUuid != null) {
            String uuid = ctrl.entityUuid.toString();
            release(this.anchorTickets.remove("saved_drone:" + uuid));
            release(this.anchorTickets.remove("drone:" + uuid));
        }
        if (ctrl.ownerName != null) {
            release(this.anchorTickets.remove("saved_drone:" + ctrl.ownerName));
        }
    }

    // ------------------------------------------------------------------
    // Ticket application
    // ------------------------------------------------------------------

    /**
     * Applies a high-to-low priority plan to a ticket and loads missing chunks
     * in that same nearest-first order.
     *
     * @return number of chunks explicitly loaded during this call
     */
    private int applyTicketChunks(WorldServer world, Ticket ticket, List<ChunkCoordIntPair> plan,
                                  boolean playerAware, int loadBudget) {
        if (world == null || ticket == null || plan == null || plan.isEmpty()) return 0;
        configureTicket(ticket);
        int depth = ticket.getChunkListDepth();
        if (depth <= 0 || depth > DroneChunkPlanner.MAX_TICKET_CHUNKS) {
            depth = DroneChunkPlanner.MAX_TICKET_CHUNKS;
        }
        List<ChunkCoordIntPair> desired = plan.size() > depth ? plan.subList(0, depth) : plan;
        Set<ChunkCoordIntPair> desiredSet = new HashSet<ChunkCoordIntPair>(desired);

        Map<ChunkCoordIntPair, Long> pending = this.releaseAt.get(ticket);
        if (pending == null) {
            pending = new java.util.HashMap<ChunkCoordIntPair, Long>();
            this.releaseAt.put(ticket, pending);
        }

        long now = world.func_82737_E();
        Set<ChunkCoordIntPair> current = new HashSet<ChunkCoordIntPair>(ticket.getChunkList());
        ChunkCoordIntPair center = desired.get(0);

        // 1. Drop claims we no longer need. Near chunks get the full grace so
        // border crossings do not churn; chunks left far behind are released
        // after a short grace so a moving ticket cannot saturate at 25 chunks.
        for (ChunkCoordIntPair old : current) {
            if (desiredSet.contains(old)) {
                pending.remove(old);
                continue;
            }
            Long due = pending.get(old);
            if (due == null) {
                boolean near = Math.abs(old.field_77276_a - center.field_77276_a) <= NEAR_RETENTION_CHUNKS
                    && Math.abs(old.field_77275_b - center.field_77275_b) <= NEAR_RETENTION_CHUNKS;
                long grace = near ? RELEASE_GRACE_TICKS : FAR_RELEASE_GRACE_TICKS;
                pending.put(old, Long.valueOf(now + grace));
            } else if (now >= due.longValue()) {
                pending.remove(old);
                unforce(ticket, old);
            }
        }

        // 2. Claim the desired set. The drone's own 3x3 is always claimed, even
        // when a player is nearby: an extra claim cannot unload a player chunk,
        // and it removes the gap when the player walks out of view mid-flight.
        Set<ChunkCoordIntPair> held = new HashSet<ChunkCoordIntPair>(ticket.getChunkList());
        for (int di = 0; di < desired.size(); di++) {
            ChunkCoordIntPair pair = desired.get(di);
            boolean core = Math.abs(pair.field_77276_a - center.field_77276_a) <= DroneChunkPlanner.DRONE_CHUNK_RADIUS
                && Math.abs(pair.field_77275_b - center.field_77275_b) <= DroneChunkPlanner.DRONE_CHUNK_RADIUS;
            if (playerAware && !core
                && isPlayerManaged(world, pair.field_77276_a, pair.field_77275_b)) {
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

        // 3. Highest-priority chunk at the tail, lowest-priority at the head.
        for (int i = desired.size() - 1; i >= 0; i--) {
            ChunkCoordIntPair pair = desired.get(i);
            if (!held.contains(pair)) continue;
            reorder(ticket, pair);
        }

        // 4. Explicitly load the missing chunks nearest-first. Forge ticket
        // claims alone do not load anything. Player-owned chunks are left to
        // vanilla's PlayerManager even if an old claim is still held.
        int loads = 0;
        for (ChunkCoordIntPair pair : desired) {
            if (loadBudget <= 0) break;
            if (!held.contains(pair)) continue;
            boolean core = Math.abs(pair.field_77276_a - center.field_77276_a) <= DroneChunkPlanner.DRONE_CHUNK_RADIUS
                && Math.abs(pair.field_77275_b - center.field_77275_b) <= DroneChunkPlanner.DRONE_CHUNK_RADIUS;
            if (playerAware && !core
                && isPlayerManaged(world, pair.field_77276_a, pair.field_77275_b)) {
                continue;
            }
            if (((WorldServer) world).field_73059_b.func_73149_a(pair.field_77276_a, pair.field_77275_b)) {
                continue;
            }
            try {
                world.func_72964_e(pair.field_77276_a, pair.field_77275_b);
                loadBudget--;
                loads++;
            } catch (Throwable ignored) {
                break;
            }
        }
        return loads;
    }

    private static int loadHotChunks(WorldServer world, List<ChunkCoordIntPair> plan, int budget) {
        int loaded = 0;
        for (ChunkCoordIntPair pair : plan) {
            if (budget <= 0) break;
            if (isPlayerManaged(world, pair.field_77276_a, pair.field_77275_b)) continue;
            if (world.field_73059_b.func_73149_a(pair.field_77276_a, pair.field_77275_b)) continue;
            try {
                world.func_72964_e(pair.field_77276_a, pair.field_77275_b);
                budget--;
                loaded++;
            } catch (Throwable ignored) {
                break;
            }
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

    /** Configure a ticket's depth; the server may grant less than our target. */
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

    private static boolean force(Ticket ticket, ChunkCoordIntPair pair) {
        try {
            ForgeChunkManager.forceChunk(ticket, pair);
            return ticket.getChunkList().contains(pair);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Drops only this mod's claim. It cannot unload a player-watched chunk:
     * vanilla's PlayerManager owns that chunk independently.
     */
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

    /**
     * Releases a ticket immediately. {@code releaseTicket} only removes our
     * claims; player chunks stay loaded through vanilla's own watcher set.
     */
    private void release(Ticket ticket) {
        if (ticket == null) return;
        this.releaseAt.remove(ticket);
        this.droneTickets.values().remove(ticket);
        this.anchorTickets.values().remove(ticket);
        this.restoredEntityTickets.values().remove(ticket);
        try {
            ForgeChunkManager.releaseTicket(ticket);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Helpers used by navigation and read-only RPC
    // ------------------------------------------------------------------

    /**
     * Loads the chunk under a block coordinate on demand, using chunk
     * coordinates ({@code func_72964_e}), never block coordinates
     * ({@code func_72938_d}, which shifts right by four internally).
     * Player-owned chunks are already loaded by vanilla and are left alone.
     */
    public static void ensureChunkLoadedStatic(World world, double x, double z) {
        if (!(world instanceof WorldServer)) return;
        try {
            int cx = MathHelper.func_76128_c(x) >> 4;
            int cz = MathHelper.func_76128_c(z) >> 4;
            if (isPlayerManaged(world, cx, cz)) return;
            WorldServer ws = (WorldServer) world;
            if (!ws.field_73059_b.func_73149_a(cx, cz)) {
                ws.func_72964_e(cx, cz);
            }
        } catch (Throwable ignored) {
        }
    }

    private static final class AnchorSpec {
        final WorldServer world;
        final String key;
        final double x;
        final double z;
        final int radius;

        AnchorSpec(WorldServer world, String key, double x, double z, int radius) {
            this.world = world;
            this.key = key;
            this.x = x;
            this.z = z;
            this.radius = radius;
        }
    }
}
