package ir.layercloud.cortexdrone.manager.chunk;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChunkLoadPolicyTest {
    @Test
    public void opensTenSecondsAfterLogin() {
        ChunkLoadPolicy policy = new ChunkLoadPolicy();
        policy.tick(true, 1000L);
        assertFalse(policy.isLoadAllowed());
        policy.tick(true, 1000L + ChunkLoadPolicy.LOGIN_DELAY_MS - 1L);
        assertFalse(policy.isLoadAllowed());
        policy.tick(true, 1000L + ChunkLoadPolicy.LOGIN_DELAY_MS);
        assertTrue(policy.isLoadAllowed());
    }

    @Test
    public void keepsAiChunksForTenSecondsAfterLogout() {
        ChunkLoadPolicy policy = new ChunkLoadPolicy();
        policy.tick(true, 1000L);
        policy.tick(true, 1000L + ChunkLoadPolicy.LOGIN_DELAY_MS);
        assertTrue(policy.isLoadAllowed());

        policy.tick(false, 20000L);
        assertFalse(policy.isLoadAllowed());
        assertFalse(policy.isUnloadAll());
        policy.tick(false, 20000L + ChunkLoadPolicy.LOGOUT_GRACE_MS - 1L);
        assertFalse(policy.isUnloadAll());
        policy.tick(false, 20000L + ChunkLoadPolicy.LOGOUT_GRACE_MS);
        assertTrue(policy.isUnloadAll());
    }

    @Test
    public void nextLoginWaitsTenSecondsAgain() {
        ChunkLoadPolicy policy = new ChunkLoadPolicy();
        policy.tick(true, 1000L);
        policy.tick(true, 1000L + ChunkLoadPolicy.LOGIN_DELAY_MS);
        assertTrue(policy.isLoadAllowed());

        policy.tick(false, 20000L);
        policy.tick(true, 21000L);
        assertFalse(policy.isLoadAllowed());
        policy.tick(true, 21000L + ChunkLoadPolicy.LOGIN_DELAY_MS);
        assertTrue(policy.isLoadAllowed());
    }

    @Test
    public void noPlayerAtStartupUnloadsAfterGrace() {
        ChunkLoadPolicy policy = new ChunkLoadPolicy();
        policy.tick(false, 5000L);
        assertFalse(policy.isLoadAllowed());
        assertFalse(policy.isUnloadAll());
        policy.tick(false, 5000L + ChunkLoadPolicy.LOGOUT_GRACE_MS);
        assertTrue(policy.isUnloadAll());
    }
}
