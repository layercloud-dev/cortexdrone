package ir.layercloud.cortexdrone.config;

import com.google.gson.Gson;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Synchronized;

public class AIConfig {
    public String apiUrl = envOrDefault("CORTEXDRONE_API_URL", "http://127.0.0.1:7002/v1/chat/completions");
    public String model = envOrDefault("CORTEXDRONE_MODEL", "deepseek/deepseek-v4.1-flash");
    public String apiKey = envOrDefault("CORTEXDRONE_API_KEY", "");
    public int httpPort = 25580;
    public List<SavedDroneInfo> savedDrones = new ArrayList<SavedDroneInfo>();
    public Map<String, double[]> playerBases = new HashMap<String, double[]>();
    public Map<String, SavedLocation> savedLocations = new LinkedHashMap<String, SavedLocation>();

    public static class SavedDroneInfo {
        public String uuid;
        public String name;
        public String owner;
        public String mode;
        public boolean autoTorch;
        public String ledEffect;
        public Integer customLedColor;
        public String guardFilter;
        public int patrolRadius;
        public int dimension;
        public Double x;
        public Double y;
        public Double z;
    }

    public static class SavedLocation {
        public String name;
        public String owner;
        public double x;
        public double y;
        public double z;
        public String tags;
        public long updatedAt;
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

    @Synchronized
    public static AIConfig load(File configFile, Gson gson) {
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
            if (loaded.apiUrl == null || loaded.apiUrl.trim().isEmpty()) {
                loaded.apiUrl = envOrDefault("CORTEXDRONE_API_URL", "http://127.0.0.1:7002/v1/chat/completions");
            }
            if (loaded.model == null || loaded.model.trim().isEmpty()) {
                loaded.model = envOrDefault("CORTEXDRONE_MODEL", "deepseek/deepseek-v4.1-flash");
            }
            if (loaded.apiKey == null || loaded.apiKey.trim().isEmpty()) {
                loaded.apiKey = envOrDefault("CORTEXDRONE_API_KEY", "");
            }
            if (loaded.savedDrones == null) {
                loaded.savedDrones = new ArrayList<SavedDroneInfo>();
            }
            if (loaded.playerBases == null) {
                loaded.playerBases = new HashMap<String, double[]>();
            }
            if (loaded.savedLocations == null) {
                loaded.savedLocations = new LinkedHashMap<String, SavedLocation>();
            }
            if (loaded.httpPort <= 0) {
                loaded.httpPort = 25580;
            }
            return loaded;
        } catch (Exception e) {
            e.printStackTrace();
            return new AIConfig();
        }
    }

