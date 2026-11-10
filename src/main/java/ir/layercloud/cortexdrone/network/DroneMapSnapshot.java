package ir.layercloud.cortexdrone.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server-to-client snapshot of every live CortexDrone position.
 *
 * <p>The client needs this snapshot because an OpenComputers drone is only a
 * normal tracked entity: once it leaves the client's entity view distance the
 * client no longer knows where it is. The snapshot makes the map marker keep
 * following the drone across the whole world map. It is small and sent once
 * per second.</p>
 */
@Getter
public class DroneMapSnapshot implements IMessage {
    private List<DroneMarker> markers = Collections.emptyList();

    public DroneMapSnapshot() {
    }

    public DroneMapSnapshot(List<DroneMarker> markers) {
        this.markers = markers != null ? markers : Collections.<DroneMarker>emptyList();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int count = buf.readUnsignedShort();
        List<DroneMarker> list = new ArrayList<DroneMarker>(count);
        for (int i = 0; i < count; i++) {
            int id = buf.readInt();
            int dimension = buf.readInt();
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            String name = ByteBufUtils.readUTF8String(buf);
            String owner = ByteBufUtils.readUTF8String(buf);
            list.add(new DroneMarker(id, name, owner, dimension, x, y, z));
        }
        this.markers = list;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        List<DroneMarker> list = this.markers != null
                ? this.markers
                : Collections.<DroneMarker>emptyList();
        buf.writeShort(list.size());
        for (DroneMarker marker : list) {
            buf.writeInt(marker.getId());
            buf.writeInt(marker.getDimension());
            buf.writeDouble(marker.getX());
            buf.writeDouble(marker.getY());
            buf.writeDouble(marker.getZ());
            ByteBufUtils.writeUTF8String(buf, marker.getName());
            ByteBufUtils.writeUTF8String(buf, marker.getOwner());
        }
    }
}
