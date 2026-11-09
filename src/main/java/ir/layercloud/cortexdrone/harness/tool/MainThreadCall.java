package ir.layercloud.cortexdrone.harness.tool;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runs one task on the server thread and waits for it with a deadline.
 *
 * <p>The subtle part is what happens after the deadline: the queued task is
 * still sitting in the server queue. If it ran anyway, a non-idempotent tool
 * ({@code place_block}, {@code move_item}) would execute <em>after</em> the
 * model was told the call timed out and could retry, duplicating the effect.
 * The queued body therefore checks an abandoned flag before doing any work.</p>
 */
public final class MainThreadCall {
    /** Outcome of one main-thread dispatch. */
    public static final class Result<T> {
        private final T value;
        private final Throwable error;
        private final boolean timedOut;
        private final boolean skipped;

        private Result(T value, Throwable error, boolean timedOut, boolean skipped) {
            this.value = value;
            this.error = error;
            this.timedOut = timedOut;
            this.skipped = skipped;
        }

        public T value() {
            return this.value;
        }

        public Throwable error() {
            return this.error;
        }

        /** True when the deadline passed before the task finished. */
        public boolean timedOut() {
            return this.timedOut;
        }

        /** True when the task was abandoned and deliberately never ran. */
        public boolean skipped() {
            return this.skipped;
        }

        public boolean succeeded() {
            return !this.timedOut && !this.skipped && this.error == null;
        }
    }

    private MainThreadCall() {
    }

    /**
     * Queues {@code task} and waits up to {@code timeoutMs}.
     *
     * @param queue     schedules a runnable on the server thread
     * @param task      the work to perform
     * @param timeoutMs deadline in milliseconds
     */
    public static <T> Result<T> call(Consumer<Runnable> queue, Callable<T> task, long timeoutMs) {
        if (queue == null || task == null) {
            return new Result<>(null, new IllegalArgumentException("queue and task are required"),
                    false, true);
        }
        AtomicReference<T> value = new AtomicReference<>(null);
        AtomicReference<Throwable> error = new AtomicReference<>(null);
        AtomicBoolean abandoned = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        queue.accept(() -> {
            if (abandoned.get()) return;
            try {
                value.set(task.call());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });

        boolean finished;
        try {
            finished = done.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            abandoned.set(true);
            return new Result<>(null, interrupted, true, true);
        }
        if (!finished) {
            // Tell the queued body to do nothing when the server thread reaches it.
            abandoned.set(true);
            return new Result<>(null, null, true, true);
        }
        return new Result<>(value.get(), error.get(), false, false);
    }
}
