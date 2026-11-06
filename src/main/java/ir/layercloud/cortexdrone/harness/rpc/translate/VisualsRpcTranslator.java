package ir.layercloud.cortexdrone.harness.rpc.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.visuals.VisualsRequest;
import ir.layercloud.cortexdrone.action.visuals.VisualsResult;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.harness.SkillLibrary;
import ir.layercloud.cortexdrone.manager.visuals.impl.VisualsManager;
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

    private static Integer parseLightColor(JsonObject args) {
        if (!RpcArgs.has(args, "color")) return null;
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

    public JsonObject status(DroneController controller, JsonObject args) {
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
        response.addProperty("navigationActive",
                this.manager.getNavigationManager().isActive(controller));
        response.addProperty("lightActive", controller.autoTorch);
        response.addProperty("lightRadius", controller.lightRadius);
        response.addProperty("lightLevel", controller.lightIntensity);
        response.addProperty("lightColor", String.format("0x%06X", controller.lightColor & 0xFFFFFF));
        response.addProperty("solidEscalations", controller.solidEscalations);
        response.addProperty("blockedWaypoints", controller.solidWaypointHits.size());

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

    public JsonObject skills(DroneController controller,
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

        Integer red = rgb ? RpcArgs.clampedInt(args, "r", 0, 0, 255) : null;
        Integer green = rgb ? RpcArgs.clampedInt(args, "g", 0, 0, 255) : null;
        Integer blue = rgb ? RpcArgs.clampedInt(args, "b", 0, 0, 255) : null;

        Float hue = RpcArgs.has(args, "h")
                ? RpcArgs.decimalFloat(args, "h", 0f) : null;
        Float saturation = RpcArgs.has(args, "s")
                ? RpcArgs.decimalFloat(args, "s", 1f) : null;
        Float brightness = RpcArgs.has(args, "v")
                ? RpcArgs.decimalFloat(args, "v", 1f) : null;

        String colorText = null;
        Integer colorValue = null;
        if (RpcArgs.has(args, "color")) {
            if (args.get("color").getAsJsonPrimitive().isString()) {
                colorText = args.get("color").getAsString();
            } else {
                colorValue = args.get("color").getAsInt();
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
                .lightEnabled(RpcArgs.has(args, "enabled")
                        ? args.get("enabled").getAsBoolean() : null)
                .lightToggle(RpcArgs.has(args, "toggle") && args.get("toggle").getAsBoolean())
                .lightRadius(RpcArgs.has(args, "radius") ? args.get("radius").getAsInt() : null)
                .lightLevel(RpcArgs.has(args, "level") ? args.get("level").getAsInt() : null)
                .lightColor(parseLightColor(args))
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
