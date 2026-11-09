package ir.layercloud.cortexdrone.network;

import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import ir.layercloud.cortexdrone.service.question.QuestionService;

/**
 * Registers the question dialog messages on the shared CortexDrone channel.
 *
 * <p>Discriminators 0 is the map snapshot; 1 is the client-bound question and
 * 2 is the client's answer. The service is bound once the manager exists, so
 * the answer handler can complete the waiter without a static singleton of the
 * whole mod.</p>
 */
public final class QuestionNetwork {
    private static final int REQUEST_ID = 1;
    private static final int ANSWER_ID = 2;

    private static QuestionService service;

    private QuestionNetwork() {
    }

    public static void register() {
        SimpleNetworkWrapper channel = DroneMapSync.channel();
        channel.registerMessage(QuestionRequestHandler.class, QuestionRequestMessage.class,
                REQUEST_ID, Side.CLIENT);
        channel.registerMessage(QuestionAnswerHandler.class, QuestionAnswerMessage.class,
                ANSWER_ID, Side.SERVER);
    }

    public static void bind(QuestionService value) {
        service = value;
    }

    public static QuestionService service() {
        return service;
    }
}
