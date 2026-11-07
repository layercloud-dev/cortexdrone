package ir.layercloud.cortexdrone.config;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.Gson;
import ir.layercloud.cortexdrone.manager.visuals.VisualsState;
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

    // --- LLM gateway -------------------------------------------------------
    private String apiUrl = env("CORTEXDRONE_API_URL", "http://127.0.0.1:7002/v1/chat/completions");
    private String model = env("CORTEXDRONE_MODEL", "deepseek/deepseek-v4.1-flash");
    private String apiKey = env("CORTEXDRONE_API_KEY", "");

    // --- harness -----------------------------------------------------------
    private int maxTurns = 60;
    private int taskDeadlineSeconds = 480;
    private int maxTokens = 2048;
    private double temperature = 0.2D;
    private int executeTimeoutMs = 120_000;
    private int maxToolResultChars = 8_000;
    private int maxContextChars = 96_000;
    private boolean allowJavaReflection = true;
    private boolean webSearchEnabled = true;

    // --- navigation recovery ----------------------------------------------
    /**
     * Allow automatic navigation to clear blocks when no no-dig route exists.
     * Set to false to keep every route non-destructive; navigation then only
     * uses trail/flight/detour recovery.
     */
    private boolean navigationAllowDig = true;
    /** Upper bound of blocks one navigation session may clear. */
    private int navigationMaxDigBlocks = 48;
    /** Upper bound of recovery attempts (dig, detour) per session. */
    private int navigationRecoveryAttempts = 8;

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
            this.apiUrl = env("CORTEXDRONE_API_URL", "http://127.0.0.1:7002/v1/chat/completions");
        }
        if (this.model == null || this.model.trim().isEmpty()) {
            this.model = env("CORTEXDRONE_MODEL", "deepseek/deepseek-v4.1-flash");
        }
        if (this.apiKey == null) this.apiKey = "";
        if (this.maxTurns <= 0) this.maxTurns = 60;
        if (this.taskDeadlineSeconds <= 0) this.taskDeadlineSeconds = 480;
        if (this.maxTokens <= 0) this.maxTokens = 2048;
        if (this.temperature < 0.0D || this.temperature > 2.0D) this.temperature = 0.2D;
        if (this.executeTimeoutMs <= 0) this.executeTimeoutMs = 120_000;
        if (this.maxToolResultChars <= 0) this.maxToolResultChars = 8_000;
        if (this.maxContextChars <= 0) this.maxContextChars = 96_000;
        if (this.navigationMaxDigBlocks <= 0) this.navigationMaxDigBlocks = 48;
        if (this.navigationRecoveryAttempts <= 0) this.navigationRecoveryAttempts = 8;
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
                    if (info != null && info.getUuid() != null && !"Server".equalsIgnoreCase(info.getOwner())) {
                        byUuid.put(info.getUuid(), info);
                    }
                }
            }
            if (controllers != null) {
                for (DroneController controller : controllers) {
                    if (controller.drone == null || controller.drone.isDead
                            || controller.entityUuid == null
                            || "Server".equalsIgnoreCase(controller.ownerName)) continue;
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
