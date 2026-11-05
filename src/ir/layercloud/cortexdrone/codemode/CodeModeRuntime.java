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
import net.minecraft.entity.player.EntityPlayerMP;

public class CodeModeRuntime {
    private static final String HARNESS_TEMPLATE = loadSdkBundle();

    private final DroneLogger logger;
    private final AIConfig config;

    public CodeModeRuntime(DroneLogger logger, AIConfig config) {
        this.logger = logger;
        this.config = config;
    }

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
        JsonObject out = new JsonObject();
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
            int port = (this.config != null && this.config.httpPort > 0) ? this.config.httpPort : 25580;

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

            // Wait up to 180s for physical multi-step workflows, aborting within 250ms if superseded by a newer command
            for (int slice = 0; slice < 720; slice++) {
                if (proc.waitFor(250, TimeUnit.MILLISECONDS)) {
                    break;
                }
                if ((loopId > 0 && ctrl.activeLoopId.get() != loopId) || ctrl.drone == null || ctrl.drone.field_70128_L) {
                    proc.destroyForcibly();
                    break;
                }
            }
            if (proc.isAlive()) {
                proc.destroyForcibly();
            }
            readerThread.join(1000);

            long elapsed = System.currentTimeMillis() - t0;
            String rawOut = sb.toString().trim();
            int fb = rawOut.indexOf('{');
            int lb = rawOut.lastIndexOf('}');
            if (fb >= 0 && lb > fb) {
                out = new JsonParser().parse(rawOut.substring(fb, lb + 1)).getAsJsonObject();
            } else {
                out.addProperty("ok", false);
                out.addProperty("rawOutput", rawOut);
            }
            this.logger.log("CODEMODE", ctrl, "Executed JS Code Mode (" + elapsed + "ms)", out.toString());
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
