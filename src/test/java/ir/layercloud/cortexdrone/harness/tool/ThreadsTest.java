package ir.layercloud.cortexdrone.harness.tool;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * One server-thread check for the whole harness.
 */
public class ThreadsTest {
    @Test
    public void namedServerThreadIsRecognised() {
        assertTrue(Threads.isServerThread(new Thread("Server thread")));
        assertTrue(Threads.isServerThread(new Thread("Server thread #1")));
    }

    @Test
    public void workerThreadsAreNotServerThreads() {
        assertFalse(Threads.isServerThread(new Thread("CortexDrone-Agent-1")));
        assertFalse(Threads.isServerThread(new Thread("main")));
    }

    @Test
    public void nullIsNeverTheServerThread() {
        assertFalse(Threads.isServerThread((Thread) null));
    }

    @Test
    public void emptyThreadNameIsNeverTheServerThread() {
        Thread thread = new Thread(() -> {
        });
        thread.setName("");
        assertFalse(Threads.isServerThread(thread));
    }

    @Test
    public void overridePropertyWins() {
        System.setProperty(Threads.SERVER_THREAD_OVERRIDE, "true");
        try {
            assertTrue("the override must let a test simulate the server thread",
                    Threads.isServerThread());
        } finally {
            System.clearProperty(Threads.SERVER_THREAD_OVERRIDE);
        }
    }
}
