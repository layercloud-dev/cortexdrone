package ir.layercloud.cortexdrone.http;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import li.cil.oc.common.entity.Drone;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class DroneHttpServer {
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final DroneTelemetrySerializer telemetrySerializer;
    private HttpServer httpServer;
    private volatile JsonObject cachedStateJson = new JsonObject();
    private volatile JsonObject cachedDebugJson = new JsonObject();

    public DroneHttpServer(AIDroneManager manager, DroneLogger logger) {
        this.manager = manager;
        this.logger = logger;
        this.telemetrySerializer = new DroneTelemetrySerializer(manager, logger);
    }

    public void updateCachedSnapshots() {
        this.cachedStateJson = buildStateJson();
        this.cachedDebugJson = buildDebugJson();
    }

    public void start() {
        stop();
        try {
            int port = this.manager.getConfig().getHttpPort() > 0 ? this.manager.getConfig().getHttpPort() : 25580;
            this.httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);

            this.httpServer.createContext("/state", exchange -> {
                try {
                    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        sendJsonResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                        return;
                    }
                    JsonObject cached = cachedStateJson;
                    if (cached != null && !cached.entrySet().isEmpty()) {
                        sendJsonResponse(exchange, 200, CortexDroneMod.GSON.toJson(cached));
                        return;
                    }
                    final AtomicReference<JsonObject> ref = new AtomicReference<>(null);
                    final CountDownLatch latch = new CountDownLatch(1);
                    manager.getMod().queueMain(() -> {
                        try {
                            JsonObject st = buildStateJson();
                            cachedStateJson = st;
                            ref.set(st);
                        } finally {
                            latch.countDown();
                        }
                    });
                    boolean completed = latch.await(1500, TimeUnit.MILLISECONDS);
                    JsonObject out = completed || ref.get() != null ? ref.get() : cachedStateJson;
                    sendJsonResponse(exchange, 200, CortexDroneMod.GSON.toJson(out));
                } catch (Exception e) {
                    sendJsonResponse(exchange, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                }
            });

            this.httpServer.createContext("/debug", exchange -> {
                try {
                    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        sendJsonResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                        return;
                    }
                    JsonObject cached = cachedDebugJson;
                    if (cached != null && !cached.entrySet().isEmpty()) {
                        sendJsonResponse(exchange, 200, CortexDroneMod.GSON.toJson(cached));
                        return;
                    }
                    final AtomicReference<JsonObject> ref = new AtomicReference<>(null);
                    final CountDownLatch latch = new CountDownLatch(1);
                    manager.getMod().queueMain(() -> {
                        try {
                            JsonObject dbg = buildDebugJson();
                            cachedDebugJson = dbg;
                            ref.set(dbg);
                        } finally {
                            latch.countDown();
                        }
                    });
                    boolean completed = latch.await(1500, TimeUnit.MILLISECONDS);
                    JsonObject out = completed || ref.get() != null ? ref.get() : cachedDebugJson;
                    sendJsonResponse(exchange, 200, CortexDroneMod.GSON.toJson(out));
                } catch (Exception e) {
                    sendJsonResponse(exchange, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                }
            });

            this.httpServer.createContext("/rpc", exchange -> {
                try {
                    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        sendJsonResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                        return;
                    }
                    final JsonObject req = readJsonBody(exchange);
                    int droneId = req.has("droneId") ? req.get("droneId").getAsInt() : -1;
                    String pName = req.has("player") && !req.get("player").isJsonNull() ? req.get("player").getAsString() : null;
                    DroneController ctrl = droneId > 0 ? manager.getControllersById().get(droneId) : manager.getOrAnyDrone(pName);
                    EntityPlayerMP p = pName != null ? CortexDroneMod.findPlayer(pName) : null;
                    String method = req.has("method") ? req.get("method").getAsString() : "";
                    JsonObject args = req.has("args") && req.get("args").isJsonObject() ? req.getAsJsonObject("args") : req;
                    if (req.has("loopId") && !args.has("loopId")) {
                        args.add("loopId", req.get("loopId"));
                    }
                    // executeOnMainThread has its own per-method budget; the
                    // socket read adds ~1s. Log + 504 instead of silently hanging.
                    long rpcT0 = System.currentTimeMillis();
                    final AtomicReference<JsonObject> rpcRef = new AtomicReference<>(null);
                    final CountDownLatch rpcLatch = new CountDownLatch(1);
                    CortexDroneMod.HTTP_POOL.submit(() -> {
                        try {
                            rpcRef.set(manager.getRpcDispatcher().executeOnMainThread(ctrl, p, method, args));
                        } catch (Throwable t) {
                            JsonObject err = new JsonObject();
                            err.addProperty("ok", false);
                            err.addProperty("error", t.toString());
                            rpcRef.set(err);
                        } finally {
                            rpcLatch.countDown();
                        }
                    });
                    if (!rpcLatch.await(60, TimeUnit.SECONDS)) {
                        logger.log("HTTP", ctrl, "RPC '" + method + "' via /rpc TIMEOUT after " + (System.currentTimeMillis() - rpcT0) + "ms", "droneId=" + droneId + " player=" + pName);
                        sendJsonResponse(exchange, 504, "{\"ok\":false,\"error\":\"RPC timeout: " + escapeJson(method) + "\"}");
                        return;
                    }
                    JsonObject res = rpcRef.get();
                    if (res == null) {
                        res = new JsonObject();
                        res.addProperty("ok", false);
                        res.addProperty("error", "RPC returned no result for method: " + method);
                    }
                    sendJsonResponse(exchange, 200, CortexDroneMod.GSON.toJson(res));
                } catch (Exception e) {
                    sendJsonResponse(exchange, 400, "{\"ok\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                }
            });

            this.httpServer.createContext("/action", exchange -> {
                try {
                    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        sendJsonResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                        return;
                    }
                    final JsonObject req = readJsonBody(exchange);
                    String actType = req.has("action") ? req.get("action").getAsString().toLowerCase() : "";
                    if (actType.equals("js") || actType.equals("execute_js")) {
                        int dId = req.has("droneId") ? req.get("droneId").getAsInt() : -1;
                        String pName = req.has("player") && !req.get("player").isJsonNull() ? req.get("player").getAsString() : null;
                        final DroneController ctrl = dId > 0 ? manager.getControllersById().get(dId) : manager.getOrAnyDrone(pName);
                        final EntityPlayerMP p = pName != null ? CortexDroneMod.findPlayer(pName) : null;
                        final String code = req.has("code") ? req.get("code").getAsString() : "";
                        // Bounded: never block the HTTP thread forever on a wedged node.
                        final AtomicReference<JsonObject> jsRef = new AtomicReference<>(null);
                        final CountDownLatch jsLatch = new CountDownLatch(1);
                        CortexDroneMod.HTTP_POOL.submit(() -> {
                            try {
                                jsRef.set(manager.getCodeModeRuntime().executeJavaScript(ctrl, p, code));
                            } catch (Throwable t) {
                                JsonObject err = new JsonObject();
                                err.addProperty("ok", false);
                                err.addProperty("error", t.toString());
                                jsRef.set(err);
                            } finally {
                                jsLatch.countDown();
                            }
                        });
                        if (!jsLatch.await(60, TimeUnit.SECONDS)) {
                            logger.log("HTTP", ctrl, "JS execute via /action TIMEOUT after 60s", "droneId=" + dId + " player=" + pName);
                            sendJsonResponse(exchange, 504, "{\"ok\":false,\"error\":\"JS engine timeout\"}");
                            return;
                        }
                        JsonObject jsRes = jsRef.get();
                        if (jsRes == null) {
                            jsRes = new JsonObject();
                            jsRes.addProperty("ok", false);
                            jsRes.addProperty("error", "JS engine returned no result");
                        }
                        sendJsonResponse(exchange, 200, CortexDroneMod.GSON.toJson(jsRes));
                        return;
                    }
                    final AtomicReference<JsonObject> respRef = new AtomicReference<>(null);
                    final CountDownLatch latch = new CountDownLatch(1);
                    manager.getMod().queueMain(() -> {
                        try {
                            respRef.set(executeHttpAction(req));
                        } catch (Exception ex) {
                            JsonObject err = new JsonObject();
                            err.addProperty("ok", false);
                            err.addProperty("error", ex.getMessage());
                            respRef.set(err);
                        } finally {
                            latch.countDown();
                        }
                    });
                    boolean completed = latch.await(2500, TimeUnit.MILLISECONDS);
                    JsonObject res = completed ? respRef.get() : null;
                    if (res == null) {
                        res = new JsonObject();
                        res.addProperty("ok", true);
                        res.addProperty("queued", true);
                    }
                    sendJsonResponse(exchange, 200, CortexDroneMod.GSON.toJson(res));
                } catch (Exception e) {
                    sendJsonResponse(exchange, 400, "{\"ok\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                }
            });

            this.httpServer.setExecutor(CortexDroneMod.HTTP_SERVER_POOL);
            this.httpServer.start();
            updateCachedSnapshots();
        } catch (Exception e) {
            this.logger.log("HTTP", null, "Failed to start HTTP server", e.toString());
        }
    }

    public void stop() {
        if (this.httpServer != null) {
            try {
                this.httpServer.stop(0);
            } catch (Exception ignored) {
            }
            this.httpServer = null;
        }
    }

    private static JsonObject readJsonBody(HttpExchange exchange) throws IOException {
        StringBuilder body = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                body.append(line).append('\n');
            }
        }
        return new JsonParser().parse(body.toString()).getAsJsonObject();
    }

    private static void sendJsonResponse(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public JsonObject buildDebugJson() {
        return this.telemetrySerializer.buildDebugJson();
    }

    public JsonObject buildStateJson() {
        return this.telemetrySerializer.buildStateJson();
    }

    private JsonObject executeHttpAction(JsonObject req) {
        JsonObject res = new JsonObject();
        String action = req.has("action") ? req.get("action").getAsString().toLowerCase() : "";
        String playerName = req.has("player") && !req.get("player").isJsonNull() ? req.get("player").getAsString() : null;
        EntityPlayerMP player = playerName != null ? CortexDroneMod.findPlayer(playerName) : null;

        if (player == null) {
            MinecraftServer srv = MinecraftServer.getServer();
            if (srv != null && srv.getConfigurationManager() != null
                    && !srv.getConfigurationManager().playerEntityList.isEmpty()) {
                Object first = srv.getConfigurationManager().playerEntityList.get(0);
                if (first instanceof EntityPlayerMP) {
                    player = (EntityPlayerMP) first;
                    if (playerName == null) {
                        playerName = player.getCommandSenderName();
                    }
                }
            }
        }

        if (action.equals("say") || action.equals("chat")) {
            String msg = req.has("message") ? req.get("message").getAsString() : "";
            DroneController ctrl = this.manager.getOrAnyDrone(playerName);
            this.manager.droneSay(player, ctrl, msg);
            res.addProperty("ok", true);
            res.addProperty("action", "say");
            return res;
        }

        if (action.equals("spawn")) {
            String customName = req.has("name") && !req.get("name").isJsonNull() ? req.get("name").getAsString() : null;
            boolean explicitPlayer = req.has("player") && !req.get("player").isJsonNull();
            if (explicitPlayer && player != null && !req.has("x")) {
                DroneController ctrl = this.manager.spawnDroneForPlayer(player, customName);
                res.addProperty("ok", true);
                res.addProperty("droneId", ctrl.id);
                res.addProperty("name", ctrl.name);
                return res;
            }
            MinecraftServer srv = MinecraftServer.getServer();
            if (srv != null && srv.worldServers != null && srv.worldServers.length > 0 && srv.worldServers[0] != null) {
                WorldServer ws = srv.worldServers[0];
                double sx = req.has("x") ? req.get("x").getAsDouble() : ws.getSpawnPoint().posX + 0.5;
                double sy = req.has("y") ? req.get("y").getAsDouble() : ws.getSpawnPoint().posY + 2.0;
                double sz = req.has("z") ? req.get("z").getAsDouble() : ws.getSpawnPoint().posZ + 0.5;
                Drone drone = new Drone(ws);
                String dName = customName != null ? customName : "AI-Drone";
                String owner = explicitPlayer && playerName != null ? playerName : "Server";
                drone.setName(dName);
                drone.ownerName_$eq(owner);
                drone.setPosition(sx, sy, sz);
                drone.preparePowerUp();
                drone.inventorySize_$eq(8);
                drone.lightColor_$eq(0x00FFFF);
                drone.statusText_$eq("AI Ready");
                FlightPathfinder.setDroneFlightTarget(drone, sx, sy, sz, 0.1f);
                this.manager.ensureMachineRunningState(drone);
                ws.spawnEntityInWorld(drone);
                DroneController ctrl = this.manager.registerDroneController(drone, dName, owner);
                this.manager.saveConfig();
                this.logger.log("SPAWN", ctrl, "Spawned drone via HTTP /action", String.format("pos=(%.1f,%.1f,%.1f)", sx, sy, sz));
                res.addProperty("ok", true);
                res.addProperty("droneId", ctrl.id);
                res.addProperty("name", ctrl.name);
                return res;
            }
            res.addProperty("ok", false);
            res.addProperty("error", "No world available to spawn drone");
            return res;
        }

        DroneController ctrl = null;
        if (req.has("droneId")) {
            ctrl = this.manager.getControllersById().get(req.get("droneId").getAsInt());
        }
        if (ctrl == null) {
            ctrl = this.manager.getOrAnyDrone(playerName);
        }
        if (ctrl == null && player != null) {
            ctrl = this.manager.spawnDroneForPlayer(player, null);
        }
        if (ctrl == null) {
            res.addProperty("ok", false);
            res.addProperty("error", "No active AI drone found");
            return res;
        }

        if (action.equals("despawn") || action.equals("remove")) {
            int dropped = this.manager.despawnDrone(ctrl, player);
            res.addProperty("ok", true);
            res.addProperty("droppedItems", dropped);
            return res;
        }
        if (action.equals("instruct") || action.equals("command")) {
            String prompt = req.has("prompt") ? req.get("prompt").getAsString() : "";
            this.manager.handleNaturalInstruction(player, ctrl, prompt);
            res.addProperty("ok", true);
            res.addProperty("loopId", this.manager.getSessionManager().session(ctrl).activeLoopId());
            return res;
        }

        return this.manager.getRpcDispatcher().executeSync(ctrl, player, action, req);
    }
}
