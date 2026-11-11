package ir.layercloud.cortexdrone.harness.tools;

import org.junit.Test;

import static ir.layercloud.cortexdrone.harness.tools.WorldTools.withinShape;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The advanced scan shapes must mean exactly what their names say.
 */
public class ScanShapeTest {
    private static final int R = 10;

    @Test
    public void cubeKeepsTheWholeBox() {
        assertTrue(withinShape("cube", 10, 10, 10, R));
        assertTrue(withinShape("cube", 0, 0, 0, R));
        assertTrue(withinShape(null, 10, 10, 10, R));
    }

    @Test
    public void sphereKeepsAFairRadius() {
        assertTrue(withinShape("sphere", 10, 0, 0, R));
        assertTrue(withinShape("sphere", 5, 5, 5, R));
        // Corner of the box is outside a sphere of the same radius.
        assertFalse(withinShape("sphere", 10, 10, 10, R));
    }

    @Test
    public void cylinderIgnoresVerticalDistance() {
        assertTrue(withinShape("cylinder", 10, 0, 0, R));
        // Straight up/down at the centre stays inside the column.
        assertTrue(withinShape("cylinder", 0, 99, 0, R));
        // Horizontally outside is outside, whatever the height.
        assertFalse(withinShape("cylinder", 11, 0, 0, R));
    }

    @Test
    public void shellKeepsOnlyTheOuterLayer() {
        assertTrue(withinShape("shell", 10, 0, 0, R));
        assertFalse("centre must be hollow", withinShape("shell", 0, 0, 0, R));
        assertFalse("inside the shell is excluded", withinShape("shell", 3, 0, 0, R));
    }

    @Test
    public void unknownShapeFallsBackToCube() {
        assertTrue(withinShape("banana", 10, 10, 10, R));
    }

    @Test
    public void shapeNamesAreCaseInsensitive() {
        assertFalse(withinShape("SPHERE", 10, 10, 10, R));
        assertTrue(withinShape("CYLINDER", 0, 50, 0, R));
    }
}
