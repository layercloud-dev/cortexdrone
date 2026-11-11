package ir.layercloud.cortexdrone.harness.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.WorldBounds;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Time-sliced block scan jobs.
 *
 * <p>A large cube scan must run on the server thread for 1.7.10 world safety,
 * but must not monopolize a tick. Only one job runs at a time; each tick
 * advances it by a bounded number of voxels and a bounded time budget. Results
 * are distance-sorted, capped, and explicitly marked {@code pending} /
 * {@code truncated} so callers can poll instead of re-scanning.</p>
 */
@SuppressWarnings("unused")
public class ScanJobs {
    private static final long MAX_JOB_AGE_MS = 30_000L;
    private static final long MAX_SLICE_MS = 8L;
    private static final int MAX_SLICE_VOXELS = 30_000;
    /**
     * @see ScanLimits#MAX_RADIUS
     */
    public static final int MAX_ADVERTISED_RADIUS = ScanLimits.MAX_RADIUS;
    /**
     * @see ScanLimits#MAX_RESULTS
     */
    public static final int MAX_ADVERTISED_RESULTS = ScanLimits.MAX_RESULTS;
    /**
     * Hard ceiling on a single slice radius.
     */
    public static final int ABSOLUTE_RADIUS_CAP = ScanLimits.MAX_RADIUS;
    /**
     * Hard ceiling on returned results.
     */
    public static final int MAX_RESULTS = ScanLimits.MAX_RESULTS;
    private static final long RETENTION_MS = 120_000L;

    private final AIDroneManager manager;
    private final Map<String, ScanJob> jobs = new ConcurrentHashMap<>();
    private volatile ScanJob activeJob;
    private final Gate gate = new Gate();

    public ScanJobs(AIDroneManager manager) {
        this.manager = manager;
    }

    public JsonObject submit(DroneController controller, EntityPlayerMP player, JsonObject args) {
        JsonObject work = args != null ? args : new JsonObject();
        ScanJob job = new ScanJob(controller, player, work);
        if (!this.gate.tryAcquire()) {
            JsonObject busy = ToolJson.error("scan_busy",
                    "A large scan is already running.",
                    "Call poll_scan with the active job_id until done=true, or wait for it to finish.", true);
            busy.addProperty("busy", true);
            ScanJob current = this.activeJob;
            if (current != null) busy.addProperty("job_id", current.id);
            return busy;
        }
        this.activeJob = job;
        purgeOldJobs();
        this.jobs.put(job.id, job);
        this.manager.getMod().queueMain(() -> {
            try {
                drainWithBudget(job);
            } catch (Throwable t) {
                JsonObject error = ToolJson.error("scan_failed", t.toString(),
                        "Retry the scan with a smaller radius.", true);
                job.result = error;
                job.done = true;
                finish(job);
            }
        });
        JsonObject accepted = ToolJson.ok();
        accepted.addProperty("queued", true);
        accepted.addProperty("pending", true);
        accepted.addProperty("done", false);
        accepted.addProperty("job_id", job.id);
        accepted.addProperty("radius", job.radius);
        accepted.addProperty("requested_radius", job.requestedRadius);
        accepted.addProperty("capped_radius", job.radius);
        accepted.addProperty("radius_was_capped", job.requestedRadius > job.radius);
        accepted.addProperty("requested_limit", job.requestedLimit);
        accepted.addProperty("limit_was_capped", job.requestedLimit > job.limit);
        accepted.addProperty("scan_mode", "time_sliced");
        accepted.addProperty("hint", "Poll poll_scan({job_id}) until done=true.");
        return accepted;
    }

    public JsonObject poll(String jobId) {
        ScanJob job;
        if (jobId == null || jobId.trim().isEmpty()) {
            job = this.activeJob;
            if (job == null) {
                JsonObject empty = ToolJson.ok();
                empty.addProperty("done", true);
                empty.addProperty("found", false);
                empty.addProperty("count", 0);
                empty.add("blocks", new JsonArray());
                return empty;
            }
        } else {
            job = this.jobs.get(jobId);
        }
        if (job == null) {
            return ToolJson.error("unknown_scan", "Unknown scan job '" + jobId + "'.",
                    "Start a new scan with scan_blocks; job ids expire after a few minutes.", true);
        }
        if (job.done && job.result != null) return job.result;

        JsonObject pending = ToolJson.ok();
        pending.addProperty("done", false);
        pending.addProperty("pending", true);
        pending.addProperty("job_id", job.id);
        pending.addProperty("count", job.results.size());
        pending.addProperty("scanned_voxels", job.scannedVoxels);
        pending.addProperty("radius", job.radius);
        pending.add("blocks", new JsonArray());
        return pending;
    }

    public void onServerTickEnd() {
        ScanJob job = this.activeJob;
        if (job == null || job.done) return;
        if (System.currentTimeMillis() - job.createdAtMs > MAX_JOB_AGE_MS) {
            job.truncated = true;
            job.done = true;
            finish(job);
            return;
        }
        drainWithBudget(job);
    }

