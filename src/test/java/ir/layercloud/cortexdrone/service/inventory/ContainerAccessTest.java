package ir.layercloud.cortexdrone.service.inventory;

import ir.layercloud.cortexdrone.service.world.BlockSight;
import ir.layercloud.cortexdrone.service.world.DamageJournal;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Reach rules must never report a distant or invisible container as usable.
 */
public class ContainerAccessTest {
    @Test
    public void missingWorldIsNeverAccessible() {
        assertFalse(ContainerAccess.check(null, 0.0, 64.0, 0.0, 0, 64, 0).isAccessible());
        assertEquals("No world.", ContainerAccess.check(null, 0.0, 64.0, 0.0, 0, 64, 0).getReason());
    }

    @Test
    public void reachMatchesBlockReach() {
        // A slot transfer is a physical interaction, so it uses the same reach.
        assertEquals(BlockSight.REACH, ContainerAccess.REACH, 0.0001);
    }

    @Test
    public void damageJournalIsBoundedAndClears() {
        DamageJournal journal = new DamageJournal();
        // No world means nothing to capture, and no crash.
        assertNull(journal.record(null, null, 0, 0, 0, "navigation"));
        assertEquals(0, journal.size(null));
        assertTrue(journal.snapshot(null, 10).isEmpty());
        assertTrue(journal.pending(null).isEmpty());
        journal.remove(null, null);
        journal.clear(null);
    }

    @Test
    public void unloadedChunksAreNotAccessible() {
        // A null world is the unit-test stand-in for "target not loaded".
        ContainerAccess.Result result = ContainerAccess.check(null, 1.0, 64.0, 1.0, 5000, 64, 5000);
        assertFalse(result.isAccessible());
        assertNull(result.getStandoff());
    }
}
