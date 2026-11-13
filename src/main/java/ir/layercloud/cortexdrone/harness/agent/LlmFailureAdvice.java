package ir.layercloud.cortexdrone.harness.agent;

/**
 * Player-facing diagnosis for a failed gateway call.
 *
 * <p>One generic sentence ("fix the gateway/key") was wrong for most of the
 * failures in the live journal: a free-tier daily quota and a 404 endpoint
 * need completely different settings. Extracted from {@code LlmClient} so the
 * retry policy and the message policy can each stay small and testable.</p>
 */
final class LlmFailureAdvice {
    private LlmFailureAdvice() {
    }

    /**
     * Actionable replacement for the generic gateway advice.
     */
    static String diagnose(String rawError) {
        String text = rawError != null ? rawError.toLowerCase(java.util.Locale.ROOT) : "";
        if (text.contains("429") || text.contains("rate limit")) {
            if (isDailyQuota(text)) {
                return "The model gateway's daily free quota is exhausted. Add credits to the "
                        + "gateway account, or switch model in /drone config model <id>, then re-send "
                        + "the task.";
            }
            return "The model gateway is rate-limiting requests. Wait a minute, or lower the task "
                    + "rate, then re-send the task.";
        }
        if (text.contains("404")) {
            return "The model gateway returned 404. Check that apiUrl ends in "
                    + "/v1/chat/completions and that the model id exists "
                    + "(/drone config url|model), then re-send the task.";
        }
        if (text.contains("401") || text.contains("403")) {
            return "The model gateway rejected the API key. Set it with /drone config key <token>, "
                    + "then re-send the task.";
        }
        return "Fix the gateway/key and re-send the task.";
    }

    /**
     * True for a per-day / credit quota, which retrying cannot fix.
     */
    static boolean isDailyQuota(String message) {
        if (message == null) return false;
        String text = message.toLowerCase(java.util.Locale.ROOT);
        return text.contains("per-day") || text.contains("per day")
                || text.contains("daily") || text.contains("credits");
    }
}
