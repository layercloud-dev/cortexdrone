package ir.layercloud.cortexdrone;

import ir.layercloud.cortexdrone.config.DroneLogger;
import java.util.Arrays;

/**
 * Lightweight server-tick profiler for CortexDrone TPS diagnosis.
 *
 * Measures the interval from FML START to END phases (onServerTickStart to
 * onServerTickEnd), which approximates the full 50 ms budget slot. Keeps a
 * ring buffer of the last 600 ticks (30 s at 20 TPS) and exposes avg/p95.
 * Warn threshold and cooldown are dynamic via environment variables so they
 * can be retuned without a code change.
 */
public final class TickProfiler {
    private static final int SAMPLE_CAPACITY = 600;
    private static final double DEFAULT_WARN_MS = 50.0;
    private static final long DEFAULT_COOLDOWN_MS = 30000L;
    private static final String ENV_WARN_MS = "CORTEXDRONE_TICK_WARN_MS";
    private static final String ENV_COOLDOWN_MS = "CORTEXDRONE_WARN_COOLDOWN_MS";

    private final DroneLogger logger;
    private volatile long tickStartNanos = 0L;
    private final long[] sampleNanos = new long[SAMPLE_CAPACITY];
    private int sampleCount = 0;
    private int sampleIndex = 0;
    private volatile long lastDurationNanos = 0L;
    private volatile long ticksMeasured = 0L;
    private volatile long overThresholdCount = 0L;
    private volatile long lastWarnWallMs = 0L;
    private volatile double warnThresholdMs = DEFAULT_WARN_MS;
    private volatile long warnCooldownMs = DEFAULT_COOLDOWN_MS;

    public TickProfiler(DroneLogger logger) {
        this.logger = logger;
        refreshFromEnv();
    }

    public void onTickStart() {
        this.tickStartNanos = System.nanoTime();
    }

    public void onTickEnd(int tickCounter) {
        long start = this.tickStartNanos;
        if (start == 0L) return;
        if (tickCounter > 0 && tickCounter % 100 == 0) {
            refreshFromEnv();
        }
        long durationNanos = System.nanoTime() - start;
        this.lastDurationNanos = durationNanos;
        this.ticksMeasured++;
        synchronized (this) {
            this.sampleNanos[this.sampleIndex] = durationNanos;
            this.sampleIndex = (this.sampleIndex + 1) % SAMPLE_CAPACITY;
            if (this.sampleCount < SAMPLE_CAPACITY) {
                this.sampleCount++;
            }
        }
        double durationMs = durationNanos / 1000000.0;
        if (durationMs > this.warnThresholdMs) {
            this.overThresholdCount++;
            long nowWall = System.currentTimeMillis();
            if (nowWall - this.lastWarnWallMs >= this.warnCooldownMs) {
                this.lastWarnWallMs = nowWall;
                if (this.logger != null) {
                    this.logger.log("WARN", null,
                        String.format("Slow server tick: %.1f ms exceeds %.1f ms budget (tick=%d)",
                            durationMs, this.warnThresholdMs, tickCounter),
                        String.format("avgMs=%.1f p95Ms=%.1f maxMs=%.1f samples=%d over=%d",
                            getAvgMs(), getP95Ms(), getMaxMs(), getSampleCount(), this.overThresholdCount));
                }
            }
        }
    }

    public void refreshFromEnv() {
        this.warnThresholdMs = envDouble(ENV_WARN_MS, DEFAULT_WARN_MS);
        this.warnCooldownMs = envLong(ENV_COOLDOWN_MS, DEFAULT_COOLDOWN_MS);
    }

    public double getWarnThresholdMs() {
        return this.warnThresholdMs;
    }

    public long getWarnCooldownMs() {
        return this.warnCooldownMs;
    }

    public double getLastMs() {
        return this.lastDurationNanos / 1000000.0;
    }

    public double getAvgMs() {
        long[] snap = snapshot();
        if (snap.length == 0) return 0.0;
        long sum = 0L;
        for (long v : snap) {
            sum += v;
        }
        return (sum / (double) snap.length) / 1000000.0;
    }

    public double getP95Ms() {
        long[] snap = snapshot();
        if (snap.length == 0) return 0.0;
        Arrays.sort(snap);
        int idx = (int) Math.ceil(0.95 * snap.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= snap.length) idx = snap.length - 1;
        return snap[idx] / 1000000.0;
    }

    public double getMaxMs() {
        long[] snap = snapshot();
        if (snap.length == 0) return 0.0;
        long max = snap[0];
        for (long v : snap) {
            if (v > max) max = v;
        }
        return max / 1000000.0;
    }

    public double getEstimatedTps() {
        double avgMs = getAvgMs();
        if (avgMs <= 0.0) return 20.0;
        return Math.min(20.0, 1000.0 / avgMs);
    }

    public long getTicksMeasured() {
        return this.ticksMeasured;
    }

    public long getOverThresholdCount() {
        return this.overThresholdCount;
    }

    public long getLastWarnWallMs() {
        return this.lastWarnWallMs;
    }

    public int getSampleCount() {
        synchronized (this) {
            return this.sampleCount;
        }
    }

    private long[] snapshot() {
        synchronized (this) {
            if (this.sampleCount == 0) return new long[0];
            return Arrays.copyOf(this.sampleNanos, this.sampleCount);
        }
    }

    private static double envDouble(String key, double fallback) {
        String val = envValue(key);
        if (val == null) return fallback;
        try {
            double parsed = Double.parseDouble(val);
            return parsed > 0.0 && !Double.isNaN(parsed) ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long envLong(String key, long fallback) {
        String val = envValue(key);
        if (val == null) return fallback;
        try {
            long parsed = Long.parseLong(val);
            return parsed > 0L ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String envValue(String key) {
        try {
            String val = System.getenv(key);
            if (val != null && !val.trim().isEmpty()) {
                return val.trim();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
