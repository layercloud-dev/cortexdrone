package ir.layercloud.cortexdrone.config;

import com.google.gson.Gson;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.visuals.VisualsState;
import ir.layercloud.cortexdrone.service.navigation.DroneSpeed;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent CortexDrone configuration.
 *
 * <p>Everything AI-related (gateway, agent limits, saved drones) lives in the
 * mod config directory ({@code config/cortexdrone/config.json}) and survives a
 * world reset. World state — memory and protected zones — lives inside the
 * save instead; see {@link WorldDirectory} and {@link ModPaths}.</p>
 */
@Getter
@Setter
public class ModConfig {
    private static final Logger LOG = Logger.getLogger("CortexDrone");

    /**
     * Default OpenAI-compatible gateway used when nothing else is configured.
     */
    public static final String DEFAULT_API_URL = "http://127.0.0.1:7002/v1/chat/completions";
    /**
     * Default model id used when nothing else is configured.
     */
    public static final String DEFAULT_MODEL = "deepseek/deepseek-v4.1-flash";

    // Defaults are declared once here and referenced by both the field
    // initialisers and sanitized(), so a hand-edited config cannot drift
    // against the compiled defaults.
    public static final int DEFAULT_MAX_TURNS = 60;
    /**
     * Turn-count bounds for one task loop. A single turn is legal (one-shot
     * tasks); an unbounded count would let a looping model burn the whole
     * deadline, so the ceiling is generous but finite.
     */
    public static final int MIN_MAX_TURNS = 1;
    public static final int MAX_MAX_TURNS = 500;
    /**
     * Wall-clock budget for one generation. A "keep going until I say stop"
     * task is bounded by the turn count and by this budget; 8 minutes used to
     * cut an in-flight task mid-leg, so the default is 30 minutes.
     */
    public static final int DEFAULT_TASK_DEADLINE_SECONDS = 1800;
    public static final int MIN_TASK_DEADLINE_SECONDS = 60;
    public static final int MAX_TASK_DEADLINE_SECONDS = 24 * 60 * 60;
    /**
     * Completion budget for one assistant turn.
     *
     * <p>Thinking models spend this budget on hidden reasoning before they emit
     * a tool call. A small value (the old 2048) was consumed entirely by
     * reasoning, so the turn returned an empty message with no tool call and
     * the task looked dead. Keep enough room for reasoning plus one call.</p>
     */
    public static final int DEFAULT_MAX_TOKENS = 8192;
    public static final int MIN_MAX_TOKENS = 256;
    public static final int MAX_MAX_TOKENS = 200_000;
    public static final double DEFAULT_TEMPERATURE = 0.2D;
    public static final double MIN_TEMPERATURE = 0.0D;
    public static final double MAX_TEMPERATURE = 2.0D;
    public static final int DEFAULT_EXECUTE_TIMEOUT_MS = 120_000;
    public static final int MIN_EXECUTE_TIMEOUT_MS = 1_000;
    public static final int MAX_EXECUTE_TIMEOUT_MS = 600_000;
    public static final int DEFAULT_MAX_TOOL_RESULT_CHARS = 8_000;
    public static final int MIN_MAX_TOOL_RESULT_CHARS = 500;
    public static final int MAX_MAX_TOOL_RESULT_CHARS = 200_000;
    public static final int DEFAULT_MAX_CONTEXT_CHARS = 96_000;
    public static final int MIN_MAX_CONTEXT_CHARS = 4_000;
    public static final int MAX_MAX_CONTEXT_CHARS = 2_000_000;
    /**
     * Default flight-speed multiplier. 1.0 is the original cruise speed; the
     * tuned ceiling is {@link DroneSpeed#MAX_MULTIPLIER}.
     */
    public static final double DEFAULT_DRONE_SPEED_MULTIPLIER = 5.0D;

    // --- LLM gateway -------------------------------------------------------
    private String apiUrl = env("CORTEXDRONE_API_URL", DEFAULT_API_URL);
    private String model = env("CORTEXDRONE_MODEL", DEFAULT_MODEL);
    private String apiKey = env("CORTEXDRONE_API_KEY", "");

    // --- harness -----------------------------------------------------------
    private int maxTurns = DEFAULT_MAX_TURNS;
    private int taskDeadlineSeconds = DEFAULT_TASK_DEADLINE_SECONDS;
    private int maxTokens = DEFAULT_MAX_TOKENS;
    private double temperature = DEFAULT_TEMPERATURE;
    private int executeTimeoutMs = DEFAULT_EXECUTE_TIMEOUT_MS;
    private int maxToolResultChars = DEFAULT_MAX_TOOL_RESULT_CHARS;
    private int maxContextChars = DEFAULT_MAX_CONTEXT_CHARS;
    private boolean allowJavaReflection = true;
    private boolean webSearchEnabled = true;

    // --- movement ----------------------------------------------------------
    /**
     * Flight-speed multiplier applied to the tuned cruise tiers.
     * 1.0 = the original speed; the defined ceiling is 5.0 (the default here).
     * Values outside 0.25..5.0 are clamped by {@code DroneSpeed}.
     */
    private double droneSpeedMultiplier = DEFAULT_DRONE_SPEED_MULTIPLIER;

    // --- persisted drone bindings -----------------------------------------
    private List<SavedDroneInfo> savedDrones = new ArrayList<>();

    @Data
    public static class SavedDroneInfo {
        private String uuid;
        private String name;
        private String owner;
        private boolean lightAura;
        private Integer ledColor;
        private int dimension;
        private Double x;
        private Double y;
        private Double z;
    }

