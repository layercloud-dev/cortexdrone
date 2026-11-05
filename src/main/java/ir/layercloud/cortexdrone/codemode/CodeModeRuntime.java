package ir.layercloud.cortexdrone.codemode;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.AIConfig;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

@RequiredArgsConstructor
public class CodeModeRuntime {
    private static final String HARNESS_TEMPLATE = loadSdkBundle();

    private final DroneLogger logger;
    private final AIConfig config;

    private static String loadSdkBundle() {
        String[] modules = new String[]{
            "/sdk/core-rpc.mjs",
            "/sdk/world-api.mjs",
            "/sdk/drone-actions.mjs",
            "/sdk/drone-effects.mjs"
        };
        StringBuilder sb = new StringBuilder();
        for (String mod : modules) {
            String part = loadResourceText(mod);
            if (!part.isEmpty()) {
                sb.append(part).append('\n');
            }
        }
        return sb.toString();
    }

    public static String loadResourceText(String resourcePath) {
        try (InputStream is = CodeModeRuntime.class.getResourceAsStream(resourcePath)) {
            if (is == null) return "";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public JsonObject executeJavaScript(DroneController ctrl, EntityPlayerMP player, String jsCode) {
        return executeJavaScript(ctrl, player, jsCode, 0, true);
    }

    public JsonObject executeJavaScript(DroneController ctrl, EntityPlayerMP player, String jsCode, int loopId) {
        return executeJavaScript(ctrl, player, jsCode, loopId, true);
    }

    public JsonObject executeJavaScript(DroneController ctrl, EntityPlayerMP player, String jsCode, int loopId, boolean isFinalTurn) {
        return executeJavaScript(ctrl, player, jsCode, loopId, isFinalTurn, 180000L);
    }

    public JsonObject executeJavaScript(DroneController ctrl, EntityPlayerMP player, String jsCode, int loopId, boolean isFinalTurn, long timeoutMs) {
        final JsonObject out = new JsonObject();
        long t0 = System.currentTimeMillis();
        File scriptFile = new File("/tmp/opencode/drone-codemode-" + ctrl.id + "-" + System.nanoTime() + ".mjs");
        ctrl.activeScriptCount.incrementAndGet();
        try {
            String safeJsCode = jsCode != null ? jsCode
                .replaceAll("((?:const|let|var)\\s+drone\\s*=[^;\\n]*?)\\bdrone\\.", "$1_droneApi.")
                .replaceAll("((?:const|let|var)\\s+player\\s*=[^;\\n]*?)\\bplayer\\.", "$1_playerApi.")
                .replaceAll("((?:const|let|var)\\s+world\\s*=[^;\\n]*?)\\bworld\\.", "$1_worldApi.")
                : "";
            String pName = player != null ? player.func_70005_c_() : ctrl.ownerName;
            int port = (this.config != null && this.config.getHttpPort() > 0) ? this.config.getHttpPort() : 25580;

            String script = "const DRONE_ID = " + ctrl.id + ";\n"
                + "const LOOP_ID = " + loopId + ";\n"
                + "const IS_FINAL_TURN = " + isFinalTurn + ";\n"
                + "const PLAYER_NAME = " + CortexDroneMod.GSON.toJson(pName) + ";\n"
                + "const HTTP_PORT = " + port + ";\n"
                + HARNESS_TEMPLATE + "\n"
                + "(async () => {\n"
                + "  try {\n"
                + "    let result = await (async () => {\n"
                + safeJsCode + "\n"
                + "    })();\n"
                + "    await _finalizeScript();\n"
                + "    if (_flushedMineTotal > 0 || _flushedBuildTotal > 0) {\n"
                + "      const totalQueued = _flushedMineTotal + _flushedBuildTotal;\n"
                + "      if (result === undefined || result === null) {\n"
                + "        result = { queued: totalQueued, queuedMine: _flushedMineTotal, queuedBuild: _flushedBuildTotal };\n"
                + "      } else if (typeof result === 'object' && !Array.isArray(result) && result.queued === undefined) {\n"
                + "        result.queued = totalQueued;\n"
                + "      }\n"
                + "    }\n"
                + "    process.stdout.write(JSON.stringify({ ok: true, result: result ?? null, logs }) + '\\n', () => process.exit(0));\n"
                + "  } catch (err) {\n"
                + "    const msg = String(err && err.message || err);\n"
                + "    if (msg === 'SUPERSEDED') {\n"
                + "      process.stdout.write(JSON.stringify({ ok: true, superseded: true, result: null, logs }) + '\\n', () => process.exit(0));\n"
                + "    } else {\n"
                + "      process.stdout.write(JSON.stringify({ ok: false, error: String(err && err.stack || err), logs }) + '\\n', () => process.exit(0));\n"
                + "    }\n"
                + "  }\n"
                + "})();\n";

            try (FileWriter fw = new FileWriter(scriptFile)) {
                fw.write(script);
            }

            ProcessBuilder pb = new ProcessBuilder(
                "/usr/local/bin/node",
                "--max-old-space-size=256",
                "--no-warnings",
                scriptFile.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            final Process proc = pb.start();
            final StringBuilder sb = new StringBuilder();
            Thread readerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }, "CortexDrone-JS-Reader-" + ctrl.id);
            readerThread.setDaemon(true);
            readerThread.start();

            // Bounded wait (default 180s via the overload) with a 250ms
            // supersession poll. A wedged node process is destroyed, logged,
            // and returned as an error — never a silent hang.
            long deadline = t0 + Math.max(5000L, timeoutMs);
            boolean timedOut = false;
            boolean superseded = false;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    timedOut = true;
                    break;
                }
                if (proc.waitFor(Math.min(250L, remaining), TimeUnit.MILLISECONDS)) {
                    break;
                }
                if ((loopId > 0 && ctrl.isLoopCancelled(loopId)) || ctrl.drone == null || ctrl.drone.field_70128_L) {
                    superseded = true;
                    break;
                }
            }
            if (proc.isAlive()) {
                proc.destroyForcibly();
                try {
                    proc.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            readerThread.join(1000);

            long elapsed = System.currentTimeMillis() - t0;
            String rawOut = sb.toString().trim();
            if (superseded) {
                out.addProperty("ok", true);
                out.addProperty("superseded", true);
                this.logger.log("CODEMODE", ctrl, "JS Code Mode superseded (" + elapsed + "ms)", "loopId=" + loopId);
            } else if (timedOut) {
                out.addProperty("ok", false);
                out.addProperty("timeout", true);
                out.addProperty("error", "JS Code Mode timed out after " + elapsed + "ms (loopId=" + loopId + ")");
                out.addProperty("rawOutput", rawOut);
                this.logger.log("CODEMODE", ctrl, "JS Code Mode TIMEOUT (" + elapsed + "ms)", "loopId=" + loopId + " raw=" + rawOut.substring(0, Math.min(400, rawOut.length())));
            } else {
                int fb = rawOut.indexOf('{');
                int lb = rawOut.lastIndexOf('}');
                if (fb >= 0 && lb > fb) {
                    try {
                        JsonObject parsedOut = new JsonParser().parse(rawOut.substring(fb, lb + 1)).getAsJsonObject();
                        boolean parsedOk = parsedOut.has("ok") ? parsedOut.get("ok").getAsBoolean() : true;
                        out.addProperty("ok", parsedOk);
                        if (parsedOut.has("superseded") && parsedOut.get("superseded").getAsBoolean()) {
                            out.addProperty("superseded", true);
                        }
                        if (parsedOut.has("result")) out.add("result", parsedOut.get("result"));
                        if (parsedOut.has("logs")) out.add("logs", parsedOut.get("logs"));
                        if (!parsedOk && parsedOut.has("error")) out.add("error", parsedOut.get("error"));
                        if (!parsedOk && parsedOut.has("rawOutput")) out.add("rawOutput", parsedOut.get("rawOutput"));
                    } catch (Exception parseEx) {
                        out.addProperty("ok", false);
                        out.addProperty("error", "JS output parse failed: " + parseEx.toString());
                        out.addProperty("rawOutput", rawOut);
                        this.logger.log("CODEMODE", ctrl, "JS Code Mode OUTPUT PARSE ERROR (" + elapsed + "ms)", parseEx.toString() + " raw=" + rawOut.substring(0, Math.min(400, rawOut.length())));
                    }
                } else {
                    out.addProperty("ok", false);
                    if (proc.exitValue() != 0 || !rawOut.isEmpty()) {
                        out.addProperty("rawOutput", rawOut);
                    } else {
                        out.addProperty("error", "JS Code Mode exited with no output (exit=" + proc.exitValue() + ")");
                    }
                    this.logger.log("CODEMODE", ctrl, "JS Code Mode NO OUTPUT (" + elapsed + "ms)", "loopId=" + loopId + " exit=" + proc.exitValue());
                }
                if (out.has("ok")) {
                    this.logger.log("CODEMODE", ctrl, "Executed JS Code Mode (" + elapsed + "ms)", out.toString());
                }
            }
        } catch (Exception e) {
            out.addProperty("ok", false);
            out.addProperty("error", e.toString());
            this.logger.log("CODEMODE", ctrl, "JS Code Mode error", e.toString());
        } finally {
            ctrl.activeScriptCount.decrementAndGet();
            try {
                if (scriptFile.exists()) scriptFile.delete();
            } catch (Throwable ignored) {
            }
        }
        return out;
    }
}
