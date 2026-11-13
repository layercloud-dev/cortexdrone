package ir.layercloud.cortexdrone.harness.memory;

import org.junit.Test;

import static org.junit.Assert.*;

public class MemoryCaptureTest {
    @Test
    public void recognizesRememberStatements() {
        assertTrue(MemoryCapture.isMemoryMessage("remember this is my base"));
        assertTrue(MemoryCapture.isMemoryMessage("keep in mind the farm is north"));
        assertTrue(MemoryCapture.isMemoryMessage("don't forget the portal"));
        assertFalse(MemoryCapture.isMemoryMessage("mine 8 iron ore"));
        assertTrue(MemoryCapture.containsMemoryCue("by the way remember the base"));
    }

    @Test
    public void classifiesLocationsAndLabels() {
        assertTrue(MemoryCapture.isLocation("this is my base"));
        assertTrue(MemoryCapture.isLocation("the iron farm is at 120 64 -40"));
        assertFalse(MemoryCapture.isLocation("I prefer torches"));
        assertEquals("base", MemoryCapture.label("this is my base"));
        assertEquals("iron farm", MemoryCapture.label("remember the iron farm is at 120 64 -40"));
        assertEquals("north mine", MemoryCapture.label("call this the north mine"));
    }

    @Test
    public void parsesExplicitCoordinatesAndDimensions() {
        int[] coords = MemoryCapture.explicitCoordinates("remember the mine is at -120 32 400");
        assertNotNull(coords);
        assertArrayEquals(new int[]{-120, 32, 400}, coords);
        assertEquals(Integer.valueOf(-1), MemoryCapture.explicitDimension("remember the portal in the nether"));
        assertEquals(Integer.valueOf(0), MemoryCapture.explicitDimension("this is my overworld base"));
    }

    @Test
    public void stripsRememberPrefix() {
        assertEquals("bring torches", MemoryCapture.canonicalText("remember to bring torches"));
        assertEquals("this is my base", MemoryCapture.canonicalText("remember this is my base"));
    }

    @Test
    public void hereIsAWordNotASubstringOfEverywhereElse() {
        // "where"/"there"/"somewhere" must not turn a fact into a location that
        // pins the player's current position.
        assertFalse(MemoryCapture.isLocation("remember where I died"));
        assertFalse(MemoryCapture.isLocation("remember the spare pickaxes are over there"));
        assertFalse(MemoryCapture.isLocation("remember there are spiders somewhere nearby"));
        assertFalse(MemoryCapture.isLocation("remember this sphere is dangerous"));

        assertTrue("a standalone 'here' still means this position",
                MemoryCapture.isLocation("remember this ore vein is here"));
        assertTrue(MemoryCapture.isLocation("remember here"));
        assertTrue(MemoryCapture.isLocation("save this location"));
    }
}
