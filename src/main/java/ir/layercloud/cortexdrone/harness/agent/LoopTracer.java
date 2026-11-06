package ir.layercloud.cortexdrone.harness.agent;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import lombok.experimental.UtilityClass;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Structured observability for a task generation. Every turn, tool call and
 * supersession gets one log line; API keys never appear in logs.
 */
@UtilityClass
public final class LoopTracer {
    public static String maskKey(String key) {
        if (key == null || key.isEmpty()) return "<empty>";
        String tail = key.substring(key.length() - 2);
        if (key.length() <= 12) return key.substring(0, Math.min(3, key.length())) + "..." + tail;
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
        logger.log("TRACE", controller,
                "TRACE loopId=" + loopId + " turn=" + turn + " elapsedMs=" + elapsedMs
                        + " failed=" + failed + " content=\""
                        + snippet(content, 180) + "\" tools=\"" + snippet(toolCalls, 180) + "\"",
                "loopId=" + loopId + " turn=" + turn + " elapsedMs=" + elapsedMs);
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
