package ir.layercloud.cortexdrone.manager.chunk.impl;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Chunk re-plan throttling.
 *
 * <p>Extracted from {@code ChunkLoadingManager}. Two bugs are pinned here: a
 * fresh cursor must be due immediately (the old {@code Long.MIN_VALUE} sentinel
 * overflowed when subtracted), and a stationary drone must not rebuild its plan
 * every tick.</p>
 */
public class DroneChunkCursorTest {
    @Test
    public void aFreshCursorIsDueImmediately() {
        DroneChunkCursor cursor = new DroneChunkCursor();
        assertTrue("a new drone must plan on its first tick",
                cursor.shouldReplan(0, 0, 0, 0L, false));
        assertTrue("a fresh cursor must also plan when a ticket exists",
                cursor.shouldReplan(0, 0, 0, 0L, true));
    }

    @Test
    public void movingToANewChunkTriggersAReplan() {
        DroneChunkCursor cursor = new DroneChunkCursor();
        cursor.recordPlan(0, 0, 0, 100L);
        assertTrue(cursor.shouldReplan(1, 0, 0, 101L, true));
        assertTrue(cursor.shouldReplan(0, 1, 0, 101L, true));
        assertTrue(cursor.shouldReplan(0, 0, 1, 101L, true));
    }

    @Test
    public void aStationaryDroneWaitsForTheInterval() {
        DroneChunkCursor cursor = new DroneChunkCursor();
        cursor.recordPlan(0, 0, 0, 100L);
        assertFalse("no move and inside the interval", cursor.shouldReplan(0, 0, 0, 101L, true));
        assertTrue("the interval has elapsed",
                cursor.shouldReplan(0, 0, 0,
                        100L + DroneChunkCursor.PLAN_INTERVAL_TICKS, true));
    }

    @Test
    public void aMissingTicketForcesAReplan() {
        DroneChunkCursor cursor = new DroneChunkCursor();
        cursor.recordPlan(0, 0, 0, 100L);
        assertTrue("a drone without a ticket must replan", cursor.shouldReplan(0, 0, 0, 101L, false));
    }

    @Test
    public void loggingIsThrottledButNotWhenSomethingChanged() {
        DroneChunkCursor cursor = new DroneChunkCursor();
        assertTrue("the first plan always logs", cursor.shouldLog(5, 0, 0, 100L));
        assertFalse("an unchanged plan inside the interval is silent",
                cursor.shouldLog(5, 0, 0, 101L));
        assertTrue("a changed count logs immediately", cursor.shouldLog(6, 0, 0, 102L));
        assertTrue("loading chunks logs immediately", cursor.shouldLog(6, 0, 3, 103L));
        assertTrue("the interval elapses",
                cursor.shouldLog(6, 0, 0, 103L + DroneChunkCursor.LOG_INTERVAL_TICKS));
    }
}
