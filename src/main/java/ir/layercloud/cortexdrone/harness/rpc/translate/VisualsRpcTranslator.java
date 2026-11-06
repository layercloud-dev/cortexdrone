package ir.layercloud.cortexdrone.harness.rpc.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.visuals.VisualsRequest;
import ir.layercloud.cortexdrone.action.visuals.VisualsResult;
import ir.layercloud.cortexdrone.harness.SkillLibrary;
import ir.layercloud.cortexdrone.manager.visuals.LightConfig;
import ir.layercloud.cortexdrone.manager.visuals.LightResult;
import ir.layercloud.cortexdrone.manager.visuals.impl.VisualsManager;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * JSON translation boundary for visuals actions and visuals/telemetry queries.
 */
@RequiredArgsConstructor
public final class VisualsRpcTranslator {
    private final AIDroneManager manager;
    private final VisualsManager service;

    public JsonObject handle(DroneController controller, EntityPlayerMP player,
                             String operation, JsonObject args) {
        VisualsResult result = this.service.handle(
                controller, player, operation, parse(args));
        return serialize(result);
    }

    public JsonObject configureLight(DroneController controller, JsonObject args) {
        LightResult result = this.service.lighting().configure(controller, parseLight(args));
        if (!result.isOk()) return RpcJson.error(result.getError());
        JsonObject response = RpcJson.ok();
        response.addProperty("active", result.isActive());
        response.addProperty("radius", result.getRadius());
        response.addProperty("level", result.getLevel());
        response.addProperty("color", String.format("0x%06X", result.getColor() & 0xFFFFFF));
        response.addProperty("cells", result.getCells());
        response.addProperty("torchFree", result.isTorchFree());
        return response;
    }

    private static LightConfig parseLight(JsonObject args) {
        Boolean enabled = RpcArgs.has(args, "enabled")
                ? Boolean.valueOf(args.get("enabled").getAsBoolean()) : null;
        return LightConfig.builder()
                .enabled(enabled)
                .toggle(RpcArgs.has(args, "toggle") && args.get("toggle").getAsBoolean())
                .radius(RpcArgs.has(args, "radius") ? Integer.valueOf(args.get("radius").getAsInt()) : null)
                .level(RpcArgs.has(args, "level") ? Integer.valueOf(args.get("level").getAsInt()) : null)
                .color(parseLightColor(args))
                .build();
    }

    private static Integer parseLightColor(JsonObject args) {
        if (!RpcArgs.has(args, "color")) return null;
        try {
            if (args.get("color").isJsonPrimitive()) {
                if (args.get("color").getAsJsonPrimitive().isNumber()) {
                    return Integer.valueOf(args.get("color").getAsInt() & 0xFFFFFF);
                }
                String value = args.get("color").getAsString().trim();
                if (value.startsWith("#")) value = value.substring(1);
                if (value.startsWith("0x") || value.startsWith("0X")) value = value.substring(2);
                String named = namedColor(value);
                if (named != null) value = named;
                return Integer.valueOf((int) Long.parseLong(value, 16) & 0xFFFFFF);
            }
            if (args.get("color").isJsonArray()) {
                JsonArray array = args.getAsJsonArray("color");
                if (array.size() >= 3) {
                    int red = clamp(array.get(0).getAsInt(), 0, 255);
                    int green = clamp(array.get(1).getAsInt(), 0, 255);
                    int blue = clamp(array.get(2).getAsInt(), 0, 255);
                    return Integer.valueOf((red << 16) | (green << 8) | blue);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String namedColor(String raw) {
        String value = raw == null ? "" : raw.toLowerCase()
                .replace("-", "").replace("_", "").replace(" ", "");
        if ("white".equals(value) || "normal".equals(value)
                || "default".equals(value) || "warm".equals(value)) return "FFFFFF";
        if ("red".equals(value)) return "FF3B30";
        if ("orange".equals(value)) return "FF9500";
        if ("yellow".equals(value)) return "FFD60A";
        if ("green".equals(value)) return "34C759";
        if ("cyan".equals(value) || "aqua".equals(value)) return "32ADE6";
        if ("blue".equals(value)) return "0A84FF";
        if ("purple".equals(value) || "violet".equals(value)) return "AF52DE";
        if ("pink".equals(value)) return "FF2D55";
        return null;
    }

    public JsonObject status(DroneController controller, EntityPlayerMP player, JsonObject args) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return RpcJson.error("No active drone or world");
        }
        JsonObject response = RpcJson.ok();
        Drone drone = controller.drone;
        if (RpcArgs.has(args, "loopId") && args.get("loopId").getAsInt() > 0
                && controller.isLoopCancelled(args.get("loopId").getAsInt())) {
            response.addProperty("aborted", true);
        }
        response.addProperty("id", controller.id);
        response.addProperty("name", controller.name);
        response.addProperty("owner", controller.ownerName);
        response.addProperty("lastThought", controller.lastThought != null ? controller.lastThought : "");
        response.addProperty("x", drone.posX);
        response.addProperty("y", drone.posY);
        response.addProperty("z", drone.posZ);
        response.addProperty("mode", controller.mode.name());
        response.addProperty("lightActive", controller.autoTorch);
        response.addProperty("lightRadius", controller.lightRadius);
        response.addProperty("lightLevel", controller.lightIntensity);
        response.addProperty("lightColor", String.format("0x%06X", controller.lightColor & 0xFFFFFF));
        response.addProperty("solidEscalations", controller.solidEscalations);
        response.addProperty("blockedWaypoints", controller.solidWaypointHits.size());
        response.addProperty("mineQueueSize", controller.mineQueue.size());
        response.addProperty("mineTargetCount", controller.mineTargetCount);
        response.addProperty("mineExactQueueOnly", controller.mineExactQueueOnly);
        response.addProperty("mineFilter", controller.mineFilter != null ? controller.mineFilter : "");
        response.addProperty("selfRescue", controller.escapeResume != null);
        response.addProperty("minedCount", controller.minedCount);
        response.addProperty("builtCount", controller.builtCount);
        response.addProperty("scoopedCount", controller.scoopedCount);

        JsonArray inventory = new JsonArray();
        IInventory inv = drone.mainInventory();
        int usedSlots = 0;
        int totalItems = 0;
        if (inv != null) {
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack != null && stack.stackSize > 0) {
                    usedSlots++;
                    totalItems += stack.stackSize;
                    inventory.add(ItemClassifier.itemStackToJson(i, stack));
                }
            }
        }
        response.addProperty("freeSlots", Math.max(0, 8 - usedSlots));
        response.addProperty("totalItems", totalItems);
        response.addProperty("isFull", InventoryService.isInventoryFull(inv));
        response.add("inventory", inventory);
        return response;
    }

