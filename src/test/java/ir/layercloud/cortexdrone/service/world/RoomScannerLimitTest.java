package ir.layercloud.cortexdrone.service.world;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * The room flood-fill caps come from the scan policy owner.
 *
 * <p>Regression: {@code RoomScanner} carried a bare {@code 4500}-voxel cap and
 * a bare {@code 3}-block opening probe.</p>
 */
public class RoomScannerLimitTest {
    private static final String SOURCE =
            "src/main/java/ir/layercloud/cortexdrone/service/world/RoomScanner.java";

    private static String source() throws Exception {
        return new String(Files.readAllBytes(Paths.get(SOURCE)), StandardCharsets.UTF_8);
    }

    @Test
    public void roomScannerReadsTheScanPolicy() throws Exception {
        assertTrue(source().contains("ScanLimits.MAX_ROOM_VOXELS"));
        assertTrue(source().contains("ScanLimits.ROOM_PROBE_RADIUS"));
    }

    @Test
    public void theRoomCapsAreStable() {
        assertEquals(4500, ScanLimits.MAX_ROOM_VOXELS);
        assertEquals(3, ScanLimits.ROOM_PROBE_RADIUS);
    }
}
