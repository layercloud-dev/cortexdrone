package ir.layercloud.cortexdrone.harness.markdown;

/**
 * The last write failure for a store instance, per thread.
 *
 * <p>The stores used to log a warning and still return the saved entry, so the
 * tool reply said {@code saved:true} while the file was never written. A
 * caller now records the failure and reads it immediately after the save, on
 * the same agent thread, and turns it into a {@code *_not_persisted} error.</p>
 *
 * <p>Thread-local rather than an instance field: one {@code MemoryStore} is
 * shared by every drone, and a global flag let one drone's failure be reported
 * by another drone's successful save.</p>
 */
public final class WriteStatus {
    private static final ThreadLocal<String> LAST_FAILURE = new ThreadLocal<>();

    private WriteStatus() {
    }

    /**
     * Records why the last write failed, or clears the slot.
     */
    public static void failed(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            LAST_FAILURE.remove();
        } else {
            LAST_FAILURE.set(reason.trim());
        }
    }

    /**
     * The reason the last write on this thread failed, or {@code null}.
     */
    public static String lastFailure() {
        return LAST_FAILURE.get();
    }

    /**
     * Reads and clears the failure, so the caller cannot reuse a stale one.
     */
    public static String takeFailure() {
        String value = LAST_FAILURE.get();
        LAST_FAILURE.remove();
        return value;
    }

    /**
     * Clears the failure slot for a fresh operation.
     */
    public static void clear() {
        LAST_FAILURE.remove();
    }

    /**
     * A short, chat-safe reason naming the file that could not be written.
     */
    public static String reason(String what, String path) {
        return what + " could not be written to " + path;
    }
}
