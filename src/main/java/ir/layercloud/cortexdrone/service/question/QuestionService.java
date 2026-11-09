package ir.layercloud.cortexdrone.service.question;

import ir.layercloud.cortexdrone.network.QuestionRequestMessage;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Asks one player a question and waits for the answer.
 *
 * <p>This is the server-side half of {@code ask_player}. A dialog is pushed to
 * the player's client; if the client never acknowledges it (vanilla client or a
 * dedicated server without the mod), the question is also posted to chat after
 * {@link QuestionLimits#CHAT_FALLBACK_MS}. The waiter runs on the agent thread,
 * so the server keeps ticking while it blocks.</p>
 *
 * <p>All state is per-question and removed in a {@code finally} block, so a
 * cancelled task, a disconnect or a timeout cannot leak a pending dialog.</p>
 */
public final class QuestionService {
    /** Where a question ended up. */
    public enum Status {
        ANSWERED,
        CANCELLED,
        TIMED_OUT,
        OFFLINE,
        ABORTED
    }

    /** Immutable outcome of one question. */
    public static final class Answer {
        private final Status status;
        private final String text;
        private final int optionIndex;
        private final long elapsedMs;

        Answer(Status status, String text, int optionIndex, long elapsedMs) {
            this.status = status;
            this.text = text != null ? text : "";
            this.optionIndex = optionIndex;
            this.elapsedMs = elapsedMs;
        }

        public Status status() {
            return this.status;
        }

        public String text() {
            return this.text;
        }

        public int optionIndex() {
            return this.optionIndex;
        }

        public long elapsedMs() {
            return this.elapsedMs;
        }

        public boolean answered() {
            return this.status == Status.ANSWERED;
        }
    }

    /** The transport that reaches the player; injectable so tests need no client. */
    public interface Transport {
        void send(EntityPlayerMP player, QuestionRequestMessage request);

        void chatPrompt(EntityPlayerMP player, String question);

        boolean isOnline(EntityPlayerMP player);
    }

    private static final class Reply {
        private final Status status;
        private final String text;
        private final int optionIndex;

        private Reply(Status status, String text, int optionIndex) {
            this.status = status;
            this.text = text;
            this.optionIndex = optionIndex;
        }
    }

    private static final class Pending {
        private final String playerName;
        private final List<String> options;
        private final CompletableFuture<Reply> future = new CompletableFuture<Reply>();
        private volatile boolean opened;

        private Pending(String playerName, List<String> options) {
            this.playerName = playerName;
            this.options = options;
        }
    }

    private final Transport transport;
    private final long pollMs;
    private final long chatFallbackMs;
    private final Map<Long, Pending> pending = new ConcurrentHashMap<Long, Pending>();
    private final AtomicLong nextId = new AtomicLong(1L);

    public QuestionService(Transport transport) {
        this(transport, QuestionLimits.POLL_MS, QuestionLimits.CHAT_FALLBACK_MS);
    }

    /** Test seam: shorter polling and chat fallback. */
    public QuestionService(Transport transport, long pollMs, long chatFallbackMs) {
        this.transport = transport;
        this.pollMs = Math.max(10L, pollMs);
        this.chatFallbackMs = Math.max(0L, chatFallbackMs);
    }

    /**
     * Sends the question and blocks until it is answered, dismissed, the player
     * leaves, the caller is cancelled or the timeout expires.
     *
     * @param playerName stable name used to match the answering player
     * @param cancelled  checked every poll; may be {@code null}
     */
    public Answer ask(EntityPlayerMP player, String playerName, String question,
                      List<String> options, boolean allowFreeText, int timeoutMs,
                      BooleanSupplier cancelled) {
        long started = System.currentTimeMillis();
        if (playerName == null || playerName.trim().isEmpty() || !this.transport.isOnline(player)) {
            return result(Status.OFFLINE, "", -1, started);
        }
        cancelFor(playerName);
        long id = this.nextId.getAndIncrement();
        List<String> safeOptions = options == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(options));
        Pending entry = new Pending(playerName, safeOptions);
        this.pending.put(id, entry);
        try {
            this.transport.send(player, new QuestionRequestMessage(id, question, safeOptions,
                    allowFreeText, timeoutMs));
            long deadline = started + Math.max(1, timeoutMs);
            long promptAt = started + this.chatFallbackMs;
            boolean prompted = false;
            while (true) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    return result(Status.ABORTED, "", -1, started);
                }
                long now = System.currentTimeMillis();
                long remaining = deadline - now;
                if (remaining <= 0L) return result(Status.TIMED_OUT, "", -1, started);
                try {
                    Reply reply = entry.future.get(Math.min(this.pollMs, remaining),
                            TimeUnit.MILLISECONDS);
                    return result(reply.status, reply.text, reply.optionIndex, started);
                } catch (TimeoutException keepWaiting) {
                    // Fall through to the fallback/offline checks.
                } catch (ExecutionException failed) {
                    return result(Status.ABORTED, "", -1, started);
                }
                if (!entry.opened && !prompted && now >= promptAt) {
                    prompted = true;
                    this.transport.chatPrompt(player, question);
                }
                if (!this.transport.isOnline(player)) {
                    return result(Status.OFFLINE, "", -1, started);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return result(Status.ABORTED, "", -1, started);
        } finally {
            this.pending.remove(id);
        }
    }

    /** The client opened the dialog; suppress the chat fallback. */
    public void markOpened(long id, String playerName) {
        Pending entry = this.pending.get(id);
        if (entry != null && samePlayer(entry, playerName)) entry.opened = true;
    }

    /** Records the dialog answer. Returns false for a stale id or another player. */
    public boolean answer(long id, String playerName, String text, int optionIndex, boolean cancelled) {
        Pending entry = this.pending.get(id);
        if (entry == null || !samePlayer(entry, playerName)) return false;
        int index = optionIndex >= 0 && optionIndex < entry.options.size() ? optionIndex : -1;
        String clean = text != null ? text.trim() : "";
        if (clean.length() > QuestionLimits.MAX_ANSWER_CHARS) {
            clean = clean.substring(0, QuestionLimits.MAX_ANSWER_CHARS);
        }
        return entry.future.complete(cancelled
                ? new Reply(Status.CANCELLED, "", -1)
                : new Reply(Status.ANSWERED, clean, index));
    }

    /**
     * Accepts the next chat line from a player with a pending question.
     *
     * @return true when the line was consumed as an answer
     */
    public boolean answerFromChat(String playerName, String text) {
        if (playerName == null || text == null) return false;
        for (Pending entry : this.pending.values()) {
            if (entry.playerName.equalsIgnoreCase(playerName)) {
                String clean = text.trim();
                if (clean.length() > QuestionLimits.MAX_ANSWER_CHARS) {
                    clean = clean.substring(0, QuestionLimits.MAX_ANSWER_CHARS);
                }
                if (entry.future.complete(new Reply(Status.ANSWERED, clean, -1))) return true;
            }
        }
        return false;
    }

    /** Dismisses any question already waiting for this player. */
    public void cancelFor(String playerName) {
        if (playerName == null) return;
        for (Pending entry : this.pending.values()) {
            if (entry.playerName.equalsIgnoreCase(playerName)) {
                entry.future.complete(new Reply(Status.CANCELLED, "", -1));
            }
        }
    }

    /** Number of live questions; diagnostics and tests. */
    public int activeCount() {
        return this.pending.size();
    }

    private static boolean samePlayer(Pending entry, String playerName) {
        return playerName != null && entry.playerName.equalsIgnoreCase(playerName.trim());
    }

    private static Answer result(Status status, String text, int optionIndex, long started) {
        return new Answer(status, text, optionIndex, Math.max(0L,
                System.currentTimeMillis() - started));
    }
}
