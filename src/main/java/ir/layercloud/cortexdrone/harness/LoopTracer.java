package ir.layercloud.cortexdrone.harness;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.drone.DroneController;
import lombok.experimental.UtilityClass;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Single place that formats loop observability lines so a silent stop is
 * impossible to miss: every turn gets a TRACE line, supersession is both
 * logged and spoken, and error/trace snippets never leak a full API key.
 */
@UtilityClass
public final class LoopTracer {

    public static String maskKey(String key) {
        if (key == null || key.isEmpty()) return "<empty>";
        if (key.length() <= 12) return key.substring(0, 3) + "..." + key.substring(key.length() - 2);
        return key.substring(0, 8) + "..." + key.substring(key.length() - 2) + " (len=" + key.length() + ")";
    }

    public static String snippet(String value, int max) {
        if (value == null) return "";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= max) return oneLine;
        return oneLine.substring(0, Math.max(0, max - 3)) + "...";
    }

    public static void traceStart(DroneLogger logger, DroneController ctrl, int loopId, String userPrompt) {
        logger.log("TRACE", ctrl,
                "TRACE loopId=" + loopId + " turn=0 elapsedMs=0 planNull=false thought=\"\" code=\"\" userPrompt=\""
                        + snippet(userPrompt, 120) + "\"",
                "loopId=" + loopId + " event=start");
    }

    public static void traceTurn(DroneLogger logger, DroneController ctrl, int loopId, int turn, long elapsedMs,
                                 boolean planNull, boolean malformed, String thought, String code) {
        logger.log("TRACE", ctrl,
                "TRACE loopId=" + loopId + " turn=" + turn + " elapsedMs=" + elapsedMs + " planNull=" + planNull
                        + " malformed=" + malformed + " thought=\"" + snippet(thought, 120) + "\" code=\""
                        + snippet(code, 120) + "\"",
                "loopId=" + loopId + " turn=" + turn + " elapsedMs=" + elapsedMs + " snippet thought=\""
                        + snippet(thought, 80) + "\" code=\" " + snippet(code, 80) + "\"");
    }

    public static void logSuperseded(DroneLogger logger, DroneController ctrl, int loopId, int turnReached,
                                     String userPrompt) {
        logger.log("SUPERSEDED", ctrl,
                "Superseded loopId=" + loopId + " turnReached=" + turnReached + " userPrompt=\""
                        + snippet(userPrompt, 120) + "\" supersededByLoopId=" + ctrl.activeLoopId.get(),
                "loopId=" + loopId + " turnReached=" + turnReached);
    }

    public static void saySuperseded(final AIDroneManager manager, final EntityPlayerMP player,
                                     final DroneController ctrl, final String userPrompt) {
        final String msg = "previous task superseded by your new command (\" " + snippet(userPrompt, 80)
                + " \") — starting the new one.";
        say(manager, player, ctrl, msg);
    }

    public static void say(final AIDroneManager manager, final EntityPlayerMP player,
                           final DroneController ctrl, final String message) {
        if (manager == null || message == null || message.isEmpty()) return;
        try {
            manager.getMod().queueMain(new Runnable() {
                @Override
                public void run() {
                    manager.droneSay(player, ctrl, message);
                }
            });
        } catch (Exception ignored) {
        }
    }

    public static void sayError(final AIDroneManager manager, final EntityPlayerMP player,
                                final DroneController ctrl, final String detail) {
        String safeDetail = snippet(detail, 400);
        if (safeDetail.isEmpty()) safeDetail = "no response from LLM gateway";
        say(manager, player, ctrl, "AI backend error: " + safeDetail
                + ". Fix the API key / gateway then re-send your instruction.");
    }
}
