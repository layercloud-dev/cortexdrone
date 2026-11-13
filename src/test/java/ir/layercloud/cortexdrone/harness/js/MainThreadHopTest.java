package ir.layercloud.cortexdrone.harness.js;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@code main(fn)} must hop to the server thread, never run the callback on the
 * calling agent thread.
 *
 * <p>Live evidence in {@code logs/fml-client-3.log}: HodgePodge logged
 * "Off-thread read from CortexDrone-Agent-1277281352500 - serving from
 * snapshot" with {@code JsEngine$5.call(JsEngine.java:214)} on the stack. The
 * condition was inverted, so an agent-thread script executed its {@code main}
 * body on the agent thread and read live world chunks from it.</p>
 */
public class MainThreadHopTest {
    @Test
    public void theScriptMainHelperHopsOffTheAgentThread() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/ir/layercloud/cortexdrone/harness/js/JsEngine.java").toPath()),
                StandardCharsets.UTF_8);

        assertTrue("the helper must know the server thread", source.contains("onServerThread()"));
        assertTrue("an on-server caller may run inline",
                source.contains("if (onServerThread()) return fn.call("));
        assertFalse("the old inverted guard must be gone",
                source.contains("if (!onServerThread()) return fn.call("));
        assertTrue("an agent-thread caller must go through the main-thread bridge",
                source.contains("manager.callOnMainThread("));
    }

    @Test
    public void theBridgeIsUsedBeforeAnyLiveWorldAccess() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/ir/layercloud/cortexdrone/harness/js/JsEngine.java").toPath()),
                StandardCharsets.UTF_8);
        int inline = source.indexOf("if (onServerThread()) return fn.call(");
        int bridge = source.indexOf("manager.callOnMainThread(");
        assertTrue("the on-server inline branch must exist: " + inline, inline > 0);
        assertTrue("the agent-thread bridge branch must exist: " + bridge, bridge > inline);
    }
}
