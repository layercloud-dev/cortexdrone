package ir.layercloud.cortexdrone.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import ir.layercloud.cortexdrone.service.question.QuestionLimits;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The question messages cross the FML channel, so both ends must read exactly
 * what the other wrote. The payload also clamps text so a long question or
 * answer cannot overflow the dialog or the chat fallback.
 */
public class QuestionMessageTest {
    @Test
    public void requestRoundTrips() {
        QuestionRequestMessage source = new QuestionRequestMessage(42L, "Where?",
                Arrays.asList("here", "there"), true, 30_000);
        ByteBuf buffer = Unpooled.buffer();
        try {
            source.toBytes(buffer);
            QuestionRequestMessage decoded = new QuestionRequestMessage();
            decoded.fromBytes(buffer);

            assertEquals(42L, decoded.getId());
            assertEquals("Where?", decoded.getQuestion());
            assertEquals(Arrays.asList("here", "there"), decoded.getOptions());
            assertTrue(decoded.isAllowFreeText());
            assertEquals(30_000, decoded.getTimeoutMs());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void answerRoundTrips() {
        QuestionAnswerMessage source = new QuestionAnswerMessage(7L,
                QuestionAnswerMessage.STATUS_ANSWERED, "yes, but only at night", 1);
        ByteBuf buffer = Unpooled.buffer();
        try {
            source.toBytes(buffer);
            QuestionAnswerMessage decoded = new QuestionAnswerMessage();
            decoded.fromBytes(buffer);

            assertEquals(7L, decoded.getId());
            assertEquals(QuestionAnswerMessage.STATUS_ANSWERED, decoded.getStatus());
            assertEquals("yes, but only at night", decoded.getAnswer());
            assertEquals(1, decoded.getOptionIndex());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void overlongPayloadsAreClampedOnWrite() {
        StringBuilder longQuestion = new StringBuilder();
        for (int i = 0; i < QuestionLimits.MAX_QUESTION_CHARS + 50; i++) longQuestion.append('q');
        StringBuilder longAnswer = new StringBuilder();
        for (int i = 0; i < QuestionLimits.MAX_ANSWER_CHARS + 50; i++) longAnswer.append('a');

        ByteBuf buffer = Unpooled.buffer();
        try {
            new QuestionRequestMessage(1L, longQuestion.toString(),
                    Collections.singletonList("only"), true, 5_000).toBytes(buffer);
            QuestionRequestMessage request = new QuestionRequestMessage();
            request.fromBytes(buffer);
            assertEquals(QuestionLimits.MAX_QUESTION_CHARS, request.getQuestion().length());

            new QuestionAnswerMessage(1L, QuestionAnswerMessage.STATUS_ANSWERED,
                    longAnswer.toString(), -1).toBytes(buffer);
            QuestionAnswerMessage answer = new QuestionAnswerMessage();
            answer.fromBytes(buffer);
            assertEquals(QuestionLimits.MAX_ANSWER_CHARS, answer.getAnswer().length());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void timeoutAndOptionsAreBounded() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            new QuestionRequestMessage(1L, "q", Arrays.asList("a", "b", "c", "d", "e", "f"),
                    false, 1).toBytes(buffer);
            QuestionRequestMessage decoded = new QuestionRequestMessage();
            decoded.fromBytes(buffer);

            assertEquals(QuestionLimits.MAX_OPTIONS, decoded.getOptions().size());
            assertEquals(QuestionLimits.MIN_TIMEOUT_MS, decoded.getTimeoutMs());
            assertFalse(decoded.isAllowFreeText());
        } finally {
            buffer.release();
        }
    }
}
