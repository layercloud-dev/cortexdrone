package ir.layercloud.cortexdrone.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class DroneMapSnapshotTest {
    @Test
    public void roundTripsMarkers() {
        List<DroneMarker> markers = new ArrayList<DroneMarker>();
        markers.add(new DroneMarker(7, "MHProDev-AI", "MHProDev", -1, 12.5D, 64.0D, -8.25D));
        markers.add(new DroneMarker(9, "", "", 0, 1.0D, 2.0D, 3.0D));

        DroneMapSnapshot source = new DroneMapSnapshot(markers);
        ByteBuf buffer = Unpooled.buffer();
        try {
            source.toBytes(buffer);
            DroneMapSnapshot decoded = new DroneMapSnapshot();
            decoded.fromBytes(buffer);

            List<DroneMarker> actual = decoded.getMarkers();
            assertEquals(2, actual.size());

            DroneMarker first = actual.get(0);
            assertEquals(7, first.getId());
            assertEquals("MHProDev-AI", first.getName());
            assertEquals("MHProDev", first.getOwner());
            assertEquals(-1, first.getDimension());
            assertEquals(12.5D, first.getX(), 0.0D);
            assertEquals(64.0D, first.getY(), 0.0D);
            assertEquals(-8.25D, first.getZ(), 0.0D);

            DroneMarker second = actual.get(1);
            assertEquals(9, second.getId());
            assertEquals("", second.getName());
            assertEquals("", second.getOwner());
            assertEquals(0, second.getDimension());
        } finally {
            buffer.release();
        }
    }
}
