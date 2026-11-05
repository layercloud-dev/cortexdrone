package ir.layercloud.cortexdrone.model;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import ir.layercloud.cortexdrone.harness.TaskSession;
import li.cil.oc.common.entity.Drone;

public class DroneController {
    public final int id;
    public Drone drone;
    public UUID entityUuid;
    public String name;
    public String ownerName;
    public String targetPlayerName;
    public Mode mode = Mode.FOLLOW;
    public double goalX;
    public double goalY;
    public double goalZ;
    // Master navigation route selected by the AI: auto | direct | tunnel | up | down.
    // "up" routes via the surface first and then continues to the final goal.
    public String navigationRoute = "auto";
    public boolean navViaSurface = false;
    public double navFinalX = Double.NaN;
    public double navFinalY = Double.NaN;
    public double navFinalZ = Double.NaN;
    // Single task / single session state. The harness never queues instructions.
    public final TaskSession task = new TaskSession();
    public double carryGoalX;
    public double carryGoalY;
    public double carryGoalZ;

    // Follow anchor & look-to-interact state
    public double anchorX;
    public double anchorY;
    public double anchorZ;
    public boolean hasAnchor = false;
    public boolean playerLookHold = false;

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
    // Separate solid-waypoint throttle: FlightPathfinder and DroneTickCoordinator
    // share lastStuckKey/lastStuckLogTick, so their keys overwrote each other
    // and the throttle never held — one STUCK line per tick for minutes.
    public String lastSolidKey = "";
    public int lastSolidLogTick = -10000;
    // Per-waypoint hit counters: an A/B/A/B alternation of two blocked goals used
    // to defeat the single lastSolidKey throttle, so escalation never fired and
    // the drone hovered forever. Hits are counted per blocked waypoint instead.
    public final Map<Long, Integer> solidWaypointHits = new HashMap<Long, Integer>();
    public int solidLogTick = -10000;
    public int solidEscalations = 0;
    // A blocked waypoint can force a short self-rescue dig. That dig must not
    // silently end the task the drone was already doing, so the interrupted
    // task (mode, goal, mine flags) is snapshotted and restored afterwards.
    public TaskSnapshot escapeResume = null;

    public static class TaskSnapshot {
        public Mode mode;
        public double goalX;
        public double goalY;
        public double goalZ;
        public String mineFilter;
        public boolean mineExactQueueOnly;
        public boolean mineUntilFull;
        public boolean returnToOwnerAfterMine;
        public int mineTargetCount;
        public int minedCount;
        public int mineMatchedCount;
        public int[] mineTarget;
        public double[] mineStandoff;
        // The original queue is parked here while the short self-rescue queue runs.
        // Leaving rescue digs at the front of the real queue kept the rescue's
        // "exact queue only / mine everything" flags alive until the whole task
        // drained, so "mine 8 iron" never stopped at 8.
        public List<int[]> mineQueue;
    }
    public String lastPathLogKey = "";
    public int lastPathLogTick = -10000;
    public final Deque<int[]> navigationHistory = new ConcurrentLinkedDeque<int[]>();
    public int[] lastSurfaceAnchor = null;

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
    public String mineFilter = "ores";
    public boolean mineExactQueueOnly = false;
    public boolean returnToOwnerAfterMine = false;
    public boolean mineUntilFull = false;
    public int mineTargetCount = 0;
    public final Deque<int[]> mineQueue = new ConcurrentLinkedDeque<int[]>();
    public int[] currentMineTarget = null;
    public double[] currentMineStandoff = null;
    public int mineProgressTicks = 0;
    public int miningRequiredTicks = 8;
    public int minedCount = 0;
    public int mineMatchedCount = 0;
    public int builtCount = 0;

    public int scoopedCount = 0;

    // Deposit / delivery target state
    public int[] targetDepositChestPos = null;
    public String dropFilter = "all";

    // Hunting, guard & patrol state
    public String huntFilter = "sheep|cow|pig";
    public int huntTargetCount = 10;
    public int huntedCount = 0;
    public int targetAttackEntityId = -1;
    public final Map<Integer, Long> unreachableEntities = new HashMap<Integer, Long>();
    public String guardFilter = "hostile|mutant";
    public int patrolRadius = 36;

