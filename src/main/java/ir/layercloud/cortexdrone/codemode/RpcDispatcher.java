package ir.layercloud.cortexdrone.codemode;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.codemode.rpc.MasterRpcHandler;
import ir.layercloud.cortexdrone.codemode.rpc.RpcHandler;
import ir.layercloud.cortexdrone.action.block.BlockActionService;
import ir.layercloud.cortexdrone.inventory.ChestSorter;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.inventory.SmeltingService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.navigation.FlightPathfinder;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import ir.layercloud.cortexdrone.world.WorldScanner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Main-thread synchronous RPC router that dispatches Code Mode SDK calls
 * to modular domain handlers (World, Navigation, Inventory, Action, Effects).
 *
 * Large cube scans are special: they must stay on the server (main) thread for
 * 1.7.10 world safety (chunk/world objects are not thread-safe off-thread), but
 * they are also the only RPCs that can eat a whole 50ms tick when run as one
 * monolithic loop (radius=64 cube = 129^3 = 2.1M voxels, plus underground scans
 * to Y=1). So this router time-slices them across server ticks:
 * - RpcDispatcher owns a single pending large-scan job (one at a time).
 * - A large-scan request arriving while a job is already queued/running is
 *   rejected immediately (busy) instead of piling up behind it on the main
 *   thread, mirroring the existing LLM circuit-breaker / 8s-latch posture.
 * - The job advances in bounded per-tick slices (bounded voxels and bounded
 *   elapsed ms), then drains across ticks (RpcDispatcher.onServerTickEnd) and
 *   delivers partial results with capped radius confirmation, a truncated
 *   marker, and scanned voxel/cell counts.
 */
public class RpcDispatcher {
    private static final int LARGE_RADIUS_THRESHOLD = 32;
    private static final int MAX_JOB_AGE_MS = 30000;
    // Server tick budget is 50 ms; keep a slice small enough to leave headroom for
    // drone physics + A* in the same tick, with a tight per-voxel cap plus a
    // deadline check every 1024 voxels so one slice stays near MAX_SLICE_MS.
    private static final long MAX_SLICE_MS = 8L;
    private static final int MAX_SLICE_VOXELS = 30000;

    // Capped radius by default 32 (was unchecked up to 64 in WorldRpcHandler).
    private static final int DEFAULT_SCAN_RADIUS_CAP = 32;
    private static final int ABSOLUTE_SCAN_RADIUS_CAP = 64;
    private static final int MAX_SCAN_RESULTS = 400;

    private final AIDroneManager manager;
    private final Map<String, RpcHandler> handlerRegistry = new HashMap<String, RpcHandler>();
    private final AtomicScanLock scanLock = new AtomicScanLock();
    private volatile ScanJob activeJob;
    private final Map<String, ScanJob> jobByKey = new ConcurrentHashMap<String, ScanJob>();

    public RpcDispatcher(
        AIDroneManager manager,
        WorldScanner worldScanner,
        FlightPathfinder pathfinder,
        InventoryService inventoryService,
        ChestSorter chestSorter,
        SmeltingService smeltingService,
        BlockActionService blockActionService
    ) {
        this.manager = manager;
        // Single master tool surface. Legacy handlers remain internal delegates
        // inside MasterRpcHandler; they are deliberately NOT registered as tools.
        registerHandler(new MasterRpcHandler(
            manager, worldScanner, pathfinder, inventoryService, chestSorter,
            smeltingService, blockActionService
        ));
    }

    public void registerHandler(RpcHandler handler) {
        if (handler == null || handler.getSupportedMethods() == null) return;
        for (String method : handler.getSupportedMethods()) {
            if (method != null && !method.isEmpty()) {
                this.handlerRegistry.put(method.trim().toLowerCase(), handler);
            }
        }
    }

    /** Drain active large-scan work; called from the server tick END phase. */
    public void onServerTickEnd() {
        // Check the clock before each step so MAX_SLICE_MS is actually honoured;
        // the old post-hoc drain did up to 90k single-voxel iterator steps per
        // tick even when the wall clock had already blown past one tick budget.
        // Route through drainActiveJob() so the 30s age-out is live: this used
        // to call drainActiveJobWithBudget directly, which made MAX_JOB_AGE_MS
        // unreachable dead code and let a wedged scan hold the lock forever.
        this.drainActiveJob();
    }

    private void drainActiveJob() {
        ScanJob job = this.activeJob;
        if (job == null || job.done) return;
        // Age out a wedged job so one stuck scan cannot starve the queue forever.
        if (System.currentTimeMillis() - job.createdAtMs > MAX_JOB_AGE_MS) {
            job.truncated = true;
            job.done = true;
            this.finishJob(job);
            return;
        }
        this.drainActiveJobWithBudget(job, MAX_SLICE_MS);
    }

