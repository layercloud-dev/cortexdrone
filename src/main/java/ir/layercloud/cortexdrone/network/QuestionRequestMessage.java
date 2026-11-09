package ir.layercloud.cortexdrone.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import ir.layercloud.cortexdrone.service.question.QuestionLimits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server to client: open a question dialog.
 *
 * <p>The payload is clamped on both sides. A client only ever renders what
 * this message carries, so an over-long question cannot overflow the panel or
 * the chat fallback line.</p>
 */
public class QuestionRequestMessage implements IMessage {
    private long id;
    private String question = "";
    private List<String> options = Collections.emptyList();
    private boolean allowFreeText = true;
    private int timeoutMs = QuestionLimits.DEFAULT_TIMEOUT_MS;

    /** FML instantiates messages reflectively. */
    public QuestionRequestMessage() {
    }

    public QuestionRequestMessage(long id, String question, List<String> options,
                                  boolean allowFreeText, int timeoutMs) {
        this.id = id;
        this.question = clamp(question, QuestionLimits.MAX_QUESTION_CHARS);
        this.options = clampOptions(options);
        this.allowFreeText = allowFreeText;
        this.timeoutMs = timeout(timeoutMs);
    }

    public long getId() {
        return this.id;
    }

    public String getQuestion() {
        return this.question;
    }

    public List<String> getOptions() {
        return this.options;
    }

    public boolean isAllowFreeText() {
        return this.allowFreeText;
    }

    public int getTimeoutMs() {
        return this.timeoutMs;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.id = buf.readLong();
        this.question = clamp(ByteBufUtils.readUTF8String(buf), QuestionLimits.MAX_QUESTION_CHARS);
        int count = Math.min(buf.readUnsignedByte(), QuestionLimits.MAX_OPTIONS);
        List<String> read = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            read.add(clamp(ByteBufUtils.readUTF8String(buf), QuestionLimits.MAX_OPTION_CHARS));
        }
        this.options = Collections.unmodifiableList(read);
        this.allowFreeText = buf.readBoolean();
        this.timeoutMs = timeout(buf.readInt());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.id);
        ByteBufUtils.writeUTF8String(buf, clamp(this.question, QuestionLimits.MAX_QUESTION_CHARS));
        buf.writeByte(this.options.size());
        for (String option : this.options) {
            ByteBufUtils.writeUTF8String(buf, clamp(option, QuestionLimits.MAX_OPTION_CHARS));
        }
        buf.writeBoolean(this.allowFreeText);
        buf.writeInt(timeout(this.timeoutMs));
    }

    private static int timeout(int value) {
        return Math.max(QuestionLimits.MIN_TIMEOUT_MS,
                Math.min(QuestionLimits.MAX_TIMEOUT_MS, value));
    }

    private static String clamp(String text, int max) {
        String value = text == null ? "" : text.trim();
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static List<String> clampOptions(List<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        List<String> copy = new ArrayList<String>(Math.min(values.size(), QuestionLimits.MAX_OPTIONS));
        for (int i = 0; i < values.size() && i < QuestionLimits.MAX_OPTIONS; i++) {
            String option = clamp(values.get(i), QuestionLimits.MAX_OPTION_CHARS);
            if (!option.isEmpty()) copy.add(option);
        }
        return Collections.unmodifiableList(copy);
    }
}
