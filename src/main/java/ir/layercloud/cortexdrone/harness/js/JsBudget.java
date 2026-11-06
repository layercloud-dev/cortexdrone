package ir.layercloud.cortexdrone.harness.js;

import org.mozilla.javascript.EvaluatorException;

import java.util.function.BooleanSupplier;

/**
 * Per-execution budget: wall clock plus cooperative cancellation. Rhino calls
 * {@link #check()} from its instruction observer, so an infinite loop or a
 * script that outlives the task is terminated instead of wedging a thread.
 */
final class JsBudget {
    private final long deadlineMs;
    private final BooleanSupplier cancelled;
    private volatile boolean tripped;
    private volatile String reason;

    JsBudget(long deadlineMs, BooleanSupplier cancelled) {
        this.deadlineMs = deadlineMs;
        this.cancelled = cancelled;
    }

    void check() {
        if (this.tripped) throw new EvaluatorException(this.reason);
        if (this.cancelled != null && this.cancelled.getAsBoolean()) {
            this.tripped = true;
            this.reason = "Script cancelled: the task generation was superseded.";
            throw new EvaluatorException(this.reason);
        }
        if (System.currentTimeMillis() > this.deadlineMs) {
            this.tripped = true;
            this.reason = "Script time budget exceeded.";
            throw new EvaluatorException(this.reason);
        }
    }

    boolean tripped() {
        return this.tripped;
    }

    String reason() {
        return this.reason;
    }
}
