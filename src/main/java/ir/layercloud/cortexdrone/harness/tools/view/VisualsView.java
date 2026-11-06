package ir.layercloud.cortexdrone.harness.tools.view;

import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.action.visuals.VisualsRequest;
import ir.layercloud.cortexdrone.action.visuals.VisualsResult;
import ir.layercloud.cortexdrone.manager.visuals.impl.VisualsManager;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * JSON translation boundary for the visuals actions. Live status lives in
 * {@link StateView}; skill memory lives in {@link SkillView}.
 */
@RequiredArgsConstructor
public final class VisualsView {
    private final VisualsManager service;
    public JsonObject handle(DroneController controller, EntityPlayerMP player,
                             String operation, JsonObject args) {
        VisualsResult result = this.service.handle(
                controller, player, operation, parse(args));
        return serialize(result);
    }

    private static Integer parseColor(JsonObject args) {
        if (!ToolArgs.has(args, "color")) return null;
        try {
            if (args.get("color").isJsonPrimitive()) {
                if (args.get("color").getAsJsonPrimitive().isNumber()) {
                    return args.get("color").getAsInt() & 0xFFFFFF;
                }
                String value = args.get("color").getAsString().trim();
                if (value.startsWith("#")) value = value.substring(1);
                if (value.startsWith("0x") || value.startsWith("0X")) value = value.substring(2);
                String named = namedColor(value);
                if (named != null) value = named;
                return (int) Long.parseLong(value, 16) & 0xFFFFFF;
            }
            if (args.get("color").isJsonArray()) {
                JsonArray array = args.getAsJsonArray("color");
                if (array.size() >= 3) {
                    int red = clamp(array.get(0).getAsInt());
                    int green = clamp(array.get(1).getAsInt());
                    int blue = clamp(array.get(2).getAsInt());
                    return (red << 16) | (green << 8) | blue;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static String namedColor(String raw) {
        String value = raw == null ? "" : raw.toLowerCase()
                .replace("-", "").replace("_", "").replace(" ", "");
        switch (value) {
            case "white":
            case "normal":
            case "default":
            case "warm":
                return "FFFFFF";
            case "red":
                return "FF3B30";
            case "orange":
                return "FF9500";
            case "yellow":
                return "FFD60A";
            case "green":
                return "34C759";
            case "cyan":
            case "aqua":
                return "32ADE6";
            case "blue":
                return "0A84FF";
            case "purple":
            case "violet":
                return "AF52DE";
            case "pink":
                return "FF2D55";
        }
        return null;
    }

    private static VisualsRequest parse(JsonObject args) {
        boolean coordinates = ToolArgs.has(args, "x")
                && ToolArgs.has(args, "y")
                && ToolArgs.has(args, "z");
        boolean rgb = ToolArgs.has(args, "r")
                && ToolArgs.has(args, "g")
                && ToolArgs.has(args, "b");

        Integer red = rgb ? ToolArgs.clampedInt(args, "r", 0, 0, 255) : null;
        Integer green = rgb ? ToolArgs.clampedInt(args, "g", 0, 0, 255) : null;
        Integer blue = rgb ? ToolArgs.clampedInt(args, "b", 0, 0, 255) : null;

        Integer colorValue = parseColor(args);

        return VisualsRequest.builder()
                .red(red)
                .green(green)
                .blue(blue)
                .colorValue(colorValue)
                .type(ToolArgs.string(args, "type", "heart"))
                .count(ToolArgs.clampedInt(args, "count", 12, 1, 80))
                .spread(ToolArgs.decimal(args, "spread", 0.35))
                .speed(ToolArgs.decimal(args, "speed", 0.05))
                .coordinates(coordinates)
                .x(coordinates ? ToolArgs.decimal(args, "x", 0) : 0)
                .y(coordinates ? ToolArgs.decimal(args, "y", 0) : 0)
                .z(coordinates ? ToolArgs.decimal(args, "z", 0) : 0)
                .sound(ToolArgs.string(args, "sound", "note.pling"))
                .volume(ToolArgs.decimalFloat(args, "volume", 1.0f))
                .pitch(ToolArgs.decimalFloat(args, "pitch", 1.0f))
                .semitone(ToolArgs.clampedInt(args, "semitone", -1, -1, 24))
                .message(ToolArgs.string(args, "message", ""))
                .text(ToolArgs.string(args, "text", ""))
                .lightEnabled(ToolArgs.has(args, "enabled")
                        ? args.get("enabled").getAsBoolean() : null)
                .lightToggle(ToolArgs.has(args, "toggle") && args.get("toggle").getAsBoolean())
                .lightRadius(ToolArgs.has(args, "radius") ? args.get("radius").getAsInt() : null)
                .lightLevel(ToolArgs.has(args, "level") ? args.get("level").getAsInt() : null)
                .lightColor(parseColor(args))
                .build();
    }

    private static JsonObject serialize(VisualsResult result) {
        if (result == null) return ToolJson.error("Visuals action produced no result");
        JsonObject response = result.isOk()
                ? ToolJson.ok()
                : ToolJson.error(result.getError());
        ToolJson.addAll(response, result.getFields());
        return response;
    }
}
