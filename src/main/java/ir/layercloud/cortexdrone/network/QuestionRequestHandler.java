package ir.layercloud.cortexdrone.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;

import java.util.List;

/**
 * Client half of a question.
 *
 * <p>The dialog class is resolved by name, exactly like the Xaero hook, so this
 * handler stays loadable on a dedicated server where
 * {@code net.minecraft.client.Minecraft} is absent. If the client has no
 * CortexDrone, opening fails silently and the service's chat fallback takes
 * over.</p>
 */
public class QuestionRequestHandler implements IMessageHandler<QuestionRequestMessage, IMessage> {
    private static final String CLIENT = "ir.layercloud.cortexdrone.client.QuestionClient";

    @Override
    public IMessage onMessage(QuestionRequestMessage message, MessageContext ctx) {
        if (ctx.side != Side.CLIENT) return null;
        openDialog(message);
        return null;
    }

    private static void openDialog(QuestionRequestMessage message) {
        try {
            Class<?> client = Class.forName(CLIENT);
            client.getMethod("open", long.class, String.class, List.class, boolean.class, int.class)
                    .invoke(null, message.getId(), message.getQuestion(), message.getOptions(),
                            message.isAllowFreeText(), message.getTimeoutMs());
        } catch (Throwable ignored) {
            // Vanilla client or a server without the GUI: the chat fallback asks.
        }
    }
}
