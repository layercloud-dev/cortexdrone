package ir.layercloud.cortexdrone.config;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * The batched log flush must not clear a pending interrupt.
 *
 * <p>Regression: {@code flushPendingLog} slept and swallowed
 * {@link InterruptedException} without restoring the thread's interrupt flag, so
 * a shutdown request could be lost while the flush thread continued.</p>
 */
public class DroneLoggerInterruptTest {
    @Test
    public void anInterruptedFlushDelayRestoresTheInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            DroneLogger.pauseForFlush(1L);
            assertTrue("the flush delay must not clear a pending interrupt",
                    Thread.currentThread().isInterrupted());
        } finally {
            assertTrue(Thread.interrupted());
        }
    }
}
