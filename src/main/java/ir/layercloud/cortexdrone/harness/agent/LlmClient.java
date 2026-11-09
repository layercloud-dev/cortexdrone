package ir.layercloud.cortexdrone.harness.agent;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.config.ModConfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
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
 * in prose and does not fall back to the retired {@code functions} protocol.</p>
 */
public final class LlmClient {
    /** HTTP read timeout for a model response. */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 180_000;
    private final ModConfig config;
    private final DroneLogger logger;

    public LlmClient(ModConfig config, DroneLogger logger) {
        this.config = config;
        this.logger = logger;
    }

    private static final int MAX_ATTEMPTS = 7;

    public LlmResponse chat(DroneController ctrl, List<JsonObject> messages,
                            JsonArray tools, boolean jsonMode) throws Exception {
        return chat(ctrl, messages, tools, jsonMode, 0);
    }

    /**
     * Same call with a per-turn completion budget.
     *
     * <p>{@code maxTokensOverride <= 0} uses the configured value. The agent
     * loop raises the budget when a thinking model consumed the whole previous
     * completion on hidden reasoning and returned no tool call.</p>
     */
    public LlmResponse chat(DroneController ctrl, List<JsonObject> messages,
                            JsonArray tools, boolean jsonMode,
                            int maxTokensOverride) throws Exception {
        int attempt = 0;
        Exception last = null;
        int budget = maxTokensOverride;
        boolean budgetFellBack = false;
        while (attempt < MAX_ATTEMPTS) {
            attempt++;
            try {
                JsonObject response = request(ctrl, messages, tools, jsonMode, budget);
                LlmResponse parsed = parse(response);
                if (parsed != null) return parsed;
                last = new IllegalStateException("Gateway returned no usable assistant message.");
            } catch (Exception failure) {
                last = failure;
                if (failure instanceof GatewayException) {
                    GatewayException gateway = (GatewayException) failure;
                    if (!budgetFellBack && budget > this.config.getMaxTokens()
                            && isTokenBudgetRejection(gateway)) {
                        // Recovery widened the budget past what this model accepts.
                        // Fall back to the configured value instead of failing the task.
                        budgetFellBack = true;
                        budget = this.config.getMaxTokens();
                        if (this.logger != null) {
                            this.logger.log("LLM", ctrl,
                                    "Gateway rejected the raised max_tokens; using the configured budget",
                                    gateway.getMessage());
                        }
                        continue;
                    }
                }
                boolean retryable = failure instanceof GatewayException
                        ? isTransient((GatewayException) failure)
                        : isNetworkFailure(failure);
                if (!retryable || attempt >= MAX_ATTEMPTS) throw failure;
                if (this.logger != null) {
                    this.logger.log("LLM", ctrl, "Transient LLM failure, retrying",
                            "attempt=" + attempt + "/" + MAX_ATTEMPTS + " " + failure);
                }
                // Back off further each time, capped so one bad gateway cannot
                // hold a task forever: 1s, 4s, 9s, 15s, ...
                sleep(Math.min(15_000L, 1_000L * attempt * attempt));
            }
        }
        throw last != null ? last : new IllegalStateException("LLM request failed");
    }

    /**
     * Retry only what can plausibly succeed on a second try.
     *
     * <p>A 5xx or a "model not available / all upstreams failed" 403 is the
     * gateway briefly failing to place the request. A bad key or malformed body
     * is not, and retrying it just delays the error the player needs to see.</p>
     */
    private static boolean isTransient(GatewayException gateway) {
        int code = gateway.httpCode();
        if (code >= 500) return true;
        if (code == 408 || code == 429) return true;
        if (code != 403) return false;
        String message = gateway.getMessage() != null
                ? gateway.getMessage().toLowerCase() : "";
        return message.contains("model_not_available")
                || message.contains("all_upstreams_failed")
                || message.contains("upstream")
                || message.contains("overload")
                || message.contains("timeout");
    }

    /**
     * True for a dropped connection, a TLS reset or a read timeout.
     *
     * <p>The gateway sometimes accepts the request and then closes the socket
     * without a response ("Unexpected end of file from server"). That used to
     * escape the retry loop because it is not an HTTP status, so a single
     * upstream hiccup ended the task with {@code llm_error}.</p>
     */
    static boolean isNetworkFailure(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++) {
            if (cause instanceof java.io.EOFException
                    || cause instanceof java.net.SocketException
                    || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.ConnectException
                    || cause instanceof javax.net.ssl.SSLException
                    || cause instanceof java.io.InterruptedIOException) {
                return true;
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return false;
    }

    /**
     * True when the gateway refused the request because {@code max_tokens} is
     * above the model's ceiling.
     *
     * <p>The agent loop raises the budget when a thinking model returns an
     * empty completion. A model that caps output below the retry budget would
     * otherwise turn that recovery into a hard 400 error.</p>
     */
    static boolean isTokenBudgetRejection(GatewayException gateway) {
        if (gateway == null || gateway.httpCode() != 400) return false;
        String message = gateway.getMessage() != null
                ? gateway.getMessage().toLowerCase(java.util.Locale.ROOT) : "";
        return message.contains("max_tokens")
                || message.contains("max tokens")
                || message.contains("max_completion_tokens")
                || message.contains("maximum output tokens");
    }

    /**
     * True when the gateway is on this machine or a private network, in which
     * case a system proxy must be bypassed. A public gateway keeps the default
     * proxy path so corporate proxies still work.
     */
    static boolean isLocalGateway(String apiUrl) {
        return ir.layercloud.cortexdrone.config.PrivateHosts.isPrivateUrl(apiUrl);
    }

    private JsonObject request(DroneController ctrl, List<JsonObject> messages, JsonArray tools,
                               boolean jsonMode, int maxTokensOverride) throws Exception {
        URL url = new URL(this.config.getApiUrl());
        boolean local = isLocalGateway(this.config.getApiUrl());
        HttpURLConnection connection = (HttpURLConnection) (local
                ? url.openConnection(Proxy.NO_PROXY) : url.openConnection());
        connection.setUseCaches(false);
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Connection", "close");
        if (this.config.getApiKey() != null && !this.config.getApiKey().isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + this.config.getApiKey());
        }
        connection.setDoOutput(true);

        JsonObject request = new JsonObject();
        request.addProperty("model", this.config.getModel());
        request.addProperty("temperature", this.config.getTemperature());
        request.addProperty("max_tokens", maxTokensOverride > 0
                ? maxTokensOverride : this.config.getMaxTokens());
        request.add("messages", toArray(messages));
        if (tools != null && tools.size() > 0) {
            request.add("tools", tools);
            request.addProperty("tool_choice", "auto");
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
        try {
            return readBody(stream);
        } catch (IOException failure) {
            // The caller turns this into a GatewayException with the real reason.
            throw new IllegalStateException("Could not read the gateway response", failure);
        }
    }

    /**
     * Reads a response body to completion.
     *
     * <p>A dropped connection must surface as an error, not as a short "valid"
     * string: a truncated body used to fail later with a misleading
     * "invalid JSON" message.</p>
     */
    static String readBody(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
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

    /** HTTP failure with the status code exposed for retry decisions. */
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
