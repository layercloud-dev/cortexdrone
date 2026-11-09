package ir.layercloud.cortexdrone.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import ir.layercloud.cortexdrone.service.question.QuestionService;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Server half of a question answer.
 *
 * <p>FML delivers this on the netty thread. Completing the waiter's future is
 * thread-safe, and the answering player is taken from the live connection
 * rather than from the payload, so one player cannot answer another's
 * question.</p>
 */
public class QuestionAnswerHandler implements IMessageHandler<QuestionAnswerMessage, IMessage> {
    @Override
    public IMessage onMessage(QuestionAnswerMessage message, MessageContext ctx) {
        if (ctx.side != Side.SERVER) return null;
        QuestionService service = QuestionNetwork.service();
        if (service == null) return null;
        EntityPlayerMP player = ctx.getServerHandler().playerEntity;
        if (player == null) return null;
        String name = player.getCommandSenderName();
        switch (message.getStatus()) {
            case QuestionAnswerMessage.STATUS_OPENED:
                service.markOpened(message.getId(), name);
                break;
            case QuestionAnswerMessage.STATUS_ANSWERED:
                service.answer(message.getId(), name, message.getAnswer(),
                        message.getOptionIndex(), false);
                break;
            case QuestionAnswerMessage.STATUS_CANCELLED:
                service.answer(message.getId(), name, "", -1, true);
                break;
            default:
                break;
        }
        return null;
    }
}