    public JsonObject skills(DroneController controller, EntityPlayerMP player,
                             String operation, JsonObject args) {
        if (controller == null) return RpcJson.error("No active drone");
        JsonObject response = RpcJson.ok();
        if ("save_skill".equals(operation)) {
            String name = RpcArgs.string(args, "name", "custom_skill");
            String description = RpcArgs.string(args, "description", "");
            String code = RpcArgs.string(args, "code", "");
            String tags = RpcArgs.string(args, "tags", "");
            SkillLibrary.SkillEntry entry = this.manager.getSkillLibrary()
                    .saveSkill(name, description, code, tags);
            response.addProperty("saved", entry != null);
            if (entry != null) response.addProperty("name", entry.getName());
            return response;
        }
        if ("search_skills".equals(operation) || "list_skills".equals(operation)) {
            String query = RpcArgs.string(args, "query", "");
            int limit = RpcArgs.integer(args, "limit", 10);
            List<SkillLibrary.SkillEntry> found = this.manager.getSkillLibrary()
                    .searchSkills(query, limit);
            response.addProperty("count", found.size());
            response.add("skills", this.manager.getSkillLibrary().toJsonArray(found));
            return response;
        }
        return RpcJson.error("Unhandled visuals RPC method: " + operation);
    }

    private static VisualsRequest parse(JsonObject args) {
        boolean coordinates = RpcArgs.has(args, "x")
                && RpcArgs.has(args, "y")
                && RpcArgs.has(args, "z");
        boolean rgb = RpcArgs.has(args, "r")
                && RpcArgs.has(args, "g")
                && RpcArgs.has(args, "b");

        Integer red = rgb ? Integer.valueOf(RpcArgs.clampedInt(args, "r", 0, 0, 255)) : null;
        Integer green = rgb ? Integer.valueOf(RpcArgs.clampedInt(args, "g", 0, 0, 255)) : null;
        Integer blue = rgb ? Integer.valueOf(RpcArgs.clampedInt(args, "b", 0, 0, 255)) : null;

        Float hue = RpcArgs.has(args, "h")
                ? Float.valueOf(RpcArgs.decimalFloat(args, "h", 0f)) : null;
        Float saturation = RpcArgs.has(args, "s")
                ? Float.valueOf(RpcArgs.decimalFloat(args, "s", 1f)) : null;
        Float brightness = RpcArgs.has(args, "v")
                ? Float.valueOf(RpcArgs.decimalFloat(args, "v", 1f)) : null;

        String colorText = null;
        Integer colorValue = null;
        if (RpcArgs.has(args, "color")) {
            if (args.get("color").getAsJsonPrimitive().isString()) {
                colorText = args.get("color").getAsString();
            } else {
                colorValue = Integer.valueOf(args.get("color").getAsInt());
            }
        }

        return VisualsRequest.builder()
                .effect(RpcArgs.string(args, "effect", null))
                .red(red)
                .green(green)
                .blue(blue)
                .hue(hue)
                .saturation(saturation)
                .brightness(brightness)
                .colorText(colorText)
                .colorValue(colorValue)
                .type(RpcArgs.string(args, "type", "heart"))
                .count(RpcArgs.clampedInt(args, "count", 12, 1, 80))
                .spread(RpcArgs.decimal(args, "spread", 0.35))
                .speed(RpcArgs.decimal(args, "speed", 0.05))
                .coordinates(coordinates)
                .x(coordinates ? RpcArgs.decimal(args, "x", 0) : 0)
                .y(coordinates ? RpcArgs.decimal(args, "y", 0) : 0)
                .z(coordinates ? RpcArgs.decimal(args, "z", 0) : 0)
                .sound(RpcArgs.string(args, "sound", "note.pling"))
                .volume(RpcArgs.decimalFloat(args, "volume", 1.0f))
                .pitch(RpcArgs.decimalFloat(args, "pitch", 1.0f))
                .semitone(RpcArgs.clampedInt(args, "semitone", -1, -1, 24))
                .message(RpcArgs.string(args, "message", ""))
                .text(RpcArgs.string(args, "text", ""))
                .build();
    }

    private static JsonObject serialize(VisualsResult result) {
        if (result == null) return RpcJson.error("Visuals action produced no result");
        JsonObject response = result.isOk()
                ? RpcJson.ok()
                : RpcJson.error(result.getError());
        RpcJson.addAll(response, result.getFields());
        return response;
    }
}
