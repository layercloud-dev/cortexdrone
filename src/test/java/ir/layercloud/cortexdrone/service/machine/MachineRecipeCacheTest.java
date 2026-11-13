package ir.layercloud.cortexdrone.service.machine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The recipe cache is shared by every drone's agent thread.
 *
 * <p>Eight concurrent lookups used to run the reflective read eight times. The
 * cache is now guarded, so the first thread fills it and the rest consume it.</p>
 */
public class MachineRecipeCacheTest {
    @Test
    public void concurrentLookupsLoadTheHooksOnce() throws Exception {
        final AtomicInteger loads = new AtomicInteger();
        MachineRecipeService service = new MachineRecipeService(null);
        injectOneHook(service, loads);

        int threads = 8;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final List<Throwable> failures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    service.getMachineRecipesForFilter("", "");
                } catch (Throwable t) {
                    synchronized (failures) {
                        failures.add(t);
                    }
                } finally {
                    done.countDown();
                }
            });
            thread.start();
        }
        start.countDown();
        assertTrue("threads must finish", done.await(15, TimeUnit.SECONDS));
        assertTrue("no lookup may throw: " + failures, failures.isEmpty());
        assertEquals("the hook must be read once, not once per thread", 1, loads.get());
    }

    private static void injectOneHook(MachineRecipeService service, final AtomicInteger loads) {
        try {
            java.lang.reflect.Field hooks = MachineRecipeService.class.getDeclaredField("hooks");
            hooks.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<MachineRecipeHook> list = (List<MachineRecipeHook>) hooks.get(service);
            list.clear();
            list.add(new MachineRecipeHook() {
                @Override
                public boolean isAvailable() {
                    return true;
                }

                @Override
                public List<MachineRecipeService.NormalizedRecipe> load() {
                    loads.incrementAndGet();
                    return new ArrayList<>();
                }
            });
        } catch (Exception e) {
            throw new AssertionError("could not install the counting hook", e);
        }
    }
}
