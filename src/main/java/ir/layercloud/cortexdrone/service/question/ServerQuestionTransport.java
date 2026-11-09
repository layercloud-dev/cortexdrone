package ir.layercloud.cortexdrone.service.question;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.network.DroneMapSync;
import ir.layercloud.cortexdrone.network.QuestionRequestMessage;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Minecraft wiring for {@link QuestionService}: network send, chat fallback and
 * an online check.
 *
 * <p>Network and chat writes are queued onto the server thread. The service
 * polls from the agent thread, so touching the connection directly there would
 * be a cross-thread call for no reason.</p>
 */
public final class ServerQuestionTransport implements QuestionService.Transport {
    /** Upper bound for a queued network/chat write. */
    private static final long MAIN_THREAD_TIMEOUT_MS = 5_000L;

    private final AIDroneManager manager;

    public ServerQuestionTransport(AIDroneManager manager) {
        this.manager = manager;
    }

    @Override
    public void send(final EntityPlayerMP player, final QuestionRequestMessage request) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                DroneMapSync.channel().sendTo(request, player);
            }
        });
    }

    @Override
    public void chatPrompt(final EntityPlayerMP player, String question) {
        final String text = chatLine(question);
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                CortexDroneMod.sendMsg(player, text);
            }
        });
        this.manager.getLogger().log("QUESTION", null,
                "Chat fallback prompt sent to " + (player != null ? player.getCommandSenderName() : "?"),
                "text=\"" + text + "\"");
    }

    @Override
    public boolean isOnline(EntityPlayerMP player) {
        return player != null && !player.isDead && player.playerNetServerHandler != null;
    }

    private void runOnMainThread(Runnable task) {
        try {
            this.manager.callOnMainThread(() -> {
                task.run();
                return null;
            }, MAIN_THREAD_TIMEOUT_MS);
        } catch (Exception ignored) {
            // A dropped connection must not fail the whole question.
        }
    }

    /** A one-line chat version of the question. */
    static String chatLine(String question) {
        String text = question == null ? "" : question.trim();
        if (text.length() > QuestionLimits.CHAT_QUESTION_CHARS) {
            text = text.substring(0, QuestionLimits.CHAT_QUESTION_CHARS) + "...";
        }
        return "[CortexDrone] " + text + "  (type your answer in chat)";
    }
}
