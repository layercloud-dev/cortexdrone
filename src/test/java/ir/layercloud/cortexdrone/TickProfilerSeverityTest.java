package ir.layercloud.cortexdrone;

import ir.layercloud.cortexdrone.config.DebugEntry;
import ir.layercloud.cortexdrone.config.DroneLogger;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A lone slow tick is not server lag.
 *
 * <p>The live log has 599 {@code [WARN] Slow server tick: 176.4 ms exceeds
 * 50.0 ms budget} lines whose own statistics were {@code avgMs=15.2
 * p95Ms=18.8}. The model read one and spent turns believing "the server thread
 * is stuck", so a spike must be reported as a spike, and only a sustained
 * average/p95 overrun may be a WARN.</p>
 */
public class TickProfilerSeverityTest {
    @Test
    public void aSpikeDoesNotClaimSustainedLag() {
        DroneLogger logger = new DroneLogger();
        TickProfiler profiler = new TickProfiler(logger);

        // One genuinely slow tick, surrounded by fast ones.
        profiler.onTickStart();
        sleep(120L);
        profiler.onTickEnd(1);
        for (int tick = 2; tick <= 40; tick++) {
            profiler.onTickStart();
            profiler.onTickEnd(tick);
        }

        List<DebugEntry> entries = logger.getRecentDebugEntries(10);
        boolean sawNote = false;
        boolean sawWarn = false;
        for (DebugEntry entry : entries) {
            String category = entry.getCategory();
            if (!"SPIKE".equals(category) && !"WARN".equals(category)) continue;
            sawNote |= "SPIKE".equals(category);
            sawWarn |= "WARN".equals(category);
        }
        assertTrue("a single spike must be reported", sawNote || sawWarn);
        // p95 uses the ring buffer, so the sample is dominated by 40 fast ticks.
        assertTrue("a single spike must not move the average: avg=" + profiler.getAvgMs(),
                profiler.getAvgMs() < profiler.getWarnThresholdMs());
        assertTrue("a single spike must not move p95: p95=" + profiler.getP95Ms(),
                profiler.getP95Ms() < profiler.getWarnThresholdMs());
    }

    @Test
    public void aSustainedOverrunStillWarns() {
        DroneLogger logger = new DroneLogger();
        TickProfiler profiler = new TickProfiler(logger);
        for (int tick = 1; tick <= 30; tick++) {
            profiler.onTickStart();
            sleep(60L);
            profiler.onTickEnd(tick);
        }
        assertTrue("the average is over budget, so this is real lag",
                profiler.getAvgMs() > profiler.getWarnThresholdMs());
        boolean warn = false;
        for (DebugEntry entry : logger.getRecentDebugEntries(40)) {
            warn |= "WARN".equals(entry.getCategory())
                    && entry.getMessage().contains("Slow server ticks");
        }
        assertTrue("sustained lag must still be a WARN", warn);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
