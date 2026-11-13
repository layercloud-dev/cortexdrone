package ir.layercloud.cortexdrone.service.combat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TNT is ordnance: the blast envelope and the charge identity must not drift.
 */
public class TntDropLimitsTest {
    @Test
    public void theSafetyRadiusCoversTheWholeDamageEnvelope() {
        assertTrue("block destruction radius must match a power-4 explosion",
                TntDropLimits.BLAST_BLOCK_RADIUS >= 4);
        assertTrue("entity damage reaches about twice the block radius",
                TntDropLimits.SAFE_DISTANCE >= TntDropLimits.BLAST_BLOCK_RADIUS * 2.0D);
        assertEquals(4.0F, TntDropLimits.BLAST_POWER, 0.001F);
    }

    @Test
    public void fuseAndCountAreClamped() {
        assertEquals(TntDropLimits.MIN_FUSE_TICKS, TntDropLimits.clampFuse(0));
        assertEquals(TntDropLimits.MAX_FUSE_TICKS, TntDropLimits.clampFuse(10_000));
        assertEquals(TntDropLimits.DEFAULT_FUSE_TICKS, TntDropLimits.clampFuse(60));

        assertEquals(TntDropLimits.MIN_COUNT, TntDropLimits.clampCount(-3));
        assertEquals(TntDropLimits.MAX_COUNT, TntDropLimits.clampCount(99));
        assertEquals(TntDropLimits.DEFAULT_COUNT, TntDropLimits.clampCount(1));

        assertEquals(0.0D, TntDropLimits.clampSpread(-1.0D), 0.001D);
        assertEquals(TntDropLimits.MAX_SPREAD, TntDropLimits.clampSpread(50.0D), 0.001D);
    }

    @Test
    public void fuseSecondsAreTicksOverTwenty() {
        assertEquals(3.0D, TntDropLimits.fuseSeconds(60), 0.001D);
        assertEquals(1.0D, TntDropLimits.fuseSeconds(20), 0.001D);
    }

    @Test
    public void tntIsRecognizedByItemRegistryAndDisplayName() {
        assertTrue(TntDrop.matchesTnt("minecraft:tnt"));
        assertTrue(TntDrop.matchesTnt("TNT"));
        assertTrue(TntDrop.matchesTnt("hbm:dynamite"));
        assertTrue(TntDrop.matchesTnt("C4 Charge"));
        assertFalse(TntDrop.matchesTnt("minecraft:stone"));
        assertFalse(TntDrop.matchesTnt(""));
        assertFalse(TntDrop.matchesTnt(null));
    }
}