    private void drainWithBudget(ScanJob job) {
        long deadline = System.currentTimeMillis() + MAX_SLICE_MS;
        long voxels = 0L;
        while (!job.done && voxels < MAX_SLICE_VOXELS) {
            voxels += job.stepSlice();
            if ((voxels & 0x3FF) == 0 && System.currentTimeMillis() >= deadline) break;
        }
        if (job.done) {
            finish(job);
        } else if (System.currentTimeMillis() - job.createdAtMs > MAX_JOB_AGE_MS) {
            job.truncated = true;
            job.done = true;
            finish(job);
        }
    }

    private void finish(ScanJob job) {
        if (job == this.activeJob) this.activeJob = null;
        if (job.result == null) {
            JsonObject result = ToolJson.ok();
            result.addProperty("done", true);
            result.addProperty("job_id", job.id);
            result.addProperty("count", job.results.size());
            result.add("blocks", job.toJsonArray());
            result.addProperty("scanned_voxels", job.scannedVoxels);
            result.addProperty("truncated", job.truncated);
            result.addProperty("radius", job.radius);
            result.addProperty("requested_radius", job.requestedRadius);
            result.addProperty("capped_radius", job.radius);
            result.addProperty("radius_was_capped", job.requestedRadius > job.radius);
            result.addProperty("requested_limit", job.requestedLimit);
            result.addProperty("limit_was_capped", job.requestedLimit > job.limit);
            job.result = result;
        }
        this.gate.release();
    }

    private void purgeOldJobs() {
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        for (Map.Entry<String, ScanJob> entry : new ArrayList<>(this.jobs.entrySet())) {
            ScanJob job = entry.getValue();
            if (job.done && job.createdAtMs < cutoff) this.jobs.remove(entry.getKey());
        }
    }

    /**
     * Same shape gate as {@code scan_blocks}; see {@link ScanShape}.
     */
    private static boolean withinShape(String shape, int dx, int dy, int dz, int radius) {
        return ScanShape.parse(shape).contains(dx, dy, dz, radius);
    }