    private static String env(String key, String fallback) {
        try {
            String value = System.getenv(key);
            if (value != null && !value.trim().isEmpty()) return value.trim();
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /**
     * Clamps a turn budget into {@link #MIN_MAX_TURNS}..{@link #MAX_MAX_TURNS}.
     *
     * <p>Shared by the command and by {@link #sanitized()} so a hand-edited
     * config and {@code /drone config turns} cannot diverge.</p>
     */
    public static int clampMaxTurns(int turns) {
        return Math.max(MIN_MAX_TURNS, Math.min(MAX_MAX_TURNS, turns));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static ModConfig load(File file, Gson gson) {
        if (file == null || !file.exists()) return new ModConfig();
        try (FileReader reader = new FileReader(file)) {
            ModConfig loaded = gson.fromJson(reader, ModConfig.class);
            if (loaded == null) return new ModConfig();
            return loaded.sanitized();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to read " + file.getAbsolutePath(), e);
            return new ModConfig();
        }
    }

    /**
     * Repairs missing/blank values after a migration or a hand edit.
     */
    public ModConfig sanitized() {
        if (this.apiUrl == null || this.apiUrl.trim().isEmpty()) {
            this.apiUrl = env("CORTEXDRONE_API_URL", DEFAULT_API_URL);
        }
        if (this.model == null || this.model.trim().isEmpty()) {
            this.model = env("CORTEXDRONE_MODEL", DEFAULT_MODEL);
        }
        if (this.apiKey == null) this.apiKey = "";
        if (this.maxTurns <= 0) this.maxTurns = DEFAULT_MAX_TURNS;
        this.maxTurns = clampMaxTurns(this.maxTurns);
        if (this.taskDeadlineSeconds <= 0) this.taskDeadlineSeconds = DEFAULT_TASK_DEADLINE_SECONDS;
        this.taskDeadlineSeconds = clamp(this.taskDeadlineSeconds,
                MIN_TASK_DEADLINE_SECONDS, MAX_TASK_DEADLINE_SECONDS);
        if (this.maxTokens <= 0) this.maxTokens = DEFAULT_MAX_TOKENS;
        this.maxTokens = clamp(this.maxTokens, MIN_MAX_TOKENS, MAX_MAX_TOKENS);
        if (this.temperature < MIN_TEMPERATURE || this.temperature > MAX_TEMPERATURE) {
            this.temperature = DEFAULT_TEMPERATURE;
        }
        if (this.executeTimeoutMs <= 0) this.executeTimeoutMs = DEFAULT_EXECUTE_TIMEOUT_MS;
        this.executeTimeoutMs = clamp(this.executeTimeoutMs,
                MIN_EXECUTE_TIMEOUT_MS, MAX_EXECUTE_TIMEOUT_MS);
        if (this.maxToolResultChars <= 0) this.maxToolResultChars = DEFAULT_MAX_TOOL_RESULT_CHARS;
        this.maxToolResultChars = clamp(this.maxToolResultChars,
                MIN_MAX_TOOL_RESULT_CHARS, MAX_MAX_TOOL_RESULT_CHARS);
        if (this.maxContextChars <= 0) this.maxContextChars = DEFAULT_MAX_CONTEXT_CHARS;
        this.maxContextChars = clamp(this.maxContextChars,
                MIN_MAX_CONTEXT_CHARS, MAX_MAX_CONTEXT_CHARS);
        if (this.droneSpeedMultiplier <= 0.0D || Double.isNaN(this.droneSpeedMultiplier)) {
            // An unreadable value must not silently become max speed.
            this.droneSpeedMultiplier = 1.0D;
        }
        this.droneSpeedMultiplier = Math.max(DroneSpeed.MIN_MULTIPLIER,
                Math.min(DroneSpeed.MAX_MULTIPLIER,
                        this.droneSpeedMultiplier));
        if (this.savedDrones == null) this.savedDrones = new ArrayList<>();
        return this;
    }

    public synchronized void save(File file, Gson gson, Collection<DroneController> controllers,
                                  Function<DroneController, VisualsState> visualsOf) {
        if (file == null) return;
        try {
            Map<String, SavedDroneInfo> byUuid = new LinkedHashMap<>();
            if (this.savedDrones != null) {
                for (SavedDroneInfo info : this.savedDrones) {
                    if (info != null && info.getUuid() != null && !DroneController.SERVER_OWNER.equalsIgnoreCase(info.getOwner())) {
                        byUuid.put(info.getUuid(), info);
                    }
                }
            }
            if (controllers != null) {
                for (DroneController controller : controllers) {
                    if (controller.drone == null || controller.drone.isDead
                            || controller.entityUuid == null
                            || DroneController.SERVER_OWNER.equalsIgnoreCase(controller.ownerName)) continue;
                    SavedDroneInfo info = new SavedDroneInfo();
                    info.setUuid(controller.entityUuid.toString());
                    info.setName(controller.name);
                    info.setOwner(controller.ownerName);
                    VisualsState visuals = visualsOf != null ? visualsOf.apply(controller) : null;
                    info.setLightAura(visuals != null && visuals.lightAura);
                    info.setLedColor(controller.drone.lightColor() & 0xFFFFFF);
                    info.setDimension(controller.drone.dimension);
                    info.setX(Math.round(controller.drone.posX * 10.0D) / 10.0D);
                    info.setY(Math.round(controller.drone.posY * 10.0D) / 10.0D);
                    info.setZ(Math.round(controller.drone.posZ * 10.0D) / 10.0D);
                    byUuid.put(info.getUuid(), info);
                }
            }
            this.savedDrones = new ArrayList<>(byUuid.values());
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                LOG.warning("Could not create config directory " + parent.getAbsolutePath());
            }
            try (FileWriter writer = new FileWriter(file)) {
                gson.toJson(this, writer);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to save " + file.getAbsolutePath(), e);
        }
    }
}
