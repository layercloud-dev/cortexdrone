package ir.layercloud.cortexdrone.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import ir.layercloud.cortexdrone.service.question.QuestionLimits;

/**
 * Client to server: one lifecycle event for a question dialog.
 *
 * <p>{@link #STATUS_OPENED} lets the server suppress the chat fallback when the
 * player has the CortexDrone client. {@link #STATUS_ANSWERED} and
 * {@link #STATUS_CANCELLED} finish the wait.</p>
 */
public class QuestionAnswerMessage implements IMessage {
    /** The dialog is on screen; the client understands the question. */
    public static final byte STATUS_OPENED = 1;
    /** The player picked an option or typed an answer. */
    public static final byte STATUS_ANSWERED = 2;
    /** The player dismissed the dialog. */
    public static final byte STATUS_CANCELLED = 3;

    private long id;
    private byte status = STATUS_CANCELLED;
    private String answer = "";
    private int optionIndex = -1;

    /** FML instantiates messages reflectively. */
    public QuestionAnswerMessage() {
    }

    public QuestionAnswerMessage(long id, byte status, String answer, int optionIndex) {
        this.id = id;
        this.status = status;
        this.answer = answer != null ? answer.trim() : "";
        if (this.answer.length() > QuestionLimits.MAX_ANSWER_CHARS) {
            this.answer = this.answer.substring(0, QuestionLimits.MAX_ANSWER_CHARS);
        }
        this.optionIndex = optionIndex;
    }

    public long getId() {
        return this.id;
    }

    public byte getStatus() {
        return this.status;
    }

    public String getAnswer() {
        return this.answer;
    }

    public int getOptionIndex() {
        return this.optionIndex;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.id = buf.readLong();
        this.status = buf.readByte();
        this.answer = ByteBufUtils.readUTF8String(buf);
        if (this.answer != null && this.answer.length() > QuestionLimits.MAX_ANSWER_CHARS) {
            this.answer = this.answer.substring(0, QuestionLimits.MAX_ANSWER_CHARS);
        }
        this.optionIndex = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.id);
        buf.writeByte(this.status);
        ByteBufUtils.writeUTF8String(buf, this.answer == null ? "" : this.answer);
        buf.writeInt(this.optionIndex);
    }
}