    // Active Code Mode loop generation ID & running script counter
    public final AtomicInteger activeLoopId = new AtomicInteger(0);
    public final AtomicInteger activeScriptCount = new AtomicInteger(0);
    private final java.util.Set<Integer> runningLoopIds = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Integer, Boolean>());
    private final java.util.Set<Integer> cancelledLoopIds = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Integer, Boolean>());
    public volatile String lastThought = "";
    public final Deque<String> thoughtHistory = new ConcurrentLinkedDeque<String>();

    public int attackCooldown = 0;
    public int pickupCooldown = 0;
    public int stuckTicks = 0;
    // Tick-rate pacing counters (kept on the controller: each drone ticks independently)
    public int tickClock = 0;          // ever-incrementing per-drone tick counter
    public int followAnchorCooldown = 0;
    public int breadcrumbCooldown = 0;
    // Velocity/predictive-braking load state
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
    public float orbitAngle = 0.0f;
    public volatile boolean despawned = false;

    public DroneController(int id, Drone drone, String name, String ownerName) {
        this.id = id;
        this.drone = drone;
        this.entityUuid = drone.func_110124_au();
        this.name = name;
        this.ownerName = ownerName;
        this.targetPlayerName = ownerName;
        this.goalX = drone.field_70165_t;
        this.goalY = drone.field_70163_u;
        this.goalZ = drone.field_70161_v;
        this.anchorX = drone.field_70165_t;
        this.anchorY = drone.field_70163_u;
        this.anchorZ = drone.field_70161_v;
        this.lastX = drone.field_70165_t;
        this.lastY = drone.field_70163_u;
        this.lastZ = drone.field_70161_v;
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
            if (id != null && id.intValue() != keepLoopId && this.cancelledLoopIds.add(id)) {
                count++;
            }
        }
        return count;
    }

    public boolean hasActiveTaskOrLoop(int excludeLoopId) {
        for (Integer id : this.runningLoopIds) {
            if (id != null && id.intValue() != excludeLoopId && !this.cancelledLoopIds.contains(id)) {
                return true;
            }
        }
        return this.activeScriptCount.get() > 0 || !this.mineQueue.isEmpty()
            || (this.mode != Mode.FOLLOW && this.mode != Mode.STAY && this.mode != Mode.GUARD && this.mode != Mode.PATROL);
    }

    public static int hsvToRgbInt(float hDeg, float s, float v) {
        return LedEffectEngine.hsvToRgbInt(hDeg, s, v);
    }

    public static Integer parseNamedOrHexColor(String raw) {
        return LedEffectEngine.parseNamedOrHexColor(raw);
    }

    public int resolveEffectiveLedColor(int fallbackColor) {
        return LedEffectEngine.resolveEffectiveLedColor(this.ledEffect, this.ledEffectTick, this.customLedColor, fallbackColor);
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

    public void recordBreadcrumb(int x, int y, int z, boolean skyVisible) {
        if (skyVisible) {
            this.lastSurfaceAnchor = new int[]{x, y, z, 1};
        }
        int[] last = this.navigationHistory.peekLast();
        if (last != null) {
            int dx = x - last[0];
            int dy = y - last[1];
            int dz = z - last[2];
            if (dx * dx + dy * dy + dz * dz < 4) {
                return;
            }
        }
        // Loop pruning: if revisiting a recent voxel within 1 block, unwind the dead-end loop
        List<int[]> snapshot = new ArrayList<int[]>(this.navigationHistory);
        int matchIdx = -1;
        int searchStart = Math.max(0, snapshot.size() - 28);
        for (int i = searchStart; i < snapshot.size() - 1; i++) {
            int[] pt = snapshot.get(i);
            if (Math.abs(pt[0] - x) <= 1 && Math.abs(pt[1] - y) <= 1 && Math.abs(pt[2] - z) <= 1) {
                matchIdx = i;
                break;
            }
        }
        if (matchIdx >= 0) {
            int toPop = snapshot.size() - 1 - matchIdx;
            for (int p = 0; p < toPop; p++) {
                this.navigationHistory.pollLast();
            }
            return;
        }
        this.navigationHistory.addLast(new int[]{x, y, z, skyVisible ? 1 : 0});
        while (this.navigationHistory.size() > 256) {
            this.navigationHistory.pollFirst();
        }
    }

    /**
     * Cancels an in-flight blocked-waypoint rescue. When {@code restoreQueue} is
     * true the mining task that was parked during the rescue is put back, so an
     * internal transition (e.g. inventory full -> DROP) can resume it afterwards.
     * Explicit new commands pass false because they are replacing the task.
     */
    public void clearSelfRescue(boolean restoreQueue) {
        TaskSnapshot snap = this.escapeResume;
        this.escapeResume = null;
        if (!restoreQueue || snap == null) return;
        this.mineFilter = snap.mineFilter;
        this.mineExactQueueOnly = snap.mineExactQueueOnly;
        this.mineUntilFull = snap.mineUntilFull;
        this.returnToOwnerAfterMine = snap.returnToOwnerAfterMine;
        this.mineTargetCount = snap.mineTargetCount;
        this.minedCount = snap.minedCount;
        this.mineMatchedCount = snap.mineMatchedCount;
        if (snap.mineQueue == null) return;
        for (int[] p : snap.mineQueue) {
            if (p == null) continue;
            boolean duplicate = false;
            for (int[] q : this.mineQueue) {
                if (q != null && q[0] == p[0] && q[1] == p[1] && q[2] == p[2]) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) this.mineQueue.addLast(p);
        }
        if (snap.mineTarget != null) {
            boolean duplicate = false;
            for (int[] q : this.mineQueue) {
                if (q != null && q[0] == snap.mineTarget[0]
                    && q[1] == snap.mineTarget[1] && q[2] == snap.mineTarget[2]) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) this.mineQueue.addFirst(snap.mineTarget);
        }
    }

    public void clearActionState() {
        this.escapeResume = null;
        this.mineQueue.clear();
        this.currentMineTarget = null;
        this.currentMineStandoff = null;
        this.mineExactQueueOnly = false;
        this.targetDepositChestPos = null;
        this.targetAttackEntityId = -1;
        this.unreachableEntities.clear();
        this.mineUntilFull = false;
        this.mineTargetCount = 0;
        this.mineMatchedCount = 0;
    }
}
