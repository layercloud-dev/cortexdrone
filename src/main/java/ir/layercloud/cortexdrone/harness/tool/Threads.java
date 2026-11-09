package ir.layercloud.cortexdrone.harness.tool;

/**
 * Server-thread detection in one place.
 *
 * <p>Forge 1.7.10 names the tick thread {@code "Server thread"}. The harness
 * needs to know whether it is already on that thread before queueing work, and
 * three copies of a raw {@code getName().contains(...)} check meant a renamed
 * thread (a wrapper, a proxy, a test harness) silently routed work to the wrong
 * thread. The override property makes the check explicit and testable.</p>
 */
public final class Threads {
    /** Property used to force the server-thread answer in tests. */
    public static final String SERVER_THREAD_OVERRIDE = "cortexdrone.serverThread";

    private static final String SERVER_THREAD_NAME = "Server thread";

    private Threads() {
    }

    /** True when the calling thread is the Minecraft server tick thread. */
    public static boolean isServerThread() {
        String override = System.getProperty(SERVER_THREAD_OVERRIDE);
        if (override != null) return Boolean.parseBoolean(override);
        return isServerThread(Thread.currentThread());
    }

    /** True when {@code thread} is the server tick thread. */
    public static boolean isServerThread(Thread thread) {
        if (thread == null) return false;
        String name = thread.getName();
        return name != null && name.contains(SERVER_THREAD_NAME);
    }
}