    private void drainActiveJobWithBudget(ScanJob job, long budgetMs) {
        // Check the clock before each step so the budget is actually honoured;
        // the old loop checked the clock after the slice and could iterate
        // 90k single-voxel iterator steps per tick even past one tick budget.
        long deadline = System.currentTimeMillis() + Math.max(1L, budgetMs);
        long voxelsThisSlice = 0;
        while (!job.done && voxelsThisSlice < MAX_SLICE_VOXELS) {
            voxelsThisSlice += job.stepSlice();
            if ((voxelsThisSlice & 0x3FF) == 0 && System.currentTimeMillis() >= deadline) break;
        }
        if (job.done) {
            this.finishJob(job);
        } else if (System.currentTimeMillis() - job.createdAtMs > MAX_JOB_AGE_MS) {
            // Mid-slice safety: a large scan exceeding the age budget is truncated
            // even mid-slice so one stuck scan cannot starve the queue forever.
            job.truncated = true;
            job.done = true;
            this.finishJob(job);
        }
    }

    public JsonObject executeOnMainThread(final DroneController ctrl, final EntityPlayerMP defaultPlayer, final String method, final JsonObject args) {
        // Large scans are routed to the time-sliced job queue instead of a
        // monolithic main-thread loop. Cheap RPCs take the direct host path
        // with a per-method budget; see getTimeoutForMethod().
        String m = method != null ? method.trim().toLowerCase() : "";
        JsonObject workArgs = args != null ? args : new JsonObject();
        if (isLargeScan(m, workArgs) && !isAlreadyOnServerThread()) {
            return this.submitLargeScan(ctrl, defaultPlayer, m, workArgs);
        }
        // A scan already running on the server thread must never be re-entered
        // from the tick queue (deadlock / reentrancy on 1.7.10 world state).
        final ScanJob nested = isLargeScan(m, workArgs) ? this.activeJob : null;
        if (nested != null && isAlreadyOnServerThread()) {
            JsonObject busy = new JsonObject();
            busy.addProperty("ok", false);
            busy.addProperty("error", "Large scan already in progress; result pending");
            busy.addProperty("busy", true);
            return busy;
        }
        try {
            JsonObject res = this.manager.callOnMainThread(new Callable<JsonObject>() {
                @Override
                public JsonObject call() {
                    return executeSync(ctrl, defaultPlayer, method, args != null ? args : new JsonObject());
                }
            }, getTimeoutForMethod(m, workArgs));
            if (res == null) {
                JsonObject err = new JsonObject();
                err.addProperty("ok", false);
                err.addProperty("error", "Main thread RPC timeout for method: " + method);
                return err;
            }
            return res;
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", e.toString());
            return err;
        }
    }

