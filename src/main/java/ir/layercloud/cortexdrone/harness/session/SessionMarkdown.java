package ir.layercloud.cortexdrone.harness.session;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Renders one finished session run as a Markdown transcript.
 *
 * <p>The JSON transcript is authoritative; this is the human view written beside
 * it. It reads a {@link Snapshot} so the run keeps its counters private and a
 * rendering change can never affect the audit record.</p>
 */
@SuppressWarnings("unused")
final class SessionMarkdown {
    private SessionMarkdown() {
    }

    /**
     * Read-only view of the fields the Markdown needs.
     *
     * <p>Deliberately a plain value object: it is copied under the run's lock so
     * rendering never observes a half-updated counter.</p>
     */
    static final class Snapshot {
        String id = "";
        String droneName = "";
        int droneId;
        int loopId;
        String ownerName = "";
        String playerName = "";
        String intent = "";
        String model = "";
        int dimension;
        double x;
        double y;
        double z;
        String startedIso = "";
        long startedAt;
        long endedAt;
        int turns;
        int toolCalls;
        int toolErrors;
        int worldActions;
        String prompt = "";
        List<JsonObject> transcript = Collections.emptyList();
    }

    /**
     * Renders the snapshot as Markdown.
     */
    static String render(Snapshot run, SessionOutcome outcome, String reason, String topic) {
        StringBuilder out = new StringBuilder(4_096);
        out.append("# Session ").append(run.id).append('\n').append('\n');
        out.append("- outcome: **").append(outcome.id()).append("**\n");
        out.append("- topic: ").append(topic).append('\n');
        out.append("- reason: ").append(reason != null ? reason : "").append('\n');
        out.append("- drone: #").append(run.droneId).append(' ').append(run.droneName).append('\n');
        out.append("- owner/player: ").append(run.ownerName)
                .append(" / ").append(run.playerName).append('\n');
        out.append("- intent: ").append(run.intent).append('\n');
        out.append("- model: ").append(run.model).append('\n');
        out.append("- dimension: ").append(run.dimension).append('\n');
        out.append("- start: (").append(fmt(run.x)).append(", ").append(fmt(run.y))
                .append(", ").append(fmt(run.z)).append(")\n");
        out.append("- started: ").append(run.startedIso).append('\n');
        out.append("- duration: ").append(Math.max(0L, run.endedAt - run.startedAt)).append(" ms\n");
        out.append("- turns/tools/errors: ").append(run.turns).append(" / ")
                .append(run.toolCalls).append(" / ").append(run.toolErrors).append('\n');
        out.append('\n').append("## Prompt\n\n").append(run.prompt).append('\n');
        transcript(out, run.transcript);
        return out.toString();
    }

    private static void transcript(StringBuilder out, List<JsonObject> entries) {
        if (entries == null || entries.isEmpty()) return;
        out.append('\n').append("## Transcript\n\n");
        for (JsonObject entry : entries) {
            String type = string(entry, "type");
            int turn = entry.has("turn") ? entry.get("turn").getAsInt() : 0;
            if ("thought".equals(type)) {
                out.append("### T").append(turn).append(" thought\n\n")
                        .append(string(entry, "text")).append("\n\n");
            } else if ("tool".equals(type)) {
                boolean ok = entry.has("ok") && entry.get("ok").getAsBoolean();
                out.append("### T").append(turn).append(" tool `")
                        .append(string(entry, "name")).append("` -> ")
                        .append(ok ? "ok" : "error").append("\n\n");
                String args = string(entry, "args");
                if (!args.isEmpty()) out.append("args: `").append(args).append("`\n\n");
                String result = string(entry, "result");
                if (!result.isEmpty()) out.append("```\n").append(result).append("\n```\n\n");
                String error = string(entry, "error");
                if (!error.isEmpty()) out.append("error: ").append(error).append("\n\n");
            } else if ("reply".equals(type)) {
                out.append("### T").append(turn).append(" reply\n\n")
                        .append(string(entry, "text")).append("\n\n");
            }
        }
    }

    /**
     * Two-decimal coordinate, stable across locales.
     */
    static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : "";
    }
}
