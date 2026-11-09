package ir.layercloud.cortexdrone.config;

import ir.layercloud.cortexdrone.service.text.TextSanitizer;
import lombok.Value;

import java.text.SimpleDateFormat;
import java.util.Date;

@Value
public class DebugEntry {
    long timestamp;
    String time;
    String category;
    int droneId;
    String droneName;
    String message;
    String details;

    public DebugEntry(String category, int droneId, String droneName, String message, String details) {
        this.timestamp = System.currentTimeMillis();
        this.time = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(this.timestamp));
        this.category = category != null ? category : "INFO";
        this.droneId = droneId;
        String safeName = TextSanitizer.chat(droneName);
        this.droneName = safeName.isEmpty() ? "-" : safeName;
        this.message = TextSanitizer.log(message);
        this.details = TextSanitizer.log(details);
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
