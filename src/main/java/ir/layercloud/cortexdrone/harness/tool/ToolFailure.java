package ir.layercloud.cortexdrone.harness.tool;

/**
 * A deliberate, model-facing tool failure. Handlers throw this when the call
 * was well-formed but cannot succeed (missing player, offline wiki, blocked
 * path...). The invoker turns it into the standard error envelope without a
 * Java stack trace.
 */
public final class ToolFailure extends RuntimeException {
    private final String code;
    private final String hint;
    private final boolean retryable;

    public ToolFailure(String code, String message, String hint, boolean retryable) {
        super(message);
        this.code = code;
        this.hint = hint;
        this.retryable = retryable;
    }

    public String code() {
        return this.code;
    }

    public String hint() {
        return this.hint;
    }

    public boolean retryable() {
        return this.retryable;
    }
}
