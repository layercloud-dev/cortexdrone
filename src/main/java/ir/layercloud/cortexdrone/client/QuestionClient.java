package ir.layercloud.cortexdrone.client;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ir.layercloud.cortexdrone.network.DroneMapSync;
import ir.layercloud.cortexdrone.network.QuestionAnswerMessage;
import ir.layercloud.cortexdrone.service.question.QuestionLimits;
import ir.layercloud.cortexdrone.service.text.TextSanitizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client entry point for a question dialog.
 *
 * <p>Loaded by name from {@code QuestionRequestHandler} so the network handler
 * never references a client class. The request arrives on the netty thread; the
 * dialog is opened on the client thread, and the answer is sent back on the
 * shared channel.</p>
 */
@SideOnly(Side.CLIENT)
public final class QuestionClient {
    private static volatile long activeId = -1L;

    private QuestionClient() {
    }

    /** Opens the dialog; called reflectively from the network handler. */
    public static void open(final long id, final String question, final List<String> options,
                            final boolean allowFreeText, final int timeoutMs) {
        final Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) return;
        final String safeQuestion = TextSanitizer.dialog(question, QuestionLimits.MAX_QUESTION_CHARS);
        List<String> sanitized = new ArrayList<String>();
        if (options != null) {
            for (String option : options) {
                String value = TextSanitizer.chat(option, QuestionLimits.MAX_OPTION_CHARS);
                if (!value.isEmpty()) sanitized.add(value);
            }
        }
        final List<String> safeOptions = Collections.unmodifiableList(sanitized);
        minecraft.func_152344_a(new Runnable() {
            @Override
            public void run() {
                activeId = id;
                // Lets the server skip the chat fallback for this client.
                DroneMapSync.channel().sendToServer(new QuestionAnswerMessage(id,
                        QuestionAnswerMessage.STATUS_OPENED, "", -1));
                minecraft.displayGuiScreen(new QuestionScreen(id, safeQuestion, safeOptions,
                        allowFreeText, timeoutMs));
            }
        });
    }

    /** Sends one terminal answer for the dialog that is on screen. */
    public static void answer(long id, String text, int optionIndex, boolean cancelled) {
        if (id <= 0L || id != activeId) return;
        activeId = -1L;
        DroneMapSync.channel().sendToServer(new QuestionAnswerMessage(id,
                cancelled ? QuestionAnswerMessage.STATUS_CANCELLED
                        : QuestionAnswerMessage.STATUS_ANSWERED,
                text, optionIndex));
    }
}
