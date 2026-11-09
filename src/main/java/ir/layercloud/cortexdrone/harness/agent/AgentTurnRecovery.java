package ir.layercloud.cortexdrone.harness.agent;

import ir.layercloud.cortexdrone.harness.session.DroneSession;

/**
 * Policy for an assistant turn that produced no tool call but is not a usable
 * final answer.
 *
 * <p>A thinking model can spend the whole {@code max_tokens} completion on
 * hidden reasoning and then return an empty message with
 * {@code finish_reason=length}. Retrying with the same budget just resumes the
 * same truncated reasoning and fails again, so the loop widens the budget. A
 * cut-off answer is continued, not restarted, and is delivered instead of being
 * discarded once the retries are spent.</p>
 */
final class AgentTurnRecovery {
    /** Retries tolerated before the turn is reported as dead. */
    static final int MAX_RETRIES = 4;
    /** First completion budget used after an unusable turn. */
    static final int MIN_TOKENS = 4_096;
    /** Hard ceiling for the escalated completion budget. */
    static final int MAX_TOKENS = 16_384;

    private AgentTurnRecovery() {
    }

    /** One decision for the agent loop. */
    static final class Decision {
        /** Retry the same turn with {@link #nextBudget}. */
        final boolean retry;
        /** The turn had no text at all (as opposed to a cut-off answer). */
        final boolean empty;
        /** Completion budget for the retry. */
        final int nextBudget;
        /** Instruction appended as the next user message. */
        final String nudge;
        /** Player-facing explanation when the turn is abandoned. */
        final String finalMessage;
        /** Deliver the cut-off answer instead of asking again. */
        final boolean deliverTruncated;
        /** Provider finish reason, for logging. */
        final String finish;

        private Decision(boolean retry, boolean empty, int nextBudget, String nudge,
                         String finalMessage, boolean deliverTruncated, String finish) {
            this.retry = retry;
            this.empty = empty;
            this.nextBudget = nextBudget;
            this.nudge = nudge;
            this.finalMessage = finalMessage;
            this.deliverTruncated = deliverTruncated;
            this.finish = finish;
        }

        static Decision retry(boolean empty, int nextBudget, String nudge, String finish) {
            return new Decision(true, empty, nextBudget, nudge, null, false, finish);
        }

        static Decision stop(String message, String finish) {
            return new Decision(false, true, 0, null, message, false, finish);
        }

        static Decision deliver(String finish) {
            return new Decision(false, false, 0, null, null, true, finish);
        }

        static Decision nothing(String finish) {
            return new Decision(false, false, 0, null, null, false, finish);
        }
    }

    /**
     * Decide what to do with a reply that carried no tool call.
     *
     * @param attempt       retry number, starting at 1
     * @param currentBudget the effective {@code max_tokens} of the turn just run
     */
    static Decision decide(LlmResponse response, int attempt, int currentBudget,
                           DroneSession session) {
        boolean empty = response.content().trim().isEmpty();
        boolean truncated = isTruncated(response);
        String finish = finishReason(response);
        if (!empty && !truncated) return Decision.nothing(finish);
        if (attempt <= MAX_RETRIES) {
            return Decision.retry(empty, escalate(currentBudget), nudge(response, empty), finish);
        }
        if (empty) return Decision.stop(emptyResponseMessage(session, currentBudget), finish);
        return Decision.deliver(finish);
    }

    /**
     * Widens the completion budget: at least double the previous value, never
     * below {@link #MIN_TOKENS}, never above {@link #MAX_TOKENS}.
     */
    static int escalate(int effective) {
        long doubled = (long) Math.max(1, effective) * 2L;
        return (int) Math.min(MAX_TOKENS, Math.max(doubled, (long) MIN_TOKENS));
    }

    private static boolean isTruncated(LlmResponse response) {
        return "length".equalsIgnoreCase(response.finishReason());
    }

    private static String finishReason(LlmResponse response) {
        return response.finishReason() == null || response.finishReason().isEmpty()
                ? "unknown" : response.finishReason();
    }

    private static String nudge(LlmResponse response, boolean empty) {
        if (!empty) {
            return "Your previous answer was cut off mid-reply by the output token limit. "
                    + "Continue from where it stopped: if work remains, call exactly one tool that "
                    + "makes concrete progress; otherwise finish the sentence with the outcome.";
        }
        return "Your previous reply contained no text and no tool call"
                + (isTruncated(response)
                ? " because the completion budget ran out while you were reasoning" : "")
                + ". Do not restart the plan or repeat the analysis. Either call exactly one tool "
                + "that makes concrete progress now (execute is preferred for multi-step work), "
                + "or send a short final answer stating what is done and what is blocked.";
    }

    private static String emptyResponseMessage(DroneSession session, int maxTokens) {
        String last = session != null ? session.lastThought() : "";
        StringBuilder message = new StringBuilder(
                "Stopped because the model kept returning empty replies after the output budget "
                        + "was raised to ")
                .append(maxTokens).append(" tokens. It used the completion on hidden reasoning ")
                .append("and never called a tool. Re-send the task");
        if (maxTokens >= MAX_TOKENS) {
            message.append("; if it repeats, raise maxTokens in ")
                    .append("config/cortexdrone/config.json or use a model with a shorter ")
                    .append("thinking phase");
        }
        message.append('.');
        if (last != null && !last.isEmpty()) {
            message.append(" Last state: ").append(LoopTracer.snippet(last, 160)).append('.');
        }
        return message.toString();
    }
}
