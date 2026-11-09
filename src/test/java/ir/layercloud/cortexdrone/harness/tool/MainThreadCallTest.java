package ir.layercloud.cortexdrone.harness.tool;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * A timed-out main-thread call must never run later.
 *
 * <p>Regression: the caller only stopped <em>waiting</em> on timeout; the
 * queued lambda still executed on the next tick. A retried non-idempotent tool
 * (place/break/move) then applied twice.</p>
 */
public class MainThreadCallTest {
    @Test
    public void aTimedOutTaskIsAbandonedBeforeItRuns() {
        List<Runnable> serverQueue = new ArrayList<>();
        AtomicBoolean executed = new AtomicBoolean(false);

        MainThreadCall.Result<String> result = MainThreadCall.call(serverQueue::add, () -> {
            executed.set(true);
            return "done";
        }, 5L);

        assertTrue("the caller must see a timeout", result.timedOut());
        assertFalse(result.succeeded());

        // The server thread finally drains its queue.
        for (Runnable runnable : serverQueue) runnable.run();
        assertFalse("an abandoned task must not run after the timeout", executed.get());
    }

    @Test
    public void aTaskThatFinishesInTimeReturnsItsValue() {
        List<Runnable> serverQueue = new ArrayList<>();
        Thread server = new Thread(() -> {
            // Drain on a "server tick" shortly after the call is queued.
            for (int i = 0; i < 200 && serverQueue.isEmpty(); i++) sleep(1L);
            for (Runnable runnable : new ArrayList<>(serverQueue)) runnable.run();
        });
        server.start();

        MainThreadCall.Result<Integer> result = MainThreadCall.call(serverQueue::add, () -> 42, 2_000L);

        assertTrue(result.succeeded());
        assertFalse(result.timedOut());
        assertEquals(Integer.valueOf(42), result.value());
    }

    @Test
    public void aThrowingTaskReportsTheError() {
        List<Runnable> serverQueue = new ArrayList<>();
        Thread server = new Thread(() -> {
            for (int i = 0; i < 200 && serverQueue.isEmpty(); i++) sleep(1L);
            for (Runnable runnable : new ArrayList<>(serverQueue)) runnable.run();
        });
        server.start();

        MainThreadCall.Result<Integer> result = MainThreadCall.call(serverQueue::add, () -> {
            throw new IllegalStateException("boom");
        }, 2_000L);

        assertFalse(result.succeeded());
        assertNotNull(result.error());
        assertEquals("boom", result.error().getMessage());
    }

    @Test
    public void nullArgumentsAreRejectedWithoutThrowing() {
        MainThreadCall.Result<Object> result = MainThreadCall.call(null, () -> null, 10L);
        assertFalse(result.succeeded());
        assertTrue(result.skipped());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
