package ir.layercloud.cortexdrone.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import ir.layercloud.cortexdrone.service.question.QuestionLimits;
import ir.layercloud.cortexdrone.service.text.TextSanitizer;
import lombok.Getter;

/**
 * Client to server: one lifecycle event for a question dialog.
 *
 * <p>{@link #STATUS_OPENED} lets the server suppress the chat fallback when the
 * player has the CortexDrone client. {@link #STATUS_ANSWERED} and
 * {@link #STATUS_CANCELLED} finish the wait.</p>
 */
@Getter
public class QuestionAnswerMessage implements IMessage {
    /**
     * The dialog is on screen; the client understands the question.
     */
    public static final byte STATUS_OPENED = 1;
    /**
     * The player picked an option or typed an answer.
     */
    public static final byte STATUS_ANSWERED = 2;
    /**
     * The player dismissed the dialog.
     */
    public static final byte STATUS_CANCELLED = 3;

    private long id;
    private byte status = STATUS_CANCELLED;
    private String answer = "";
    private int optionIndex = -1;

    /**
     * FML instantiates messages reflectively.
     */
    public QuestionAnswerMessage() {
    }

    public QuestionAnswerMessage(long id, byte status, String answer, int optionIndex) {
        this.id = id;
        this.status = status;
        this.answer = TextSanitizer.chat(answer, QuestionLimits.MAX_ANSWER_CHARS);
        this.optionIndex = optionIndex;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.id = buf.readLong();
        this.status = buf.readByte();
        this.answer = TextSanitizer.chat(ByteBufUtils.readUTF8String(buf),
                QuestionLimits.MAX_ANSWER_CHARS);
        this.optionIndex = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.id);
        buf.writeByte(this.status);
        ByteBufUtils.writeUTF8String(buf,
                TextSanitizer.chat(this.answer, QuestionLimits.MAX_ANSWER_CHARS));
        buf.writeInt(this.optionIndex);
    }
}
