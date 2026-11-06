package ir.layercloud.cortexdrone.service.effect.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.ActionArgs;
import ir.layercloud.cortexdrone.action.ActionJson;
import ir.layercloud.cortexdrone.action.effect.EffectRequest;
import ir.layercloud.cortexdrone.action.effect.EffectResult;
import ir.layercloud.cortexdrone.action.effect.LightingAura;
import ir.layercloud.cortexdrone.action.effect.impl.PlaySoundAction;
import ir.layercloud.cortexdrone.action.effect.impl.SayAction;
import ir.layercloud.cortexdrone.action.effect.impl.SetLedAction;
import ir.layercloud.cortexdrone.action.effect.impl.SetStatusTextAction;
import ir.layercloud.cortexdrone.action.effect.impl.SpawnParticlesAction;
import ir.layercloud.cortexdrone.codemode.SkillLibrary;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.effect.impl.EffectContext;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.ActionService;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.util.List;
import li.cil.oc.common.entity.Drone;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

/**
 * Long-lived effect service. It owns telemetry/skill reads and creates one
 * single-use action per LED/particle/sound/chat request.
 */
public final class EffectService extends ActionService<EffectContext> {
    private final AIDroneManager manager;
    private final LightingAura lightingAura;

    public EffectService(AIDroneManager manager, DroneLogger logger) {
        super("effect", "set_led", "led_effect", "spawn_particles",
            "play_sound", "play_note", "say", "set_status_text");
        this.manager = manager;
        this.lightingAura = new LightingAura(logger);
    }

    @Override
    protected EffectContext createContext(DroneController controller, EntityPlayerMP player,
                                          String operation) {
        return new EffectContext(controller, player, operation);
    }

    @Override
    protected Action<EffectContext> createAction(EffectContext context, String operation,
                                                 JsonObject args) {
        EffectRequest request = parse(args);
        if ("set_led".equals(operation) || "led_effect".equals(operation)) {
            return new SetLedAction(request);
        }
        if ("spawn_particles".equals(operation)) return new SpawnParticlesAction(request);
        if ("play_sound".equals(operation) || "play_note".equals(operation)) {
            return new PlaySoundAction(request);
        }
        if ("say".equals(operation)) return new SayAction(this.manager, request);
        if ("set_status_text".equals(operation)) return new SetStatusTextAction(request);
        throw new IllegalArgumentException("Unsupported effect action: " + operation);
    }

    @Override
    protected JsonObject serialize(EffectContext context) {
        EffectResult result = context.result();
        if (result == null) return ActionJson.error("Effect action produced no result");
        JsonObject response = result.isOk()
            ? ActionJson.ok()
            : ActionJson.error(result.getError());
        ActionJson.addAll(response, result.getFields());
        return response;
    }

    public JsonObject configureLight(DroneController controller, JsonObject args) {
        return this.lightingAura.configure(controller, args);
    }

    public void tickLighting(DroneController controller) {
        this.lightingAura.tick(controller);
    }

    public void clearLighting(DroneController controller) {
        this.lightingAura.clearAura(controller);
    }

    /** Telemetry read used by {@code state}; not a mutating master operation. */
    public JsonObject status(DroneController controller, EntityPlayerMP player, JsonObject args) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return ActionJson.error("No active drone or world");
        }
        JsonObject response = ActionJson.ok();
        Drone drone = controller.drone;
        if (ActionArgs.has(args, "loopId") && args.get("loopId").getAsInt() > 0
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
        response.addProperty("huntedCount", controller.huntedCount);
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

    /** Skill-library reads/writes used by {@code skills}; not master actions. */
    public JsonObject skills(DroneController controller, EntityPlayerMP player,
                             String operation, JsonObject args) {
        if (controller == null) return ActionJson.error("No active drone");
        JsonObject response = ActionJson.ok();
        if ("save_skill".equals(operation)) {
            String name = ActionArgs.string(args, "name", "custom_skill");
            String description = ActionArgs.string(args, "description", "");
            String code = ActionArgs.string(args, "code", "");
            String tags = ActionArgs.string(args, "tags", "");
            SkillLibrary.SkillEntry entry = this.manager.getSkillLibrary()
                .saveSkill(name, description, code, tags);
            response.addProperty("saved", entry != null);
            if (entry != null) response.addProperty("name", entry.getName());
            return response;
        }
        if ("search_skills".equals(operation) || "list_skills".equals(operation)) {
            String query = ActionArgs.string(args, "query", "");
            int limit = ActionArgs.integer(args, "limit", 10);
            List<SkillLibrary.SkillEntry> found = this.manager.getSkillLibrary()
                .searchSkills(query, limit);
            response.addProperty("count", found.size());
            response.add("skills", this.manager.getSkillLibrary().toJsonArray(found));
            return response;
        }
        return ActionJson.error("Unhandled effects RPC method: " + operation);
    }

    private static EffectRequest parse(JsonObject args) {
        boolean coordinates = ActionArgs.has(args, "x")
            && ActionArgs.has(args, "y")
            && ActionArgs.has(args, "z");
        boolean rgb = ActionArgs.has(args, "r")
            && ActionArgs.has(args, "g")
            && ActionArgs.has(args, "b");

        Integer red = rgb ? Integer.valueOf(ActionArgs.clampedInt(args, "r", 0, 0, 255)) : null;
        Integer green = rgb ? Integer.valueOf(ActionArgs.clampedInt(args, "g", 0, 0, 255)) : null;
        Integer blue = rgb ? Integer.valueOf(ActionArgs.clampedInt(args, "b", 0, 0, 255)) : null;

        Float hue = ActionArgs.has(args, "h") ? Float.valueOf(ActionArgs.decimalFloat(args, "h", 0f)) : null;
        Float saturation = ActionArgs.has(args, "s") ? Float.valueOf(ActionArgs.decimalFloat(args, "s", 1f)) : null;
        Float brightness = ActionArgs.has(args, "v") ? Float.valueOf(ActionArgs.decimalFloat(args, "v", 1f)) : null;

        String colorText = null;
        Integer colorValue = null;
        if (ActionArgs.has(args, "color")) {
            if (args.get("color").getAsJsonPrimitive().isString()) {
                colorText = args.get("color").getAsString();
            } else {
                colorValue = Integer.valueOf(args.get("color").getAsInt());
            }
        }

        return EffectRequest.builder()
            .effect(ActionArgs.string(args, "effect", null))
            .red(red)
            .green(green)
            .blue(blue)
            .hue(hue)
            .saturation(saturation)
            .brightness(brightness)
            .colorText(colorText)
            .colorValue(colorValue)
            .type(ActionArgs.string(args, "type", "heart"))
            .count(ActionArgs.clampedInt(args, "count", 12, 1, 80))
            .spread(ActionArgs.decimal(args, "spread", 0.35))
            .speed(ActionArgs.decimal(args, "speed", 0.05))
            .coordinates(coordinates)
            .x(coordinates ? ActionArgs.decimal(args, "x", 0) : 0)
            .y(coordinates ? ActionArgs.decimal(args, "y", 0) : 0)
            .z(coordinates ? ActionArgs.decimal(args, "z", 0) : 0)
            .sound(ActionArgs.string(args, "sound", "note.pling"))
            .volume(ActionArgs.decimalFloat(args, "volume", 1.0f))
            .pitch(ActionArgs.decimalFloat(args, "pitch", 1.0f))
            .semitone(ActionArgs.clampedInt(args, "semitone", -1, -1, 24))
            .message(ActionArgs.string(args, "message", ""))
            .text(ActionArgs.string(args, "text", ""))
            .build();
    }
}
