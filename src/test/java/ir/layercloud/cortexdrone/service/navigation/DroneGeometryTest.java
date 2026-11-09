package ir.layercloud.cortexdrone.service.navigation;

import org.junit.Test;

import static org.junit.Assert.*;

/** The hover geometry must be one shared definition. */
public class DroneGeometryTest {
    @Test
    public void aimPointIsTheCellCentreAtAimHeight() {
        assertEquals(10.5, DroneGeometry.aimX(10), 0.0001);
        // aimY targets a block/entity inside the cell, so it uses AIM_OFFSET
        // (0.35), not the hover offset (0.65) used for path cells.
        assertEquals(64.35, DroneGeometry.aimY(64), 0.0001);
        assertEquals(-3.5, DroneGeometry.aimZ(-4), 0.0001);
    }

    @Test
    public void hoverAndAimOffsetsAreDistinct() {
        assertNotEquals("a path cell and a targeted block are aimed differently",
                DroneGeometry.HOVER_OFFSET, DroneGeometry.AIM_OFFSET);
        assertTrue(DroneGeometry.AIM_OFFSET < DroneGeometry.HOVER_OFFSET);
    }

    @Test
    public void hoverClearsTheCellFloor() {
        assertTrue("the drone must sit above the block it targets",
                DroneGeometry.HOVER_OFFSET > 0.0);
        assertTrue("but inside the block above", DroneGeometry.HOVER_OFFSET < 1.0);
    }

    @Test
    public void cellCentreIsTheMiddleOfTheBlock() {
        assertEquals(0.5, DroneGeometry.CELL_CENTER, 0.0001);
    }
}
