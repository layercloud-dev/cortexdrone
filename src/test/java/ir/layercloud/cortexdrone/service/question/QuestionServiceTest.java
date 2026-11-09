package ir.layercloud.cortexdrone.service.question;

import ir.layercloud.cortexdrone.network.QuestionRequestMessage;
import net.minecraft.entity.player.EntityPlayerMP;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The waiter is the risky part of {@code ask_player}: it blocks an agent thread
 * while a player decides. These tests pin the answer matching, the chat
 * fallback, cancellation and the timeout without a client or a server.
 */
public class QuestionServiceTest {
    private static final class FakeTransport implements QuestionService.Transport {
        private final List<QuestionRequestMessage> sent =
                new CopyOnWriteArrayList<QuestionRequestMessage>();
        private volatile boolean online = true;
        private volatile int prompts;

        @Override
        public void send(EntityPlayerMP player, QuestionRequestMessage request) {
            this.sent.add(request);
        }

        @Override
        public void chatPrompt(EntityPlayerMP player, String question) {
            this.prompts++;
        }

        @Override
        public boolean isOnline(EntityPlayerMP player) {
            return this.online;
        }
    }

    private static QuestionRequestMessage awaitRequest(FakeTransport transport) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!transport.sent.isEmpty()) return transport.sent.get(0);
            Thread.sleep(5L);
        }
        fail("the question was never sent");
        return null;
    }

    private static Thread answerLater(QuestionService service, FakeTransport transport,
                                      String player, String text, int index) {
        Thread thread = new Thread(() -> {
            try {
                QuestionRequestMessage request = awaitRequest(transport);
                service.answer(request.getId(), player, text, index, false);
            } catch (Exception ignored) {
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @Test
    public void theTargetPlayerAnswerCompletesTheQuestion() throws Exception {
        FakeTransport transport = new FakeTransport();
        QuestionService service = new QuestionService(transport, 10L, 60_000L);
        answerLater(service, transport, "alice", "yes", 0);

        QuestionService.Answer answer = service.ask(null, "alice", "Proceed?",
                Arrays.asList("yes", "no"), true, 2_000, null);

        assertEquals(QuestionService.Status.ANSWERED, answer.status());
        assertEquals("yes", answer.text());
        assertEquals(0, answer.optionIndex());
        assertEquals(0, service.activeCount());
        assertNotNull(transport.sent.get(0));
        assertEquals("Proceed?", transport.sent.get(0).getQuestion());
        assertEquals(2, transport.sent.get(0).getOptions().size());
    }

    @Test
    public void anotherPlayerCannotAnswer() throws Exception {
        FakeTransport transport = new FakeTransport();
        QuestionService service = new QuestionService(transport, 10L, 60_000L);
        answerLater(service, transport, "mallory", "yes", 0);

        QuestionService.Answer answer = service.ask(null, "alice", "Proceed?",
                Arrays.asList("yes", "no"), true, 300, null);

        assertEquals(QuestionService.Status.TIMED_OUT, answer.status());
    }

    @Test
    public void aSilentPlayerTimesOut() {
        QuestionService service = new QuestionService(new FakeTransport(), 10L, 60_000L);
        QuestionService.Answer answer = service.ask(null, "alice", "Proceed?",
                Arrays.asList("yes", "no"), true, 100, null);

        assertEquals(QuestionService.Status.TIMED_OUT, answer.status());
        assertFalse(answer.answered());
    }

    @Test
    public void chatIsUsedWhenTheDialogNeverOpens() {
        FakeTransport transport = new FakeTransport();
        QuestionService service = new QuestionService(transport, 10L, 40L);

        QuestionService.Answer answer = service.ask(null, "alice", "Proceed?",
                Arrays.asList("yes", "no"), true, 250, null);

        assertEquals(QuestionService.Status.TIMED_OUT, answer.status());
        assertEquals("one chat fallback prompt", 1, transport.prompts);
    }

    @Test
    public void anOpenedDialogSuppressesTheChatPrompt() throws Exception {
        final FakeTransport transport = new FakeTransport();
        final QuestionService service = new QuestionService(transport, 10L, 60L);
        Thread opener = new Thread(() -> {
            try {
                QuestionRequestMessage request = awaitRequest(transport);
                service.markOpened(request.getId(), "alice");
            } catch (Exception ignored) {
            }
        });
        opener.setDaemon(true);
        opener.start();

        QuestionService.Answer answer = service.ask(null, "alice", "Proceed?",
                Arrays.asList("yes", "no"), true, 250, null);

        assertEquals(QuestionService.Status.TIMED_OUT, answer.status());
        assertEquals(0, transport.prompts);
    }

    @Test
    public void aChatLineIsConsumedAsTheAnswer() throws Exception {
        final FakeTransport transport = new FakeTransport();
        final QuestionService service = new QuestionService(transport, 10L, 60_000L);
        Thread responder = new Thread(() -> {
            try {
                awaitRequest(transport);
                service.answerFromChat("alice", "only at night");
            } catch (Exception ignored) {
            }
        });
        responder.setDaemon(true);
        responder.start();

        QuestionService.Answer answer = service.ask(null, "alice", "When?",
                Arrays.asList("now", "later"), true, 2_000, null);

        assertEquals(QuestionService.Status.ANSWERED, answer.status());
        assertEquals("only at night", answer.text());
        assertEquals(-1, answer.optionIndex());
    }

    @Test
    public void chatLinesAreIgnoredWithoutAPendingQuestion() {
        QuestionService service = new QuestionService(new FakeTransport());
        assertFalse(service.answerFromChat("alice", "hello"));
    }

    @Test
    public void aCancelledTaskAbortsTheWait() {
        QuestionService service = new QuestionService(new FakeTransport(), 10L, 60_000L);
        QuestionService.Answer answer = service.ask(null, "alice", "Proceed?",
                Arrays.asList("yes", "no"), true, 2_000, () -> true);

        assertEquals(QuestionService.Status.ABORTED, answer.status());
        assertEquals(0, service.activeCount());
    }

    @Test
    public void dismissCancelsTheQuestion() {
        FakeTransport transport = new FakeTransport();
        QuestionService service = new QuestionService(transport, 10L, 60_000L);
        Thread responder = new Thread(() -> {
            try {
                QuestionRequestMessage request = awaitRequest(transport);
                service.answer(request.getId(), "alice", "", -1, true);
            } catch (Exception ignored) {
            }
        });
        responder.setDaemon(true);
        responder.start();

        QuestionService.Answer answer = service.ask(null, "alice", "Proceed?",
                Arrays.asList("yes", "no"), true, 2_000, null);

        assertEquals(QuestionService.Status.CANCELLED, answer.status());
    }

    @Test
    public void anOfflinePlayerIsRejectedBeforeWaiting() {
        FakeTransport transport = new FakeTransport();
        transport.online = false;
        QuestionService service = new QuestionService(transport);

        QuestionService.Answer answer = service.ask(null, "alice", "Proceed?",
                Arrays.asList("yes", "no"), true, 2_000, null);

        assertEquals(QuestionService.Status.OFFLINE, answer.status());
        assertTrue(transport.sent.isEmpty());
    }
}
