package ir.layercloud.cortexdrone.harness.agent;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.DroneLogger;
import lombok.experimental.UtilityClass;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Structured observability for a task generation. Every turn, tool call and
 * supersession gets one log line; API keys never appear in logs.
 */
@UtilityClass
@SuppressWarnings("unused")
public final class LoopTracer {
    public static String maskKey(String key) {
        if (key == null || key.isEmpty()) return "<empty>";
        // A short key still needs a tail and a prefix; substring(length - 2)
        // crashed the LLM-failure log path for one-character keys.
        String tail = key.length() >= 2 ? key.substring(key.length() - 2) : key;
        if (key.length() <= 6) return key;
        if (key.length() <= 12) return key.substring(0, 3) + "..." + tail;
        return key.substring(0, 8) + "..." + tail + " (len=" + key.length() + ")";
    }

    public static String snippet(String value, int max) {
        if (value == null) return "";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ')
                .replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= max) return oneLine;
        return oneLine.substring(0, Math.max(0, max - 3)) + "...";
    }

    public static void traceStart(DroneLogger logger, DroneController controller,
                                  int loopId, String userPrompt) {
        logger.log("TRACE", controller,
                "TRACE loopId=" + loopId + " turn=0 event=start userPrompt=\""
                        + snippet(userPrompt, 160) + "\"",
                "loopId=" + loopId + " event=start");
    }

    public static void traceTurn(DroneLogger logger, DroneController controller, int loopId,
                                 int turn, long elapsedMs, boolean failed,
                                 String content, String toolCalls) {
        traceTurn(logger, controller, loopId, turn, elapsedMs, failed, content, toolCalls, "");
    }

    /**
     * Same trace with the provider's {@code finish_reason}. A thinking model
     * that exhausts max_tokens reports {@code finish_reason=length} with an
     * empty message; without this field the log looks like the model simply
     * stopped thinking, which hid the real cause.
     */
    public static void traceTurn(DroneLogger logger, DroneController controller, int loopId,
                                 int turn, long elapsedMs, boolean failed,
                                 String content, String toolCalls, String finishReason) {
        logger.log("TRACE", controller,
                "TRACE loopId=" + loopId + " turn=" + turn + " elapsedMs=" + elapsedMs
                        + " failed=" + failed
                        + " finish=" + (finishReason == null || finishReason.isEmpty()
                        ? "unknown" : finishReason)
                        + " content=\"" + snippet(content, 180) + "\" tools=\""
                        + snippet(toolCalls, 180) + "\"",
                "loopId=" + loopId + " turn=" + turn + " elapsedMs=" + elapsedMs
                        + " finish=" + finishReason);
    }

    public static void logSuperseded(DroneLogger logger, DroneController controller, int loopId,
                                     int turnReached, String userPrompt, int supersededByLoopId) {
        logger.log("SUPERSEDED", controller,
                "Superseded loopId=" + loopId + " turnReached=" + turnReached
                        + " userPrompt=\"" + snippet(userPrompt, 120) + "\""
                        + " supersededByLoopId=" + supersededByLoopId,
                "loopId=" + loopId + " turnReached=" + turnReached);
    }

    public static void say(final AIDroneManager manager, final EntityPlayerMP player,
                           final DroneController controller, final String message) {
        if (manager == null || message == null || message.isEmpty()) return;
        try {
            manager.getMod().queueMain(() -> manager.droneSay(player, controller, message));
        } catch (Exception ignored) {
        }
    }
}
