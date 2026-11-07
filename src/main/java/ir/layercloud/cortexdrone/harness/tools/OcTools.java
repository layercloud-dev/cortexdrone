package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.oc.OcRequest;
import ir.layercloud.cortexdrone.action.oc.OcResult;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.harness.tools.view.OcView;
import ir.layercloud.cortexdrone.manager.oc.impl.OcManager;
import ir.layercloud.cortexdrone.service.oc.OcMethod;
import ir.layercloud.cortexdrone.service.oc.OcState;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The full OpenComputers component API, exposed as tools.
 *
 * <p>Reads ({@code oc_state}, {@code oc_methods}) go through the read-only
 * service; writes ({@code oc_call}, {@code oc_machine}) go through the manager so
 * every mutation is an action. Because the tools are ordinary registry entries,
 * Code Mode gets them as {@code drone.ocState(...)}, {@code drone.ocCall(...)}
 * and friends with no extra wiring.</p>
 */
public final class OcTools implements ToolProvider {
    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("oc_state")
                    .description("Read the drone's OpenComputers machine and component network: address, "
                            + "machine state (running/paused), component count and names, energy buffer, tier, "
                            + "status text, LED colour and the callbacks the component exposes. Read-only.")
                    .parameters(ToolSchema.object()
                            .optionalBoolean("include_methods", "Include the full callback list.", true))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(10_000L)
                    .build(),
            ToolSpec.builder("oc_methods")
                    .description("List or search the OpenComputers component callbacks this drone exposes. "
                            + "Discovered from the live @Callback annotations, so it covers OC and addon "
                            + "components without a fixed table. Read-only.")
                    .parameters(ToolSchema.object()
                            .optionalString("query", "Filter by callback name or documentation text.")
                            .optionalInt("limit", "Maximum callbacks to return, 1..256.", 1, 256, 64)
                            .optionalBoolean("docs", "Include the OC documentation string.", false))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(10_000L)
                    .build(),
            ToolSpec.builder("oc_call")
                    .description("Invoke any OpenComputers callback on the drone component, e.g. move, "
                            + "getOffset, getVelocity, detect, compare, drop, suck, select, count, space, "
                            + "transferTo, tankLevel, drain, fill, getStatusText, setLightColor, place, use. "
                            + "Arguments are positional and match the OC Lua call order. Returns the OC "
                            + "return tuple.")
                    .parameters(ToolSchema.object()
                            .requiredString("method", "Callback name, for example 'detect' or 'move'.")
                            .optionalObject("args", "Positional arguments as {\"1\":..,\"2\":..} or a list.")
                            .optionalString("arguments", "JSON array of positional arguments, if args is not used."))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(30_000L)
                    .build(),
            ToolSpec.builder("oc_machine")
                    .description("Drive the OpenComputers machine lifecycle: start, stop, pause or send a "
                            + "signal to the running OS. This is the write side of the machine, separate "
                            + "from component callbacks.")
                    .parameters(ToolSchema.object()
                            .requiredEnum("control", "Machine control to perform.",
                                    "start", "stop", "pause", "signal")
                            .optionalString("value", "Seconds for pause, signal name for signal.")
                            .optionalObject("payload", "Signal payload values, if control is signal."))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(30_000L)
                    .build()
    );

    private final OcManager manager;
    private final OcView view = new OcView();

    public OcTools(AIDroneManager manager) {
        this.manager = manager.getOcManager();
    }

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    @Override
    public void register(ToolRegistry registry) {
        for (ToolSpec spec : SPECS) {
            final String name = spec.getName();
            registry.bind(name, getClass().getSimpleName(),
                    (context, arguments) -> handle(context.controller(), context.player(), name, arguments));
        }
    }

    private JsonObject handle(DroneController controller, EntityPlayerMP player,
                              String name, JsonObject args) {
        switch (name) {
            case "oc_state":
                return this.view.state(this.manager.inspect(controller), ToolArgs.bool(args, "include_methods", true));
            case "oc_methods":
                return methods(controller, args);
            case "oc_call":
                return this.view.result(this.manager.handle(controller, player, "oc_call", request(args)));
            case "oc_machine":
                return this.view.result(this.manager.handle(controller, player, "oc_machine", machine(args)));
            default:
                return this.view.result(OcResult.failure("Unsupported OpenComputers tool: " + name));
        }
    }

    private JsonObject methods(DroneController controller, JsonObject args) {
        OcState state = this.manager.inspect(controller);
        if (state == null || !state.available()) {
            return this.view.state(state, false);
        }
        String query = ToolArgs.string(args, "query", "");
        List<OcMethod> matched = OcMethod.filter(state.methods(), query);
        int limit = ToolArgs.integer(args, "limit", 64);
        if (matched.size() > limit) matched = new ArrayList<>(matched.subList(0, limit));
        boolean docs = ToolArgs.bool(args, "docs", false);
        JsonObject out = this.view.methods(matched, query);
        if (!docs) {
            JsonArray stripped = new JsonArray();
            for (JsonElement element : out.getAsJsonArray("methods")) {
                JsonObject method = element.getAsJsonObject();
                method.remove("doc");
                stripped.add(method);
            }
            out.add("methods", stripped);
        }
        return out;
    }

    private static OcRequest request(JsonObject args) {
        List<Object> values = positional(ToolArgs.object(args, "args"));
        if (values.isEmpty()) {
            String raw = ToolArgs.string(args, "arguments", "");
            values = parse(raw);
        }
        return OcRequest.builder()
                .method(ToolArgs.string(args, "method", ""))
                .arguments(values)
                .build();
    }

    private static OcRequest machine(JsonObject args) {
        return OcRequest.builder()
                .control(ToolArgs.string(args, "control", ""))
                .value(ToolArgs.string(args, "value", ""))
                .payload(positional(ToolArgs.object(args, "payload")))
                .build();
    }

    /** Accepts {"1":a,"2":b} or {"args":[a,b]} and keeps declaration order. */
    private static List<Object> positional(JsonObject object) {
        List<Object> values = new ArrayList<>();
        if (object == null || object.entrySet().isEmpty()) return values;
        if (object.has("args") && object.get("args").isJsonArray()) {
            for (JsonElement element : object.getAsJsonArray("args")) values.add(decode(element));
            return values;
        }
        List<String> keys = new ArrayList<>();
        for (java.util.Map.Entry<String, JsonElement> entry : object.entrySet()) {
            keys.add(entry.getKey());
        }
        keys.sort(OcTools::byIndex);
        for (String key : keys) values.add(decode(object.get(key)));
        return values;
    }

    private static int byIndex(String left, String right) {
        try {
            return Integer.compare(Integer.parseInt(left), Integer.parseInt(right));
        } catch (Throwable ignored) {
            return left.compareTo(right);
        }
    }

    private static List<Object> parse(String raw) {
        List<Object> values = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return values;
        try {
            JsonElement parsed = new com.google.gson.JsonParser().parse(raw);
            if (parsed != null && parsed.isJsonArray()) {
                for (JsonElement element : parsed.getAsJsonArray()) values.add(decode(element));
            } else if (parsed != null) {
                values.add(decode(parsed));
            }
        } catch (Throwable ignored) {
        }
        return values;
    }

    /** JSON tree to plain Java so OC's Arguments adapter can flatten it. */
    private static Object decode(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) return primitive.getAsBoolean();
            if (primitive.isNumber()) return primitive.getAsDouble();
            return primitive.getAsString();
        }
        if (element.isJsonArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) values.add(decode(item));
            return values;
        }
        JsonObject object = element.getAsJsonObject();
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, JsonElement> entry : object.entrySet()) {
            map.put(entry.getKey(), decode(entry.getValue()));
        }
        return map;
    }
}