    private static int intArg(JsonObject args, String key, int fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return fallback;
        try {
            return args.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static final class Gate {
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

    private static final class ScanJob {
        final String id = "scan-" + System.nanoTime();
        final long createdAtMs = System.currentTimeMillis();
        final DroneController controller;
        final String filter;
        final int centerX;
        final int centerY;
        final int centerZ;
        final int minY;
        final int maxY;
        final int radius;
        final int requestedRadius;
        final boolean liquidOnly;
        final String shape;
        final int limit;
        final int requestedLimit;
        final boolean includeAir;
        final long totalVoxels;
        int cursorX;
        int cursorY;
        int cursorZ;
        long scannedVoxels;
        boolean done;
        boolean truncated;
        JsonObject result;
        final List<int[]> results = new ArrayList<>();
        final Set<Long> seen = new HashSet<>();

        ScanJob(DroneController controller, EntityPlayerMP player, JsonObject args) {
            this.controller = controller;
            this.filter = args.has("filter") && !args.get("filter").isJsonNull()
                    ? args.get("filter").getAsString() : "ores";
            boolean explicitCenter = args.has("x") && args.has("y") && args.has("z");
            String anchor = args.has("anchor") && !args.get("anchor").isJsonNull()
                    ? args.get("anchor").getAsString() : "drone";
            boolean usePlayer = "player".equalsIgnoreCase(anchor) && player != null;
            double ax = usePlayer ? player.posX
                    : (controller != null && controller.drone != null ? controller.drone.posX : 0);
            double ay = usePlayer ? player.posY
                    : (controller != null && controller.drone != null ? controller.drone.posY : 0);
            double az = usePlayer ? player.posZ
                    : (controller != null && controller.drone != null ? controller.drone.posZ : 0);
            this.centerX = explicitCenter ? intArg(args, "x", 0) : (int) Math.floor(ax);
            this.centerY = explicitCenter ? intArg(args, "y", 0) : (int) Math.floor(ay + 0.5);
            this.centerZ = explicitCenter ? intArg(args, "z", 0) : (int) Math.floor(az);
            this.requestedRadius = Math.max(ScanLimits.MIN_RADIUS,
                    intArg(args, "radius", ScanLimits.DEFAULT_RADIUS));
            this.radius = Math.min(ABSOLUTE_RADIUS_CAP, this.requestedRadius);
            this.shape = args.has("shape") && !args.get("shape").isJsonNull()
                    ? args.get("shape").getAsString().trim().toLowerCase() : "cube";
            this.requestedLimit = Math.max(ScanLimits.MIN_RESULTS,
                    intArg(args, "limit", ScanLimits.DEFAULT_RESULTS));
            this.limit = Math.min(MAX_RESULTS, this.requestedLimit);
            this.includeAir = args.has("include_air") && args.get("include_air").getAsBoolean();
            boolean underground = !explicitCenter && ItemClassifier.isLikelyUndergroundQuery(this.filter);
            this.minY = args.has("min_y") ? Math.max(WorldBounds.MIN_Y, intArg(args, "min_y", WorldBounds.MIN_Y))
                    : (underground ? WorldBounds.MIN_Y
                    : Math.max(WorldBounds.MIN_Y, this.centerY - this.radius));
            this.maxY = args.has("max_y") ? Math.min(WorldBounds.MAX_Y, intArg(args, "max_y", WorldBounds.MAX_Y))
                    : Math.min(WorldBounds.MAX_Y, this.centerY + this.radius);
            this.liquidOnly = this.filter.equalsIgnoreCase("liquid");
            this.cursorX = this.centerX - this.radius;
            this.cursorY = this.minY;
            this.cursorZ = this.centerZ - this.radius;
            this.totalVoxels = (long) (2 * this.radius + 1)
                    * (long) Math.max(0, this.maxY - this.minY + 1)
                    * (long) (2 * this.radius + 1);
            this.done = this.totalVoxels <= 0;
        }

        long stepSlice() {
            if (this.done) return 0L;
            if (this.cursorX > this.centerX + this.radius) {
                this.done = true;
                return 0L;
            }
            int x = this.cursorX;
            int y = this.cursorY;
            int z = this.cursorZ;
            if (++this.cursorZ > this.centerZ + this.radius) {
                this.cursorZ = this.centerZ - this.radius;
                if (++this.cursorY > this.maxY) {
                    this.cursorY = this.minY;
                    ++this.cursorX;
                }
            }
            this.scannedVoxels++;
            if (!WorldBounds.inWorld(y)) return 1L;
            if (this.controller == null || this.controller.drone == null
                    || this.controller.drone.worldObj == null || this.controller.drone.isDead) {
                this.truncated = true;
                this.done = true;
                this.result = ToolJson.error("no_drone", "No active drone for the scan.", null, false);
                return 1L;
            }
            if (this.results.size() >= Math.min(MAX_RESULTS, this.limit)) {
                this.truncated = true;
                this.done = true;
                return 1L;
            }
            try {
                World world = this.controller.drone.worldObj;
                long key = FlightPathfinder.packBlockPos(x, y, z);
                if (!this.seen.add(key)) return 1L;
                int dx = x - this.centerX;
                int dy = y - this.centerY;
                int dz = z - this.centerZ;
                if (!withinShape(this.shape, dx, dy, dz, this.radius)) return 1L;
                boolean air = world.isAirBlock(x, y, z);
                if (air && !this.includeAir) return 1L;
                Block block = world.getBlock(x, y, z);
                int meta = world.getBlockMetadata(x, y, z);
                boolean match = this.liquidOnly
                        ? ItemClassifier.isLiquidBlock(block)
                        : ItemClassifier.matchesMineFilter(block, meta, this.filter);
                if (match) {
                    int distSq = dx * dx + dy * dy + dz * dz;
                    this.results.add(new int[]{x, y, z, meta, distSq});
                }
                if (this.scannedVoxels >= this.totalVoxels) {
                    this.done = true;
                    this.truncated = false;
                }
            } catch (Throwable ignored) {
                this.truncated = true;
            }
            if (this.cursorX > this.centerX + this.radius || this.scannedVoxels >= this.totalVoxels) {
                this.done = true;
            }
            return 1L;
        }

        JsonArray toJsonArray() {
            List<int[]> sorted = new ArrayList<>(this.results);
            Collections.sort(sorted, Comparator.comparingInt(a -> a[4]));
            JsonArray array = new JsonArray();
            int limit = Math.min(MAX_RESULTS, sorted.size());
            World world = this.controller != null && this.controller.drone != null
                    ? this.controller.drone.worldObj : null;
            for (int i = 0; i < limit; i++) {
                int[] hit = sorted.get(i);
                JsonObject block = new JsonObject();
                block.addProperty("x", hit[0]);
                block.addProperty("y", hit[1]);
                block.addProperty("z", hit[2]);
                block.addProperty("meta", hit[3]);
                block.addProperty("dist", Math.round(Math.sqrt(hit[4]) * 10.0) / 10.0);
                // Name + dictionary tags make a sliced scan as informative as
                // the direct one, so a skill can tell "Cassiterite" from "tin".
                if (world != null) {
                    try {
                        Block type = world.getBlock(hit[0], hit[1], hit[2]);
                        if (type != null) {
                            block.addProperty("name", ItemClassifier.getBlockDisplayName(type, hit[3]));
                            String reg = Block.blockRegistry.getNameForObject(type);
                            block.addProperty("registryName", reg != null ? reg : "");
                            JsonArray tags = ItemClassifier.blockOreDictJson(type, hit[3]);
                            if (tags != null) block.add("oreDict", tags);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                array.add(block);
            }
            return array;
        }
    }
}