    /** Poll (main-thread safe) whether the active large scan has finished. */
    public JsonObject pollScanResult(String jobId) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        if (jobId == null) {
            ScanJob job = this.activeJob;
            if (job == null) {
                res.addProperty("done", true);
                res.addProperty("found", false);
                res.add("blocks", new JsonArray());
                res.addProperty("count", 0);
                return res;
            }
            jobId = job.id;
        }
        ScanJob job = this.jobByKey.get(jobId);
        if (job == null) {
            res.addProperty("done", true);
            res.addProperty("found", false);
            res.addProperty("error", "Unknown scan job: " + jobId);
            res.add("blocks", new JsonArray());
            res.addProperty("count", 0);
            return res;
        }
        if (job.done && job.result != null) {
            return job.result;
        }
        // Poll must not drain inline: draining here runs a full time slice on
        // the HTTP worker thread (not the server thread) and races world state,
        // besides letting a tight poll loop (250 ms backoff) monopolize ticks.
        // onServerTickEnd already advances the job every server tick.
        // done jobs settle with a result snapshot; keep poll output stable
        res.addProperty("done", job.done);
        res.addProperty("jobId", job.id);
        res.addProperty("truncated", job.truncated);
        res.addProperty("count", job.results.size());
        res.addProperty("scannedVoxels", job.scannedVoxels);
        res.addProperty("radius", job.radius);
        res.addProperty("cappedRadius", job.radius);
        if (job.done) {
            res.add("blocks", job.toJsonArray());
        } else {
            res.add("blocks", new JsonArray());
            res.addProperty("pending", true);
        }
        return res;
    }

    private boolean isAlreadyOnServerThread() {
        return Thread.currentThread().getName() != null
            && Thread.currentThread().getName().contains("Server thread");
    }

    private static boolean isLargeScan(String method, JsonObject args) {
        if (!"inspect".equals(method)) return false;
        String op = args != null && args.has("op") && !args.get("op").isJsonNull()
            ? args.get("op").getAsString().trim().toLowerCase() : "";
        if (!"blocks".equals(op) && !"scanblocks".equals(op)) return false;
        int radius = parseIntField(args, "radius", -1);
        // Radius > default cap counts as large. The WorldRpcHandler also
        // confirms the capped radius; here we only route.
        return radius > LARGE_RADIUS_THRESHOLD;
    }

    private static long getTimeoutForMethod(String method, JsonObject args) {
        // Avoid a flat 8s wait for everything: cheap read-only inspection gets a
        // tight budget while world-mutating primitives keep their longer horizon.
        if ("inspect".equals(method)) {
            String op = args != null && args.has("op") && !args.get("op").isJsonNull()
                ? args.get("op").getAsString().trim().toLowerCase() : "";
            if ("blocks".equals(op) || "scanblocks".equals(op) || "box".equals(op)
                || "veins".equals(op) || "scanveins".equals(op)
                || "room".equals(op) || "scanroom".equals(op)
                || "distant".equals(op) || "finddistant".equals(op)
                || "entities".equals(op) || "getentities".equals(op)
                || "drops".equals(op) || "getdrops".equals(op)) {
                return 2500L;
            }
        }
        return 8000L;
    }

    private static int parseIntField(JsonObject args, String key, int fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return fallback;
        try {
            JsonElement el = args.get(key);
            if (el.isJsonPrimitive()) {
                JsonPrimitive prim = el.getAsJsonPrimitive();
                if (prim.isNumber()) return prim.getAsInt();
                if (prim.isString()) {
                    String s = prim.getAsString().trim();
                    if (s.isEmpty()) return fallback;
                    return Integer.parseInt(s);
                }
            }
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    private JsonObject submitLargeScan(DroneController ctrl, EntityPlayerMP player, String method, JsonObject args) {
        // Large scans must remain on the server thread for 1.7.10 safety, so
        // they are executed as ONE time-sliced job on the tick queue instead of
        // a single monolithic block scan. Only one job runs at a time; later
        // requests get busy + jobId to poll rather than queueing unboundedly.
        ScanJob queued = new ScanJob(ctrl, player, method, args);
        if (!this.scanLock.tryAcquire()) {
            JsonObject busy = new JsonObject();
            busy.addProperty("ok", false);
            busy.addProperty("error", "Large scan already queued/running; use poll_scan for the active jobId");
            busy.addProperty("busy", true);
            ScanJob cur = this.activeJob;
            if (cur != null) busy.addProperty("jobId", cur.id);
            return busy;
        }
        this.activeJob = queued;
        this.jobByKey.put(queued.id, queued);
        // Push the first slice onto the normal main queue so the HTTP caller
        // gets partial confirmation quickly; the rest drains from onServerTickEnd.
        this.manager.getMod().queueMain(new Runnable() {
            @Override
            public void run() {
                try {
                    ScanJob job = queued;
                    drainActiveJobWithBudget(job, MAX_SLICE_MS);
                } catch (Throwable t) {
                    JsonObject err = new JsonObject();
                    err.addProperty("ok", false);
                    err.addProperty("error", t.toString());
                    queued.result = err;
                    queued.done = true;
                    finishJob(queued);
                }
            }
        });
        JsonObject accepted = new JsonObject();
        accepted.addProperty("ok", true);
        accepted.addProperty("queued", true);
        accepted.addProperty("pending", true);
        accepted.addProperty("jobId", queued.id);
        accepted.addProperty("radius", queued.radius);
        accepted.addProperty("cappedRadius", queued.radius);
        accepted.addProperty("truncated", true);
        accepted.addProperty("scanMode", "time_sliced");
        return accepted;
    }

    private void finishJob(ScanJob job) {
        if (job == this.activeJob) {
            // Retain in jobByKey so poll_scan can still fetch the final result,
            // but release the single-running lock for the next submission.
            this.activeJob = null;
        }
        if (job.result == null) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", true);
            r.addProperty("count", job.results.size());
            r.add("blocks", job.toJsonArray());
            r.addProperty("scannedVoxels", job.scannedVoxels);
            r.addProperty("truncated", job.truncated);
            r.addProperty("radius", job.radius);
            r.addProperty("cappedRadius", job.radius);
            r.addProperty("done", true);
            r.addProperty("jobId", job.id);
            job.result = r;
        }
        this.scanLock.release();
    }

    private static final class CollectionsSortHelper {
        static void sortByDistanceSq(List<int[]> list) {
            Collections.sort(list, new Comparator<int[]>() {
                @Override
                public int compare(int[] a, int[] b) {
                    return Integer.compare(a[4], b[4]);
                }
            });
        }
    }

    /** Single-running large-scan gate (no hard-coded method list needed). */
    private static final class AtomicScanLock {
        private boolean held;
        synchronized boolean tryAcquire() {
            if (this.held) return false;
            this.held = true;
            return true;
        }
        synchronized void release() {
            this.held = false;
        }
    }

    /**
     * One time-sliced scan job. stepSlice() advances a 3D cursor over the
     * capped cube (sorted by distance so farthest matches are found first)
     * without ever leaving the server thread and without monopolizing a tick.
     * Results are distance-sorted at completion; partial results carry
     * scanned-voxel counts and a truncated flag so poll_scan callers can
     * narrow rather than re-issue the identical call.
     */
    private static final class ScanJob {
        final String id;
        final long createdAtMs;
        final DroneController ctrl;
        final EntityPlayerMP player;
        final String method;
        final String filter;
        int cx;
        int cy;
        int cz;
        int minY;
        int maxY;
        final boolean liquidOnly;
        final long totalVoxels;
        final int radius;
        final int limit;
        long scannedVoxels;
        int cursorX;
        int cursorY;
        int cursorZ;
        boolean done;
        boolean truncated;
        JsonObject result;
        final List<int[]> results = new ArrayList<int[]>();
        final Set<Long> seen = new HashSet<Long>();

        ScanJob(DroneController ctrl, EntityPlayerMP player, String method, JsonObject args) {
            this.ctrl = ctrl;
            this.player = player;
            this.method = method;
            this.id = "scan-" + System.nanoTime();
            this.createdAtMs = System.currentTimeMillis();
            this.filter = args.has("filter") && !args.get("filter").isJsonNull()
                ? args.get("filter").getAsString() : "ores";
            int px = args.has("x") ? args.get("x").getAsInt() : 0;
            int py = args.has("y") ? args.get("y").getAsInt() : 0;
            int pz = args.has("z") ? args.get("z").getAsInt() : 0;
            boolean hasExplicitCenter = args.has("x") && args.has("y") && args.has("z");
            this.cx = px;
            this.cy = py;
            this.cz = pz;
            // Legacy submit path (no explicit center) mis-anchored at (0,0,0);
            // mirror WorldRpcHandler: anchor at drone/player position instead.
            // Mutate through a single helper to keep blank-final assignment legal.
            if (!hasExplicitCenter && (player != null || (ctrl != null && ctrl.drone != null))) {
                double ax = player != null ? player.field_70165_t : ctrl.drone.field_70165_t;
                double ay = player != null ? player.field_70163_u : ctrl.drone.field_70163_u;
                double az = player != null ? player.field_70161_v : ctrl.drone.field_70161_v;
                this.cx = (int) Math.floor(ax);
                this.cy = (int) Math.floor(ay + 0.5);
                this.cz = (int) Math.floor(az);
            }
            boolean underground = !hasExplicitCenter && ItemClassifier.isLikelyUndergroundQuery(this.filter);
            int requestedRadius = parseIntField(args, "radius", 24);
            int cap = DEFAULT_SCAN_RADIUS_CAP;
            int r = Math.min(ABSOLUTE_SCAN_RADIUS_CAP, Math.max(1, requestedRadius));
            // Confirm cap at submit time: the partial result already reports it.
            this.radius = Math.min(cap, r);
            // Y window mirrors WorldRpcHandler semantics.
            this.minY = args.has("minY") ? Math.max(1, args.get("minY").getAsInt()) : Math.max(1, this.cy - this.radius);
            this.maxY = args.has("maxY") ? Math.min(254, args.get("maxY").getAsInt()) : Math.min(254, this.cy + this.radius);
            // Underground without explicit center: scan down to Y=1 for ores.
            if (underground && !args.has("minY") && !args.has("maxY")) {
                this.minY = 1;
                this.maxY = Math.min(254, this.cy + this.radius);
            }
            this.liquidOnly = this.filter.equalsIgnoreCase("liquid");
            this.limit = MAX_SCAN_RESULTS;
            this.cursorX = this.cx - this.radius;
            this.cursorY = this.minY;
            this.cursorZ = this.cz - this.radius;
            this.totalVoxels = (long) (2 * this.radius + 1) * (long) Math.max(0, this.maxY - this.minY + 1) * (long) (2 * this.radius + 1);
            this.done = this.totalVoxels <= 0;
            this.truncated = false;
        }

        /** Advance the cursor by at most one voxel; returns 1. */
        long stepSlice() {
            if (this.done) return 0L;
            // Clamp to world height for safety; cursor starts within bounds.
            if (this.cursorX > this.cx + this.radius) {
                this.done = true;
                return 0L;
            }
            int x = this.cursorX;
            int y = this.cursorY;
            int z = this.cursorZ;
            // Advance cursor for next call.
            if (++this.cursorZ > this.cz + this.radius) {
                this.cursorZ = this.cz - this.radius;
                if (++this.cursorY > this.maxY) {
                    this.cursorY = this.minY;
                    ++this.cursorX;
                }
            }
            this.scannedVoxels++;
            if (y < 1 || y > 254) return 1L;
            if (this.ctrl == null || this.ctrl.drone == null || this.ctrl.drone.field_70170_p == null || this.ctrl.drone.field_70128_L) {
                this.truncated = true;
                this.done = true;
                JsonObject err = new JsonObject();
                err.addProperty("ok", false);
                err.addProperty("error", "No active drone");
                this.result = err;
                return 1L;
            }
            // Early-exit once the cap is reached; scanning the remaining cube
            // voxels only to confirm "no more hits" wastes ticks. Matches the
            // sync handler's 400-result truncation semantics.
            if (this.results.size() >= this.limit) {
                this.truncated = true;
                this.done = true;
                return 1L;
            }
            try {
                net.minecraft.world.World world = this.ctrl.drone.field_70170_p;
                long key = FlightPathfinder.packBlockPos(x, y, z);
                if (!this.seen.add(key)) return 1L;
                if (world.func_147437_c(x, y, z)) return 1L;
                net.minecraft.block.Block b = world.func_147439_a(x, y, z);
                int meta = world.func_72805_g(x, y, z);
                boolean match = this.liquidOnly
                    ? ItemClassifier.isLiquidBlock(b)
                    : ItemClassifier.matchesMineFilter(b, meta, this.filter);
                if (match) {
                    int distSq = (x - this.cx) * (x - this.cx) + (y - this.cy) * (y - this.cy) + (z - this.cz) * (z - this.cz);
                    this.results.add(new int[]{x, y, z, meta, distSq});
                }
                if (this.scannedVoxels >= this.totalVoxels) {
                    this.done = true;
                    this.truncated = false;
                }
            } catch (Throwable t) {
                // Single-voxel failures must not kill the whole sliced scan.
                this.truncated = true;
            }
            if (this.cursorX > this.cx + this.radius || this.scannedVoxels >= this.totalVoxels) {
                this.done = true;
            }
            return 1L;
        }

        JsonArray toJsonArray() {
            List<int[]> sorted = new ArrayList<int[]>(this.results);
            // Distance-sorted at completion, matching the sync handler.
            CollectionsSortHelper.sortByDistanceSq(sorted);
            JsonArray arr = new JsonArray();
            int limit = Math.min(this.limit, sorted.size());
            for (int i = 0; i < limit; i++) {
                int[] r = sorted.get(i);
                JsonObject bo = new JsonObject();
                bo.addProperty("x", r[0]);
                bo.addProperty("y", r[1]);
                bo.addProperty("z", r[2]);
                // Counts include all matches; blocks array is capped like the sync path.
                bo.addProperty("meta", r[3]);
                bo.addProperty("dist", Math.round(Math.sqrt(r[4]) * 10.0) / 10.0);
                arr.add(bo);
            }
            return arr;
        }
    }

    public JsonObject executeSync(DroneController ctrl, EntityPlayerMP player, String method, JsonObject args) {
        // Direct poll endpoint for time-sliced large scans, so JS callers can
        // retrieve partial results without re-issuing the scan.
        String pre = method != null ? method.trim().toLowerCase() : "";
        if (pre.equals("poll_scan")) {
            String jobId = args != null && args.has("jobId") && !args.get("jobId").isJsonNull()
                ? args.get("jobId").getAsString() : null;
            return this.pollScanResult(jobId);
        }
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70128_L) {
            JsonObject res = new JsonObject();
            res.addProperty("ok", false);
            res.addProperty("error", "No active drone");
            return res;
        }
        String m = method != null ? method.trim().toLowerCase() : "";
        RpcHandler handler = this.handlerRegistry.get(m);
        if (handler != null) {
            return handler.handle(ctrl, player, m, args != null ? args : new JsonObject());
        }
        JsonObject err = new JsonObject();
        err.addProperty("ok", false);
        err.addProperty("error", "Unknown RPC method: " + m);
        return err;
    }
}
