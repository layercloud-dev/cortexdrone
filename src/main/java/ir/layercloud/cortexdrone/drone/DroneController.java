package ir.layercloud.cortexdrone.drone;

import li.cil.oc.common.entity.Drone;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-drone mutable state for one Code Mode session.
 *
 * <p>There is no mode flag. What the drone is doing is derived from the work
 * itself: an active navigation session, a pending mining target/queue, or a
 * retained action context. Idle means none of those exist.</p>
 */
public class DroneController {
    public final int id;
    public Drone drone;
    public UUID entityUuid;
    public String name;
    public String ownerName;

    // Goal of the last/active navigation session. Used for telemetry and physics.
    public double goalX;
    public double goalY;
    public double goalZ;
    // Incremented for every accepted navigation request.
    public int navigationGeneration = 0;
    public String navigationError = "";
    // Single task / single session state. The harness never queues instructions.
    public final TaskSession task = new TaskSession();

    // 3D A* pathfinding state
    public final List<double[]> pathWaypoints = new ArrayList<double[]>();
    public int pathIndex = 0;
    public double pathGoalX = Double.NaN;
    public double pathGoalY = Double.NaN;
    public double pathGoalZ = Double.NaN;
    public int repathCooldown = 0;
    public int lastPathNodesExpanded = 0;
    public boolean lastPathPartial = false;
    public String lastStuckReason = "";
    public String lastStuckKey = "";
    public int lastStuckLogTick = -10000;
    // Per-waypoint hit counters: an A/B/A/B alternation of two blocked goals
    // defeats a single last-key throttle, so hits are counted per waypoint.
    public final Map<Long, Integer> solidWaypointHits = new HashMap<Long, Integer>();
    public int solidEscalations = 0;
    public String lastPathLogKey = "";
    public int lastPathLogTick = -10000;

    // Single-block break/place and illumination state.
    // autoTorch == "illumination aura active". No torch item is consumed and no
    // block is placed: the drone raises the surrounding block light directly and
    // restores it when the aura is switched off.
    public boolean autoTorch = false;
    public int lightRadius = 6;
    public int lightIntensity = 14;
    public int lightColor = 0xFFFFFF;
    public int lightCooldown = 0;
    public long lightAnchor = Long.MIN_VALUE;
    // packed world position -> original block-light level, for exact restore
    public final Map<Long, Integer> lightCells = new HashMap<Long, Integer>();

    // Mining work: an explicit target plus the dig-route queue owned by the
    // building manager. No filter, no "mine until full", no premade task flags.
    public final Deque<int[]> mineQueue = new ConcurrentLinkedDeque<int[]>();
    public int[] currentMineTarget = null;
    public double[] currentMineStandoff = null;
    public int mineProgressTicks = 0;
    public int miningRequiredTicks = 8;
    public int minedCount = 0;
    public int builtCount = 0;
    public int scoopedCount = 0;

    // Active Code Mode loop generation ID & running script counter
    public final AtomicInteger activeLoopId = new AtomicInteger(0);
    public final AtomicInteger activeScriptCount = new AtomicInteger(0);
    private final Set<Integer> runningLoopIds = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    private final Set<Integer> cancelledLoopIds = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    public volatile String lastThought = "";
    public final Deque<String> thoughtHistory = new ConcurrentLinkedDeque<String>();

    public int attackCooldown = 0;
    public int pickupCooldown = 0;
    public int stuckTicks = 0;
    public int tickClock = 0;          // ever-incrementing per-drone tick counter
    public double velX = 0.0;
    public double velY = 0.0;
    public double velZ = 0.0;
    // Path-cache signature (world-revision + endpoint bucket) for findFlightPath reuse
    public long cachedPathSignature = Long.MIN_VALUE;
    public long cachedPathCreatedTick = -1;
    public Integer customLedColor = null;
    public String ledEffect = "NONE";
    public int ledEffectTick = 0;
    public double lastX;
    public double lastY;
    public double lastZ;
    public volatile boolean despawned = false;

    public DroneController(int id, Drone drone, String name, String ownerName) {
        this.id = id;
        this.drone = drone;
        this.entityUuid = drone.getUniqueID();
        this.name = name;
        this.ownerName = ownerName;
        this.goalX = drone.posX;
        this.goalY = drone.posY;
        this.goalZ = drone.posZ;
        this.lastX = drone.posX;
        this.lastY = drone.posY;
        this.lastZ = drone.posZ;
    }

    /** True while a block target or a dig queue is being worked. */
    public boolean isMining() {
        return this.currentMineTarget != null || !this.mineQueue.isEmpty();
    }

    public boolean isScriptRunning() {
        return this.activeScriptCount.get() > 0;
    }

    public void registerLoop(int loopId) {
        if (loopId > 0) this.runningLoopIds.add(loopId);
    }

    public void unregisterLoop(int loopId) {
        if (loopId > 0) {
            this.runningLoopIds.remove(loopId);
            this.cancelledLoopIds.remove(loopId);
        }
    }

    public boolean isLoopCancelled(int loopId) {
        return loopId > 0 && this.cancelledLoopIds.contains(loopId);
    }

    public int cancelOtherLoops(int keepLoopId) {
        int count = 0;
        for (Integer id : this.runningLoopIds) {
            if (id != null && id != keepLoopId && this.cancelledLoopIds.add(id)) {
                count++;
            }
        }
        return count;
    }

    public boolean hasActiveTaskOrLoop(int excludeLoopId) {
        for (Integer id : this.runningLoopIds) {
            if (id != null && id != excludeLoopId && !this.cancelledLoopIds.contains(id)) {
                return true;
            }
        }
        return this.activeScriptCount.get() > 0 || isMining();
    }

    public void recordThought(int turn, String thought) {
        if (thought == null || thought.trim().isEmpty()) return;
        String clean = thought.trim();
        this.lastThought = clean;
        this.thoughtHistory.addLast("T" + turn + ": " + clean);
        while (this.thoughtHistory.size() > 12) {
            this.thoughtHistory.pollFirst();
        }
    }

    public void clearActionState() {
        this.mineQueue.clear();
        this.currentMineTarget = null;
        this.currentMineStandoff = null;
    }
}
