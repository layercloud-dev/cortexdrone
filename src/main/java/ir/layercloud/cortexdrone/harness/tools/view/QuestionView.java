package ir.layercloud.cortexdrone.harness.tools.view;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import ir.layercloud.cortexdrone.harness.tool.ToolContext;
import ir.layercloud.cortexdrone.harness.tool.ToolInvoker;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.service.question.QuestionLimits;
import ir.layercloud.cortexdrone.service.question.QuestionService;
import ir.layercloud.cortexdrone.service.text.TextSanitizer;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JSON boundary for {@code ask_player}.
 *
 * <p>Resolves the target on the server thread, then blocks the agent thread in
 * {@link QuestionService} until the player answers, dismisses the dialog, the
 * player leaves, the task is cancelled or the timeout expires.</p>
 */
public final class QuestionView {
    /** How long the player lookup may take on the server thread. */
    private static final long LOOKUP_TIMEOUT_MS = 3_000L;

    private final AIDroneManager manager;

    public QuestionView(AIDroneManager manager) {
        this.manager = manager;
    }

    public JsonObject handle(ToolContext context, JsonObject args) {
        QuestionService service = this.manager.getQuestionService();
        if (service == null) {
            return ToolJson.error("question_unavailable",
                    "The question service is not running.",
                    "This is a mod initialization problem; report it.", false);
        }
        String question = TextSanitizer.dialog(ToolArgs.string(args, "question", ""),
                QuestionLimits.MAX_QUESTION_CHARS);
        if (question.isEmpty()) {
            return ToolJson.error("invalid_arguments", "Argument 'question' must not be empty.",
                    "Call ask_player again with one short sentence.", true);
        }
        List<String> options = parseOptions(args);
        boolean allowFreeText = ToolArgs.bool(args, "allow_free_text", true);
        if (options.isEmpty()) allowFreeText = true;
        int timeoutMs = ToolArgs.clampedInt(args, "timeout_ms",
                QuestionLimits.DEFAULT_TIMEOUT_MS,
                QuestionLimits.MIN_TIMEOUT_MS, QuestionLimits.MAX_TIMEOUT_MS);

        String requested = ToolArgs.string(args, "player", "").trim();
        EntityPlayerMP target;
        try {
            target = this.manager.callOnMainThread(
                    () -> resolvePlayer(context, requested), LOOKUP_TIMEOUT_MS);
        } catch (Exception failure) {
            return ToolJson.error("player_lookup_failed",
                    "Could not resolve the player: " + failure,
                    "Check that the name is spelled exactly as it appears in the tab list.", true);
        }
        if (target == null) {
            return ToolJson.error("player_offline",
                    "No online player is available to answer the question.",
                    "The player must be online; ask again while they are connected.", true);
        }

        String name = target.getCommandSenderName();
        QuestionService.Answer answer = service.ask(target, name, question, options,
                allowFreeText, timeoutMs, context != null ? context::cancelled : null);
        this.manager.getLogger().log("QUESTION", null,
                "Player question " + answer.status().name().toLowerCase()
                        + " player=" + name + " elapsedMs=" + answer.elapsedMs(),
                "question=\"" + question + "\" options=" + options.size()
                        + " answer=\"" + answer.text() + "\"");
        return serialize(answer);
    }

    private static EntityPlayerMP resolvePlayer(ToolContext context, String requested) {
        if (!requested.isEmpty()) return CortexDroneMod.findPlayer(requested);
        if (context == null) return null;
        return ToolInvoker.resolvePlayer(context, context.controller());
    }

    private static List<String> parseOptions(JsonObject args) {
        JsonElement element = args != null ? args.get("options") : null;
        if (element == null || element.isJsonNull() || !element.isJsonArray()) {
            return Collections.emptyList();
        }
        List<String> options = new ArrayList<String>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (options.size() >= QuestionLimits.MAX_OPTIONS) break;
            if (item == null || item.isJsonNull() || !item.isJsonPrimitive()) continue;
            String value = TextSanitizer.chat(item.getAsString(), QuestionLimits.MAX_OPTION_CHARS);
            if (value.isEmpty()) continue;
            options.add(value);
        }
        return options;
    }

    private static JsonObject serialize(QuestionService.Answer answer) {
        JsonObject response = ToolJson.ok();
        response.addProperty("answered", answer.answered());
        response.addProperty("elapsed_ms", answer.elapsedMs());
        switch (answer.status()) {
            case ANSWERED:
                response.addProperty("answer", answer.text());
                if (answer.optionIndex() >= 0) {
                    response.addProperty("option_index", answer.optionIndex());
                }
                break;
            case CANCELLED:
                response.addProperty("cancelled", true);
                response.addProperty("hint",
                        "The player dismissed the question. Do not repeat it immediately; "
                                + "continue with the safest interpretation or report the blocker.");
                break;
            case TIMED_OUT:
                response.addProperty("timed_out", true);
                response.addProperty("hint",
                        "The player did not answer in time. Continue with the safest "
                                + "interpretation, or ask again later with a shorter question.");
                break;
            case OFFLINE:
                response.addProperty("cancelled", true);
                response.addProperty("hint", "The player disconnected while the question was open.");
                break;
            case ABORTED:
            default:
                response.addProperty("cancelled", true);
                response.addProperty("hint", "The task was cancelled while waiting for the answer.");
                break;
        }
        return response;
    }
}
