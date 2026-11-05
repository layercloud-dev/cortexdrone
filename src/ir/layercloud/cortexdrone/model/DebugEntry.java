package ir.layercloud.cortexdrone.model;

import com.google.gson.JsonObject;
import java.text.SimpleDateFormat;
import java.util.Date;

public class DebugEntry {
    public final long timestamp;
    public final String time;
    public final String category;
    public final int droneId;
    public final String droneName;
    public final String message;
    public final String details;

    public DebugEntry(String category, int droneId, String droneName, String message, String details) {
        this.timestamp = System.currentTimeMillis();
        this.time = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(this.timestamp));
        this.category = category != null ? category : "INFO";
        this.droneId = droneId;
        this.droneName = droneName != null ? droneName : "-";
        this.message = message != null ? message : "";
        this.details = details != null ? details : "";
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("timestamp", this.timestamp);
        o.addProperty("time", this.time);
        o.addProperty("category", this.category);
        o.addProperty("droneId", this.droneId);
        o.addProperty("droneName", this.droneName);
        o.addProperty("message", this.message);
        if (!this.details.isEmpty()) {
            o.addProperty("details", this.details);
        }
        return o;
    }

    public String toLogLine() {
        return String.format(
            "[%s] [%s] [Drone#%d:%s] %s%s",
            this.time,
            this.category,
            this.droneId,
            this.droneName,
            this.message,
            this.details.isEmpty() ? "" : " | " + this.details
        );
    }
}
