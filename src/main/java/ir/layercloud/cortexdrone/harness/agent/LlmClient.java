package ir.layercloud.cortexdrone.harness.agent;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.config.ModConfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal OpenAI-compatible chat-completions client with native tool calling.
 *
 * <p>The harness sends the registered tool schemas in {@code tools} and reads
 * {@code message.tool_calls}. It never asks the model to emit a JSON envelope
 * in prose. Legacy {@code functions}/{@code function_call} gateways are
 * supported as a one-time fallback when the server rejects {@code tools}.</p>
 */
public final class LlmClient {
    private final ModConfig config;
    private final DroneLogger logger;

    public LlmClient(ModConfig config, DroneLogger logger) {
        this.config = config;
        this.logger = logger;
    }

    public LlmResponse chat(DroneController ctrl, List<JsonObject> messages,
                            JsonArray tools, boolean jsonMode) throws Exception {
        boolean legacy = false;
        boolean legacyTried = false;
        int attempt = 0;
        Exception last = null;
        while (attempt < 3) {
            attempt++;
            try {
                JsonObject response = request(ctrl, messages, tools, jsonMode, legacy);
                LlmResponse parsed = parse(response);
                if (parsed != null) return parsed;
                last = new IllegalStateException("Gateway returned no usable assistant message.");
            } catch (GatewayException gateway) {
                last = gateway;
                String lower = String.valueOf(gateway.getMessage()).toLowerCase();
                boolean toolsRejected = gateway.httpCode() == 400 || gateway.httpCode() == 404
                        || gateway.httpCode() == 422;
                if (!legacy && !legacyTried && toolsRejected && tools != null
                        && (lower.contains("tool") || lower.contains("function"))) {
                    legacy = true;
                    legacyTried = true;
                    this.logger.log("LLM", ctrl,
                            "Gateway rejected native tools; retrying with legacy functions",
                            gateway.getMessage());
                    continue;
                }
                if (gateway.httpCode() == 401 || gateway.httpCode() == 403 || toolsRejected) throw gateway;
                if (attempt >= 3) throw gateway;
                sleep(400L * attempt);
            }
        }
        throw last != null ? last : new IllegalStateException("LLM request failed");
    }

    private JsonObject request(DroneController ctrl, List<JsonObject> messages, JsonArray tools,
                               boolean jsonMode, boolean legacy) throws Exception {
        URL url = new URL(this.config.getApiUrl());
        boolean local = this.config.getApiUrl().contains("127.0.0.1")
                || this.config.getApiUrl().contains("localhost");
        HttpURLConnection connection = (HttpURLConnection) (local
                ? url.openConnection(Proxy.NO_PROXY) : url.openConnection());
        connection.setUseCaches(false);
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(180_000);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Connection", "close");
        if (this.config.getApiKey() != null && !this.config.getApiKey().isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + this.config.getApiKey());
        }
        connection.setDoOutput(true);

