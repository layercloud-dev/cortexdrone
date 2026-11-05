package ir.layercloud.cortexdrone.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.model.DroneController;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.player.EntityPlayerMP;
import org.junit.Test;

public class ActionRegistryTest {
    private static final class TestContext extends ActionContextBase {
        private int ticks;
        private boolean completed;
        private boolean cancelled;

        TestContext(DroneController controller, EntityPlayerMP player, String operation) {
            super(controller, player, operation);
        }

        @Override
        protected void onTick() {
            this.ticks++;
        }

        @Override
        protected void onComplete() {
            this.completed = true;
        }

        @Override
        protected void onCancel() {
            this.cancelled = true;
        }

        void finish() {
            complete();
        }
    }

    private static final class TestAction implements Action<TestContext> {
        private final String operation;
        private final boolean retain;
        private final List<TestContext> contexts = new ArrayList<TestContext>();
        private boolean activeDuringHandle;
        private boolean retainedDuringHandle;

        TestAction(String operation, boolean retain) {
            this.operation = operation;
            this.retain = retain;
        }

        @Override
        public String category() { return "test"; }

        @Override
        public String[] operations() { return new String[]{this.operation}; }

        @Override
        public TestContext createContext(DroneController controller, EntityPlayerMP player,
                                         String operation) {
            TestContext context = new TestContext(controller, player, operation);
            this.contexts.add(context);
            return context;
        }

        @Override
        public JsonObject handle(TestContext context, String operation, JsonObject args) {
            this.activeDuringHandle = context.isActive();
            this.retainedDuringHandle = context.isRetained();
            if (this.retain) context.retain();
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            return result;
        }
    }

    @Test
    public void oneShotContextIsDiscardedWhenCallReturns() {
        ActionRegistry registry = new ActionRegistry();
        TestAction action = new TestAction("one_shot", false);
        registry.register(action);

        registry.handle(null, null, "one_shot", new JsonObject());

        assertEquals(1, action.contexts.size());
        assertTrue("context must be active while the action runs", action.activeDuringHandle);
        assertFalse("one-shot context must not be retained", action.retainedDuringHandle);
        assertFalse(action.contexts.get(0).isActive());
        assertTrue(action.contexts.get(0).completed);
        assertEquals(0, registry.activeContextCount(null));
    }

    @Test
    public void retainedContextIsTickedUntilItFinishes() {
        ActionRegistry registry = new ActionRegistry();
        TestAction action = new TestAction("long_running", true);
        registry.register(action);

        registry.handle(null, null, "long_running", new JsonObject());
        TestContext context = action.contexts.get(0);
        assertEquals(1, registry.activeContextCount(null));
        assertTrue(context.isRetained());

        registry.tick(null);
        registry.tick(null);
        assertEquals(2, context.ticks);
        assertEquals(1, registry.activeContextCount(null));

        context.finish();
        registry.tick(null);
        assertEquals(0, registry.activeContextCount(null));
        assertTrue(context.completed);
    }

    @Test
    public void newerInvocationCancelsPreviousContext() {
        ActionRegistry registry = new ActionRegistry();
        TestAction action = new TestAction("restartable", true);
        registry.register(action);

        registry.handle(null, null, "restartable", new JsonObject());
        registry.handle(null, null, "restartable", new JsonObject());

        assertEquals(2, action.contexts.size());
        assertNotSame(action.contexts.get(0), action.contexts.get(1));
        assertFalse(action.contexts.get(0).isActive());
        assertTrue(action.contexts.get(0).cancelled);
        assertTrue(action.contexts.get(1).isActive());
        assertEquals(1, registry.activeContextCount(null));
    }

    @Test
    public void cancelAllReleasesEveryContext() {
        ActionRegistry registry = new ActionRegistry();
        TestAction action = new TestAction("cancellable", true);
        registry.register(action);

        registry.handle(null, null, "cancellable", new JsonObject());
        TestContext context = action.contexts.get(0);
        registry.cancelAll(null);

        assertFalse(context.isActive());
        assertTrue(context.cancelled);
        assertEquals(0, registry.activeContextCount(null));
    }

    @Test
    public void duplicateOperationIsRejected() {
        ActionRegistry registry = new ActionRegistry();
        registry.register(new TestAction("same", false));
        try {
            registry.register(new TestAction("same", false));
            fail("duplicate operation must be rejected");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
            assertTrue(expected.getMessage().contains("same"));
        }
    }
}
