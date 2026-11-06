package ir.layercloud.cortexdrone.config;

import com.google.gson.Gson;
import ir.layercloud.cortexdrone.drone.DroneController;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;

@Getter
@Setter
public class AIConfig {
    private String apiUrl = envOrDefault("CORTEXDRONE_API_URL", "http://127.0.0.1:7002/v1/chat/completions");
    private String model = envOrDefault("CORTEXDRONE_MODEL", "deepseek/deepseek-v4.1-flash");
    private String apiKey = envOrDefault("CORTEXDRONE_API_KEY", "");
    private int httpPort = 25580;
    private List<SavedDroneInfo> savedDrones = new ArrayList<>();

    @Data
    public static class SavedDroneInfo {
        private String uuid;
        private String name;
        private String owner;
        private boolean autoTorch;
        private String ledEffect;
        private Integer customLedColor;
        private int dimension;
        private Double x;
        private Double y;
        private Double z;
    }

    private static String envOrDefault(String key, String fallback) {
        try {
            String val = System.getenv(key);
            if (val != null && !val.trim().isEmpty()) {
                return val.trim();
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    public static synchronized AIConfig load(File configFile, Gson gson) {
        if (configFile == null || !configFile.exists()) {
            return new AIConfig();
        }
        try (FileReader reader = new FileReader(configFile)) {
            AIConfig loaded = gson.fromJson(reader, AIConfig.class);
            if (loaded == null) {
                return new AIConfig();
            }
            // File values are only accepted if the caller explicitly set them;
            // the class-field initializers already applied envOrDefault (env var wins over file).
            // A blank file value means "use env/default", so we re-apply env/default here.
            if (loaded.getApiUrl() == null || loaded.getApiUrl().trim().isEmpty()) {
                loaded.setApiUrl(envOrDefault("CORTEXDRONE_API_URL", "http://127.0.0.1:7002/v1/chat/completions"));
            }
            if (loaded.getModel() == null || loaded.getModel().trim().isEmpty()) {
                loaded.setModel(envOrDefault("CORTEXDRONE_MODEL", "deepseek/deepseek-v4.1-flash"));
            }
            if (loaded.getApiKey() == null || loaded.getApiKey().trim().isEmpty()) {
                loaded.setApiKey(envOrDefault("CORTEXDRONE_API_KEY", ""));
            }
            if (loaded.getSavedDrones() == null) {
                loaded.setSavedDrones(new ArrayList<>());
            }
            if (loaded.getHttpPort() <= 0) {
                loaded.setHttpPort(25580);
            }
            return loaded;
        } catch (Exception e) {
            e.printStackTrace();
            return new AIConfig();
        }
    }

    public synchronized void save(File configFile, Gson gson, Collection<DroneController> controllers) {
        if (configFile == null) return;
        try {
            Map<String, SavedDroneInfo> byUuid = new LinkedHashMap<>();
            if (this.savedDrones != null) {
                for (SavedDroneInfo old : this.savedDrones) {
                    if (old != null && old.getUuid() != null && !"Server".equalsIgnoreCase(old.getOwner())) {
                        byUuid.put(old.getUuid(), old);
                    }
                }
            }
            if (controllers != null) {
                for (DroneController c : controllers) {
                    if (c.drone == null || c.drone.isDead || c.entityUuid == null || "Server".equalsIgnoreCase(c.ownerName))
                        continue;
                    SavedDroneInfo info = new SavedDroneInfo();
                    info.setUuid(c.entityUuid.toString());
                    info.setName(c.name);
                    info.setOwner(c.ownerName);
                    info.setAutoTorch(c.autoTorch);
                    info.setLedEffect(c.ledEffect);
                    info.setCustomLedColor(c.customLedColor);
                    info.setDimension(c.drone.dimension);
                    info.setX(Math.round(c.drone.posX * 10.0) / 10.0);
                    info.setY(Math.round(c.drone.posY * 10.0) / 10.0);
                    info.setZ(Math.round(c.drone.posZ * 10.0) / 10.0);
                    byUuid.put(info.getUuid(), info);
                }
            }
            this.savedDrones = new ArrayList<>(byUuid.values());
            try (FileWriter writer = new FileWriter(configFile)) {
                gson.toJson(this, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