        JsonObject request = new JsonObject();
        request.addProperty("model", this.config.getModel());
        request.addProperty("temperature", this.config.getTemperature());
        request.addProperty("max_tokens", this.config.getMaxTokens());
        request.add("messages", toArray(messages));
        if (tools != null && tools.size() > 0) {
            if (legacy) {
                JsonArray functions = new JsonArray();
                for (JsonElement tool : tools) {
                    if (tool.isJsonObject() && tool.getAsJsonObject().has("function")) {
                        functions.add(tool.getAsJsonObject().get("function"));
                    }
                }
                request.add("functions", functions);
                request.addProperty("function_call", "auto");
            } else {
                request.add("tools", tools);
                request.addProperty("tool_choice", "auto");
            }
        }
        if (jsonMode) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            request.add("response_format", format);
        }

        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }

        int httpCode = connection.getResponseCode();
        InputStream stream = httpCode >= 200 && httpCode < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String raw = read(stream);
        if (httpCode < 200 || httpCode >= 300) {
            throw new GatewayException(httpCode, "LLM gateway HTTP " + httpCode
                    + (raw.isEmpty() ? "" : " - " + truncate(raw, 900)));
        }
        try {
            JsonElement parsed = new JsonParser().parse(raw);
            if (parsed != null && parsed.isJsonObject()) return parsed.getAsJsonObject();
            throw new GatewayException(httpCode, "Gateway returned non-object JSON: " + truncate(raw, 500));
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException(httpCode, "Gateway returned invalid JSON: " + truncate(raw, 500));
        } finally {
            connection.disconnect();
        }
    }

    private LlmResponse parse(JsonObject response) {
        if (!response.has("choices") || !response.get("choices").isJsonArray()
                || response.getAsJsonArray("choices").size() == 0) {
            return null;
        }
        JsonObject choice = response.getAsJsonArray("choices").get(0).getAsJsonObject();
        JsonObject message = choice.has("message") && choice.get("message").isJsonObject()
                ? choice.getAsJsonObject("message") : new JsonObject();
        String content = text(message.get("content"));
        String reasoning = text(message.get("reasoning_content"));
        if (reasoning.isEmpty()) reasoning = text(message.get("reasoning"));
        String finish = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
                ? choice.get("finish_reason").getAsString() : "";

        List<ToolCall> calls = new ArrayList<>();
        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            for (JsonElement element : message.getAsJsonArray("tool_calls")) {
                if (!element.isJsonObject()) continue;
                JsonObject call = element.getAsJsonObject();
                String id = call.has("id") && !call.get("id").isJsonNull()
                        ? call.get("id").getAsString() : "call_" + System.nanoTime();
                JsonObject function = call.has("function") && call.get("function").isJsonObject()
                        ? call.getAsJsonObject("function") : null;
                if (function == null || !function.has("name")) continue;
                String name = function.get("name").getAsString();
                String arguments = arguments(function.get("arguments"));
                calls.add(new ToolCall(id, name, arguments));
            }
        }
        if (calls.isEmpty() && message.has("function_call") && message.get("function_call").isJsonObject()) {
            JsonObject function = message.getAsJsonObject("function_call");
            if (function.has("name")) {
                calls.add(new ToolCall("call_" + System.nanoTime(),
                        function.get("name").getAsString(), arguments(function.get("arguments"))));
            }
        }
        JsonObject usage = response.has("usage") && response.get("usage").isJsonObject()
                ? response.getAsJsonObject("usage") : null;
        return new LlmResponse(content, reasoning, calls, finish, usage);
    }

    private static String arguments(JsonElement value) {
        if (value == null || value.isJsonNull()) return "{}";
        if (value.isJsonPrimitive()) {
            String text = value.getAsString();
            return text.trim().isEmpty() ? "{}" : text;
        }
        return value.toString();
    }

    private static String text(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsString();
        if (value.isJsonArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonElement part : value.getAsJsonArray()) {
                if (part.isJsonObject()) {
                    JsonObject object = part.getAsJsonObject();
                    if (object.has("text")) builder.append(object.get("text").getAsString());
                    else if (object.has("content")) builder.append(text(object.get("content")));
                } else if (part.isJsonPrimitive()) {
                    builder.append(part.getAsString());
                }
            }
            return builder.toString();
        }
        return "";
    }

    private static JsonArray toArray(List<JsonObject> messages) {
        JsonArray array = new JsonArray();
        for (JsonObject message : messages) array.add(message);
        return array;
    }

    private static String read(InputStream stream) {
        if (stream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
        } catch (Exception ignored) {
        }
        return builder.toString().trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** HTTP failure with the status code exposed for fallback decisions. */
    public static final class GatewayException extends Exception {
        private final int httpCode;

        GatewayException(int httpCode, String message) {
            super(message);
            this.httpCode = httpCode;
        }

        public int httpCode() {
            return this.httpCode;
        }
    }
}
