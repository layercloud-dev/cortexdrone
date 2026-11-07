package ir.layercloud.cortexdrone.service.navigation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NavigationStateTest {

    @Test
    public void replacedRequestFinishesForItsOwnWaiterOnly() {
        NavigationState state = new NavigationState(0, 0, 0);
        long first = state.beginRequest();
        long second = state.beginRequest();
        state.finishRequest(first, "replaced", "new task");
        assertTrue("the replaced waiter must wake up", state.isRequestFinished(first));
        assertFalse("the newer request is still running", state.isRequestFinished(second));

        state.finishRequest(second, "arrived", "");
        assertTrue(state.isRequestFinished(second));
        assertEquals("arrived", state.finishedOutcome);
    }

    @Test
    public void aStaleRequestCannotOverwriteANewerOutcome() {
        NavigationState state = new NavigationState(0, 0, 0);
        long first = state.beginRequest();
        long second = state.beginRequest();
        state.finishRequest(second, "arrived", "");
        state.finishRequest(first, "failed", "late failure");
        assertEquals("arrived", state.finishedOutcome);
        assertEquals("", state.finishedError);
    }

    @Test
    public void beginRequestClearsThePreviousError() {
        NavigationState state = new NavigationState(0, 0, 0);
        state.error = "old failure";
        state.beginRequest();
        assertEquals("", state.error);
    }
}
