package ir.layercloud.cortexdrone.harness.session;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.harness.markdown.AtomicFiles;

import java.io.File;

/**
 * Writes one finished session to disk.
 *
 * <p>Extracted from {@code SessionJournal}. Writing a session is three artifacts
 * with a defined order: the authoritative JSON transcript, the human Markdown
 * view beside it, then the index line. The JSON must land first — if it fails,
 * nothing else is written, so the index never points at a missing transcript.</p>
 */
final class SessionWriter {
    private final File sessionsRoot;
    private final SessionIndex index;
    private final DroneLogger logger;

    SessionWriter(File sessionsRoot, SessionIndex index, DroneLogger logger) {
        this.sessionsRoot = sessionsRoot;
        this.index = index;
        this.logger = logger;
    }

    /**
     * Writes the run and returns the transcript's path relative to the root.
     */
    String write(SessionJournal.SessionRun run, SessionOutcome outcome, String reason, String topic) {
        SessionMarkdown.Snapshot snapshot = run.markdownSnapshot();
        File directory = new File(this.sessionsRoot, outcome.directory());
        if (!ensureDirectory(directory)) {
            logLine(run, outcome, reason, topic, "<directory unavailable>");
            return null;
        }
        File jsonFile = uniqueFile(directory, run.fileStem() + "-" + topic + "-" + run.fileSuffix(),
                ".json");
        JsonObject transcript = SessionJson.transcript(snapshot, outcome, reason, topic,
                run.finalReply());
        if (!AtomicFiles.writeText(jsonFile, CortexDroneMod.GSON.toJson(transcript))) {
            logLine(run, outcome, reason, topic, "<write failed: " + jsonFile.getAbsolutePath() + ">");
            return null;
        }
        File markdownFile = new File(jsonFile.getAbsolutePath().replaceFirst("\\.json$", ".md"));
        AtomicFiles.writeText(markdownFile, SessionMarkdown.render(snapshot, outcome, reason, topic));

        String relativeFile = outcome.directory() + "/" + jsonFile.getName();
        this.index.append(SessionJson.index(snapshot, outcome, reason, topic, relativeFile));
        logLine(run, outcome, reason, topic, relativeFile);
        return relativeFile;
    }

    private void logLine(SessionJournal.SessionRun run, SessionOutcome outcome, String reason,
                         String topic, String file) {
        if (this.logger == null) return;
        this.logger.log("SESSION", null,
                "Session " + outcome.id() + " (" + topic + ") drone=" + run.droneName()
                        + " loop=" + run.loopId() + " reason=" + (reason != null ? reason : ""),
                "file=" + file + " turns=" + run.turns() + " tools=" + run.toolCalls()
                        + " errors=" + run.toolErrors() + " durationMs=" + run.durationMs());
    }

    /**
     * A free file name in {@code directory}, appending {@code -2}, {@code -3}, ...
     */
    private static File uniqueFile(File directory, String baseName, String extension) {
        File candidate = new File(directory, baseName + extension);
        int suffix = 2;
        while (candidate.exists()) {
            candidate = new File(directory, baseName + "-" + suffix + extension);
            suffix++;
        }
        return candidate;
    }

    private static boolean ensureDirectory(File directory) {
        return directory != null && (directory.isDirectory() || directory.mkdirs());
    }
}
