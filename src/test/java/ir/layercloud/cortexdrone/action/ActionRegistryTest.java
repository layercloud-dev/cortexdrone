package ir.layercloud.cortexdrone.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import ir.layercloud.cortexdrone.context.ActionContextBase;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.ActionService;
import net.minecraft.entity.player.EntityPlayerMP;
import org.junit.Test;

public class ActionRegistryTest {
    private static final class TestContext extends ActionContextBase {
        private String result;
        private int ticks;
        private boolean completed;
        private boolean cancelled;

        TestContext(DroneController controller, EntityPlayerMP player, String operation) {
            super(controller, player, operation);
        }

        void result(String result) {
            this.result = result;
        }

        String result() {
            return this.result;
        }

        void countTick() {
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
    }

    private static final class TestService extends ActionService<TestContext, String, String> {
        private final boolean retain;
        private TestContext lastContext;
        private int executions;

        TestService(String operation, boolean retain) {
            super("test", operation);
            this.retain = retain;
        }

        @Override
        protected TestContext createContext(DroneController controller, EntityPlayerMP player,
                                            String operation) {
            this.lastContext = new TestContext(controller, player, operation);
            return this.lastContext;
        }

        @Override
        protected Action<TestContext> createAction(TestContext context, String operation,
                                                   String request) {
            return new Action<TestContext>() {
                @Override
                public void execute(TestContext context) {
                    TestService.this.executions++;
                    context.result("executed:" + operation + ":" + request);
                    if (TestService.this.retain) context.retain();
                }

                @Override
                public void tick(TestContext context) {
                    context.countTick();
                }
            };
        }

        @Override
        protected String result(TestContext context) {
            return context.result();
        }
    }

    @Test
    public void oneShotContextIsDiscardedWhenCallReturns() {
        ActionRegistry registry = new ActionRegistry();
        TestService service = new TestService("one_shot", false);
        registry.register(service);

        String result = service.handle(null, null, "one_shot", "request");

        assertEquals("executed:one_shot:request", result);
        assertEquals(1, service.executions);
        assertFalse(service.lastContext.isActive());
        assertTrue(service.lastContext.completed);
        assertEquals(0, registry.activeContextCount(null));
    }

    @Test
    public void retainedContextIsTickedUntilItFinishes() {
        ActionRegistry registry = new ActionRegistry();
        TestService service = new TestService("long_running", true);
        registry.register(service);

        service.handle(null, null, "long_running", "request");
        TestContext context = service.lastContext;
        assertEquals(1, registry.activeContextCount(null));
        assertTrue(context.isRetained());

        registry.tick(null);
        registry.tick(null);
        assertEquals(2, context.ticks);
        assertEquals(1, registry.activeContextCount(null));

        context.complete();
        registry.tick(null);
        assertEquals(0, registry.activeContextCount(null));
        assertTrue(context.completed);
    }

    @Test
    public void newerInvocationCancelsPreviousContext() {
        ActionRegistry registry = new ActionRegistry();
        TestService service = new TestService("restartable", true);
        registry.register(service);

        service.handle(null, null, "restartable", "first");
        TestContext first = service.lastContext;
        service.handle(null, null, "restartable", "second");
        TestContext second = service.lastContext;

        assertNotSame(first, second);
        assertFalse(first.isActive());
        assertTrue(first.cancelled);
        assertTrue(second.isActive());
        assertEquals(1, registry.activeContextCount(null));
    }

    @Test
    public void cancelAllReleasesEveryContext() {
        ActionRegistry registry = new ActionRegistry();
        TestService service = new TestService("cancellable", true);
        registry.register(service);

        service.handle(null, null, "cancellable", "request");
        TestContext context = service.lastContext;
        registry.cancelAll(null);

        assertFalse(context.isActive());
        assertTrue(context.cancelled);
        assertEquals(0, registry.activeContextCount(null));
    }

    @Test
    public void duplicateOperationIsRejected() {
        ActionRegistry registry = new ActionRegistry();
        registry.register(new TestService("same", false));
        try {
            registry.register(new TestService("same", false));
            fail("duplicate operation must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("same"));
        }
    }

    @Test
    public void unknownOperationIsRejectedBeforeExecution() {
        TestService service = new TestService("known", false);
        try {
            service.handle(null, null, "unknown", "request");
            fail("unknown operation must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unknown"));
        }
        assertEquals(0, service.executions);
    }
}