    @Synchronized
    public void save(File configFile, Gson gson, Collection<DroneController> controllers) {
        if (configFile == null) return;
        try {
            Map<String, SavedDroneInfo> byUuid = new LinkedHashMap<String, SavedDroneInfo>();
            if (this.savedDrones != null) {
                for (SavedDroneInfo old : this.savedDrones) {
                    if (old != null && old.uuid != null && !"Server".equalsIgnoreCase(old.owner)) {
                        byUuid.put(old.uuid, old);
                    }
                }
            }
            if (controllers != null) {
                for (DroneController c : controllers) {
                    if (c.drone == null || c.drone.field_70128_L || c.entityUuid == null || "Server".equalsIgnoreCase(c.ownerName)) continue;
                    SavedDroneInfo info = new SavedDroneInfo();
                    info.uuid = c.entityUuid.toString();
                    info.name = c.name;
                    info.owner = c.ownerName;
                    info.mode = (c.mode == Mode.MINE || c.mode == Mode.BUILD || c.mode == Mode.SCOOP || c.mode == Mode.SMELT || c.mode == Mode.CARRY || c.mode == Mode.DROP || c.mode == Mode.DEPOSIT)
                        ? Mode.FOLLOW.name()
                        : c.mode.name();
                    info.autoTorch = c.autoTorch;
                    info.ledEffect = c.ledEffect;
                    info.customLedColor = c.customLedColor;
                    info.guardFilter = c.guardFilter;
                    info.patrolRadius = c.patrolRadius;
                    info.dimension = c.drone.field_71093_bK;
                    info.x = Math.round(c.drone.field_70165_t * 10.0) / 10.0;
                    info.y = Math.round(c.drone.field_70163_u * 10.0) / 10.0;
                    info.z = Math.round(c.drone.field_70161_v * 10.0) / 10.0;
                    byUuid.put(info.uuid, info);
                }
            }
            this.savedDrones = new ArrayList<SavedDroneInfo>(byUuid.values());
            try (FileWriter writer = new FileWriter(configFile)) {
                gson.toJson(this, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String normLocationKey(String owner, String name) {
        String o = (owner != null && !owner.trim().isEmpty()) ? owner.trim().toLowerCase() : "global";
        String n = (name != null && !name.trim().isEmpty()) ? name.trim().toLowerCase().replace(' ', '_') : "waypoint";
        return o + ":" + n;
    }

    @Synchronized
    public SavedLocation saveLocation(String owner, String name, double x, double y, double z, String tags) {
        if (name == null || name.trim().isEmpty()) return null;
        if (this.savedLocations == null) {
            this.savedLocations = new LinkedHashMap<String, SavedLocation>();
        }
        String cleanName = name.trim();
        String cleanOwner = (owner != null && !owner.trim().isEmpty()) ? owner.trim() : "global";
        SavedLocation loc = new SavedLocation();
        loc.name = cleanName;
        loc.owner = cleanOwner;
        loc.x = Math.round(x * 10.0) / 10.0;
        loc.y = Math.round(y * 10.0) / 10.0;
        loc.z = Math.round(z * 10.0) / 10.0;
        loc.tags = tags != null ? tags.trim() : "";
        loc.updatedAt = System.currentTimeMillis();
        this.savedLocations.put(normLocationKey(cleanOwner, cleanName), loc);
        this.savedLocations.put(normLocationKey("global", cleanName), loc);
        String lower = cleanName.toLowerCase();
        if (lower.equals("base") || lower.equals("home") || lower.equals("main_base") || lower.equals("main base")) {
            setPlayerBase(cleanOwner, x, y, z);
        }
        return loc;
    }

    @Synchronized
    public SavedLocation getLocation(String owner, String query) {
        if (query == null || query.trim().isEmpty() || this.savedLocations == null) return null;
        String q = query.trim().toLowerCase();
        String qUnderscore = q.replace(' ', '_');
        if (owner != null && !owner.trim().isEmpty()) {
            SavedLocation exactOwner = this.savedLocations.get(normLocationKey(owner, q));
            if (exactOwner != null) return exactOwner;
        }
        SavedLocation exactGlobal = this.savedLocations.get(normLocationKey("global", q));
        if (exactGlobal != null) return exactGlobal;

        // Fuzzy match by name or tags (preferring owner's locations first)
        SavedLocation bestFallback = null;
        for (SavedLocation loc : this.savedLocations.values()) {
            if (loc == null || loc.name == null) continue;
            String ln = loc.name.toLowerCase();
            String lt = loc.tags != null ? loc.tags.toLowerCase() : "";
            if (ln.equals(q) || ln.equals(qUnderscore) || ln.contains(q) || q.contains(ln) || (!lt.isEmpty() && lt.contains(q))) {
                if (owner != null && owner.equalsIgnoreCase(loc.owner)) {
                    return loc;
                }
                if (bestFallback == null) {
                    bestFallback = loc;
                }
            }
        }
        return bestFallback;
    }

    @Synchronized
    public List<SavedLocation> listLocations(String owner) {
        List<SavedLocation> out = new ArrayList<SavedLocation>();
        if (this.savedLocations == null) return out;
        Map<String, SavedLocation> dedup = new LinkedHashMap<String, SavedLocation>();
        for (SavedLocation loc : this.savedLocations.values()) {
            if (loc == null || loc.name == null) continue;
            String key = loc.name.toLowerCase();
            if (!dedup.containsKey(key) || (owner != null && owner.equalsIgnoreCase(loc.owner))) {
                dedup.put(key, loc);
            }
        }
        out.addAll(dedup.values());
        return out;
    }

    @Synchronized
    public boolean deleteLocation(String owner, String name) {
        if (name == null || this.savedLocations == null) return false;
        boolean removed = false;
        if (owner != null) {
            removed |= (this.savedLocations.remove(normLocationKey(owner, name)) != null);
        }
        removed |= (this.savedLocations.remove(normLocationKey("global", name)) != null);
        return removed;
    }

    @Synchronized
    public void setPlayerBase(String playerName, double x, double y, double z) {
        if (playerName == null || playerName.trim().isEmpty()) return;
        if (this.playerBases == null) {
            this.playerBases = new HashMap<String, double[]>();
        }
        this.playerBases.put(playerName.trim().toLowerCase(), new double[]{x, y, z});
    }

    @Synchronized
    public double[] getPlayerBase(String playerName) {
        if (playerName == null || this.playerBases == null) return null;
        double[] coords = this.playerBases.get(playerName.trim().toLowerCase());
        return (coords != null && coords.length >= 3) ? coords : null;
    }
}
