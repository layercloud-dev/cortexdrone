package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.harness.tool.*;
import ir.layercloud.cortexdrone.harness.tools.view.QuestionView;
import ir.layercloud.cortexdrone.service.question.QuestionLimits;

import java.util.Arrays;
import java.util.List;

/**
 * Player dialogue: the one tool that can stop and ask.
 *
 * <p>The dialog is a client-side GUI; the chat prompt is the fallback for a
 * vanilla client. Either way the call blocks on the agent thread, so the server
 * ticks and the drone keeps its state while the player decides.</p>
 */
public final class QuestionTools implements ToolProvider {
    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("ask_player")
                    .description("Ask the player a question and wait for their answer. Opens a dialog "
                            + "on their client (a chat prompt is used if the client has no CortexDrone) "
                            + "and returns the button they picked or the text they typed. Use it only "
                            + "when the answer changes what you do: an ambiguous destructive choice, a "
                            + "target only the player knows, or a preference you cannot verify in the "
                            + "world. Offer 2.." + QuestionLimits.MAX_OPTIONS + " short options for quick "
                            + "picks; a free-text field stays available unless allow_free_text is false. "
                            + "The call waits up to timeout_ms and then returns answered:false, so do not "
                            + "retry it in a loop.")
                    .parameters(ToolSchema.object()
                            .requiredString("question", "One short question, max "
                                    + QuestionLimits.MAX_QUESTION_CHARS + " characters.")
                            .optionalStringArray("options", "Quick answers, up to "
                                    + QuestionLimits.MAX_OPTIONS + " short labels.")
                            .optionalBoolean("allow_free_text",
                                    "Offer a text field beside the option buttons. Default true.", true)
                            .optionalInt("timeout_ms", "How long to wait for an answer, "
                                            + QuestionLimits.MIN_TIMEOUT_MS + ".."
                                            + QuestionLimits.MAX_TIMEOUT_MS + " ms.",
                                    QuestionLimits.MIN_TIMEOUT_MS, QuestionLimits.MAX_TIMEOUT_MS,
                                    QuestionLimits.DEFAULT_TIMEOUT_MS)
                            .optionalString("player",
                                    "Player to ask; defaults to the player who gave the task."))
                    .affinity(ToolAffinity.ASYNC)
                    .timeoutMs(QuestionLimits.MAX_TIMEOUT_MS)
                    .capabilities(ToolCapability.PRESENTATION)
                    .build()
    );

    private final QuestionView view;

    public QuestionTools(AIDroneManager manager) {
        this.view = new QuestionView(manager);
    }

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    @Override
    public void register(ToolRegistry registry) {
        registry.bind("ask_player", getClass().getSimpleName(),
                (context, arguments) -> this.view.handle(context, arguments));
    }
}
