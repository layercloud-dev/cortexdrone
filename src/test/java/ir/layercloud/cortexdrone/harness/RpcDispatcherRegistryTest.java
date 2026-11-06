package ir.layercloud.cortexdrone.harness;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.rpc.RpcHandler;
import net.minecraft.entity.player.EntityPlayerMP;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Dispatcher registration invariants. These exercise the exact merge rule used
 * by {@link RpcDispatcher} without loading Minecraft world classes.
 */
public class RpcDispatcherRegistryTest {
    private static final class StubHandler implements RpcHandler {
        private final ToolRegistry tools;
        private final String answer;

        StubHandler(String answer, String... names) {
            this.answer = answer;
            this.tools = new ToolRegistry();
            for (String name : names) this.tools.register(name, name + " tool");
        }

        @Override
        public ToolRegistry toolRegistry() {
            return this.tools;
        }

        @Override
        public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String method, JsonObject args) {
            JsonObject res = new JsonObject();
            res.addProperty("answer", this.answer);
            return res;
        }
    }

    @Test
    public void duplicateToolAcrossHandlersIsRejected() {
        Map<String, RpcHandler> registry = new HashMap<>();
        RpcHandler first = new StubHandler("first", "state");
        RpcHandler second = new StubHandler("second", "state");

        RpcDispatcher.registerInto(registry, first);
        try {
            RpcDispatcher.registerInto(registry, second);
            fail("duplicate tool must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("state"));
        }
        assertSame(first, registry.get("state"));
    }

    @Test
    public void eachHandlerOwnsItsNames() {
        Map<String, RpcHandler> registry = new HashMap<>();
        RpcHandler world = new StubHandler("world", "get_block", "scan_blocks");
        RpcHandler blocks = new StubHandler("blocks", "break_block");
        RpcDispatcher.registerInto(registry, world);
        RpcDispatcher.registerInto(registry, blocks);

        assertSame(world, registry.get("get_block"));
        assertSame(world, registry.get("scan_blocks"));
        assertSame(blocks, registry.get("break_block"));
        assertNull(registry.get("inspect"));
        assertNull(registry.get("transfer"));
    }

    @Test
    public void reRegisteringTheSameHandlerIsIdempotent() {
        Map<String, RpcHandler> registry = new HashMap<>();
        RpcHandler handler = new StubHandler("one", "state", "cancel");
        RpcDispatcher.registerInto(registry, handler);
        RpcDispatcher.registerInto(registry, handler);
        assertEquals(2, registry.size());
    }
}
