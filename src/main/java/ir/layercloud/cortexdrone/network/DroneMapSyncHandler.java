package ir.layercloud.cortexdrone.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import ir.layercloud.cortexdrone.client.ClientDroneMarkers;

/**
 * Client half of the map snapshot. FML invokes this on the netty thread, so
 * the immutable snapshot is only published to a volatile field; the actual
 * Xaero waypoint work happens on the client tick thread.
 */
public class DroneMapSyncHandler implements IMessageHandler<DroneMapSnapshot, IMessage> {
    @Override
    public IMessage onMessage(DroneMapSnapshot message, MessageContext ctx) {
        if (ctx.side == Side.CLIENT) {
            ClientDroneMarkers.accept(message.getMarkers());
        }
        return null;
    }
}
