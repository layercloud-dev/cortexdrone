package ir.layercloud.cortexdrone.model;

import com.google.gson.JsonObject;
import lombok.Value;

@Value
public class ChatRecord {
    long timestamp;
    String sender;
    String message;

    public ChatRecord(String sender, String message) {
        this.timestamp = System.currentTimeMillis();
        this.sender = sender != null ? sender : "Unknown";
        this.message = message != null ? message : "";
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("timestamp", this.timestamp);
        o.addProperty("sender", this.sender);
        o.addProperty("message", this.message);
        return o;
    }
}
