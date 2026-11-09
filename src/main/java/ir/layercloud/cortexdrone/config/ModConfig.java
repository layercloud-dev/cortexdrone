package ir.layercloud.cortexdrone.config;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.Gson;
import ir.layercloud.cortexdrone.action.visuals.VisualsState;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent CortexDrone configuration.
 *
 * <p>Everything AI-related (gateway, agent limits, saved drones) lives in the
 * mod config directory ({@code config/cortexdrone/config.json}), never inside
 * the world save. World state stays in the world; harness state stays in the
 * config directory so it survives world resets.</p>
 */
@Getter
@Setter
public class ModConfig {
    private static final Logger LOG = Logger.getLogger("CortexDrone");

    /** Default OpenAI-compatible gateway used when nothing else is configured. */
    public static final String DEFAULT_API_URL = "http://127.0.0.1:7002/v1/chat/completions";
    /** Default model id used when nothing else is configured. */
    public static final String DEFAULT_MODEL = "deepseek/deepseek-v4.1-flash";

    // Defaults are declared once here and referenced by both the field
    // initialisers and sanitized(), so a hand-edited config cannot drift
    // against the compiled defaults.
    public static final int DEFAULT_MAX_TURNS = 60;
    public static final int DEFAULT_TASK_DEADLINE_SECONDS = 480;
    /**
     * Completion budget for one assistant turn.
     *
     * <p>Thinking models spend this budget on hidden reasoning before they emit
     * a tool call. A small value (the old 2048) was consumed entirely by
     * reasoning, so the turn returned an empty message with no tool call and
     * the task looked dead. Keep enough room for reasoning plus one call.</p>
     */
    public static final int DEFAULT_MAX_TOKENS = 8192;
    public static final double DEFAULT_TEMPERATURE = 0.2D;
    public static final int DEFAULT_EXECUTE_TIMEOUT_MS = 120_000;
    public static final int DEFAULT_MAX_TOOL_RESULT_CHARS = 8_000;
    public static final int DEFAULT_MAX_CONTEXT_CHARS = 96_000;

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
    private double droneSpeedMultiplier = 5.0D;

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

    /** Repairs missing/blank values after a migration or a hand edit. */
    public ModConfig sanitized() {
        if (this.apiUrl == null || this.apiUrl.trim().isEmpty()) {
            this.apiUrl = env("CORTEXDRONE_API_URL", DEFAULT_API_URL);
        }
        if (this.model == null || this.model.trim().isEmpty()) {
            this.model = env("CORTEXDRONE_MODEL", DEFAULT_MODEL);
        }
        if (this.apiKey == null) this.apiKey = "";
        if (this.maxTurns <= 0) this.maxTurns = DEFAULT_MAX_TURNS;
        if (this.taskDeadlineSeconds <= 0) this.taskDeadlineSeconds = DEFAULT_TASK_DEADLINE_SECONDS;
        if (this.maxTokens <= 0) this.maxTokens = DEFAULT_MAX_TOKENS;
        if (this.temperature < 0.0D || this.temperature > 2.0D) this.temperature = DEFAULT_TEMPERATURE;
        if (this.executeTimeoutMs <= 0) this.executeTimeoutMs = DEFAULT_EXECUTE_TIMEOUT_MS;
        if (this.maxToolResultChars <= 0) this.maxToolResultChars = DEFAULT_MAX_TOOL_RESULT_CHARS;
        if (this.maxContextChars <= 0) this.maxContextChars = DEFAULT_MAX_CONTEXT_CHARS;
        if (this.droneSpeedMultiplier <= 0.0D || Double.isNaN(this.droneSpeedMultiplier)) {
            this.droneSpeedMultiplier = 1.0D;
        }
        this.droneSpeedMultiplier = Math.max(ir.layercloud.cortexdrone.service.navigation.DroneSpeed.MIN_MULTIPLIER,
                Math.min(ir.layercloud.cortexdrone.service.navigation.DroneSpeed.MAX_MULTIPLIER,
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
