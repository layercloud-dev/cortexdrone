package ir.layercloud.cortexdrone.action.effect;

import ir.layercloud.cortexdrone.action.Action;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.codemode.SkillLibrary;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.LedEffectEngine;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.util.List;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * Handles drone telemetry, chat, LED colors/effects, particle bursts,
 * musical notes/sound effects, and RLM skill library queries.
 */
@RequiredArgsConstructor
public class EffectActionService implements Action {
    private final AIDroneManager manager;
    private final LightingAura lightingAura;

    public EffectActionService(AIDroneManager manager, DroneLogger logger) {
        this.manager = manager;
        this.lightingAura = new LightingAura(logger);
    }

    @Override
    public String category() { return "effect"; }

    @Override
    public String[] operations() {
        return new String[]{"set_led", "led_effect", "spawn_particles", "play_sound", "play_note", "say", "set_status_text", "light"};
    }

    @Override
    public boolean supports(String operation) {
        for (String op : operations()) if (op.equals(operation)) return true;
        return false;
    }

    public JsonObject configureLight(DroneController ctrl, JsonObject args) {
        return this.lightingAura.configure(ctrl, args);
    }

    public void tickLighting(DroneController ctrl) {
        this.lightingAura.tick(ctrl);
    }

    public void clearLighting(DroneController ctrl) {
        this.lightingAura.clearAura(ctrl);
    }

    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        if (m.equals("light") || m.equals("torch")) return this.lightingAura.configure(ctrl, args);
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        Drone d = ctrl.drone;
        World world = d.field_70170_p;

        if (m.equals("get_drone")) {
            if (args.has("loopId") && args.get("loopId").getAsInt() > 0 && ctrl.isLoopCancelled(args.get("loopId").getAsInt())) {
                res.addProperty("aborted", true);
            }
            res.addProperty("id", ctrl.id);
            res.addProperty("name", ctrl.name);
            res.addProperty("owner", ctrl.ownerName);
            res.addProperty("lastThought", ctrl.lastThought != null ? ctrl.lastThought : "");
            res.addProperty("x", d.field_70165_t);
            res.addProperty("y", d.field_70163_u);
            res.addProperty("z", d.field_70161_v);
            res.addProperty("mode", ctrl.mode.name());
            res.addProperty("lightActive", ctrl.autoTorch);
            res.addProperty("lightRadius", ctrl.lightRadius);
            res.addProperty("lightLevel", ctrl.lightIntensity);
            res.addProperty("lightColor", String.format("0x%06X", ctrl.lightColor & 0xFFFFFF));
            res.addProperty("solidEscalations", ctrl.solidEscalations);
            res.addProperty("blockedWaypoints", ctrl.solidWaypointHits.size());
            res.addProperty("mineQueueSize", ctrl.mineQueue.size());
            res.addProperty("mineTargetCount", ctrl.mineTargetCount);
            res.addProperty("mineExactQueueOnly", ctrl.mineExactQueueOnly);
            res.addProperty("mineFilter", ctrl.mineFilter != null ? ctrl.mineFilter : "");
            res.addProperty("selfRescue", ctrl.escapeResume != null);
            res.addProperty("buildQueueSize", ctrl.buildQueue.size());
            res.addProperty("scoopQueueSize", ctrl.scoopQueue.size());
            res.addProperty("minedCount", ctrl.minedCount);
            res.addProperty("builtCount", ctrl.builtCount);
            res.addProperty("huntedCount", ctrl.huntedCount);
            res.addProperty("scoopedCount", ctrl.scoopedCount);
            res.addProperty("smeltedCount", ctrl.smeltedCount);
            JsonArray invArr = new JsonArray();
            IInventory inv = d.mainInventory();
            int usedSlots = 0;
            int totalItems = 0;
            if (inv != null) {
                for (int i = 0; i < inv.func_70302_i_(); i++) {
                    ItemStack st = inv.func_70301_a(i);
                    if (st != null && st.field_77994_a > 0) {
                        usedSlots++;
                        totalItems += st.field_77994_a;
                        invArr.add(ItemClassifier.itemStackToJson(i, st));
                    }
                }
            }
            res.addProperty("freeSlots", Math.max(0, 8 - usedSlots));
            res.addProperty("totalItems", totalItems);
            res.addProperty("isFull", InventoryService.isInventoryFull(inv));
            res.add("inventory", invArr);
            return res;
        }

        if (m.equals("say")) {
            String msg = args.has("message") ? args.get("message").getAsString() : "";
            this.manager.droneSay(player, ctrl, msg);
            return res;
        }

        if (m.equals("set_status_text")) {
            String text = args.has("text") ? args.get("text").getAsString() : "";
            d.statusText_$eq(text);
            res.addProperty("text", text);
            return res;
        }

        if (m.equals("set_led") || m.equals("led_effect")) {
            if (args.has("effect") && !args.get("effect").isJsonNull()) {
                String eff = args.get("effect").getAsString().trim().toUpperCase();
                if (eff.equals("OFF") || eff.equals("NONE") || eff.equals("DEFAULT") || eff.equals("AUTO") || eff.equals("RESET")) {
                    ctrl.ledEffect = "NONE";
                    ctrl.customLedColor = null;
                } else {
                    ctrl.ledEffect = eff;
                    d.lightColor_$eq(ctrl.resolveEffectiveLedColor(d.lightColor()));
                }
                res.addProperty("effect", ctrl.ledEffect);
                res.addProperty("color", String.format("0x%06X", d.lightColor() & 0xFFFFFF));
                return res;
            }
            if (args.has("r") && args.has("g") && args.has("b")) {
                int r = Math.min(255, Math.max(0, args.get("r").getAsInt()));
                int g = Math.min(255, Math.max(0, args.get("g").getAsInt()));
                int b = Math.min(255, Math.max(0, args.get("b").getAsInt()));
                int col = (r << 16) | (g << 8) | b;
                ctrl.customLedColor = col;
                ctrl.ledEffect = "NONE";
                d.lightColor_$eq(col);
                res.addProperty("color", String.format("0x%06X", col));
                return res;
            }
            if (args.has("h")) {
                float h = args.get("h").getAsFloat();
                float s = args.has("s") ? args.get("s").getAsFloat() : 1.0f;
                float v = args.has("v") ? args.get("v").getAsFloat() : 1.0f;
                int col = DroneController.hsvToRgbInt(h, s, v);
                ctrl.customLedColor = col;
                ctrl.ledEffect = "NONE";
                d.lightColor_$eq(col);
                res.addProperty("color", String.format("0x%06X", col));
                return res;
            }
            if (args.has("color") && !args.get("color").isJsonNull()) {
                JsonElement ce = args.get("color");
                if (ce.isJsonPrimitive() && ce.getAsJsonPrimitive().isString()) {
                    String cs = ce.getAsString().trim();
                    String upper = cs.toUpperCase();
                    if (LedEffectEngine.isEffectName(upper)) {
                        ctrl.ledEffect = upper;
                        d.lightColor_$eq(ctrl.resolveEffectiveLedColor(d.lightColor()));
                        res.addProperty("effect", ctrl.ledEffect);
                        res.addProperty("color", String.format("0x%06X", d.lightColor() & 0xFFFFFF));
                        return res;
                    }
                    if (upper.equals("DEFAULT") || upper.equals("AUTO") || upper.equals("RESET")) {
                        ctrl.ledEffect = "NONE";
                        ctrl.customLedColor = null;
                        res.addProperty("effect", "NONE");
                        return res;
                    }
                    Integer parsed = DroneController.parseNamedOrHexColor(cs);
                    int col = parsed != null ? parsed.intValue() : 0x00FFFF;
                    ctrl.customLedColor = col;
                    ctrl.ledEffect = "NONE";
                    d.lightColor_$eq(col);
                    res.addProperty("color", String.format("0x%06X", col));
                    return res;
                }
                int col = ce.getAsInt() & 0xFFFFFF;
                ctrl.customLedColor = col;
                ctrl.ledEffect = "NONE";
                d.lightColor_$eq(col);
                res.addProperty("color", String.format("0x%06X", col));
                return res;
            }
            return res;
        }

        if (m.equals("spawn_particles")) {
            String type = args.has("type") ? args.get("type").getAsString() : "heart";
            int count = args.has("count") ? Math.min(80, Math.max(1, args.get("count").getAsInt())) : 12;
            double spread = args.has("spread") ? args.get("spread").getAsDouble() : 0.35;
            double speed = args.has("speed") ? args.get("speed").getAsDouble() : 0.05;
            double px = args.has("x") ? args.get("x").getAsDouble() : d.field_70165_t;
            double py = args.has("y") ? args.get("y").getAsDouble() : (d.field_70163_u + 0.35);
            double pz = args.has("z") ? args.get("z").getAsDouble() : d.field_70161_v;
            if (world instanceof WorldServer) {
                ((WorldServer) world).func_147487_a(type, px, py, pz, count, spread, spread, spread, speed);
            }
            res.addProperty("spawned", count);
            res.addProperty("type", type);
            return res;
        }

        if (m.equals("play_sound") || m.equals("play_note")) {
            String sound = args.has("sound") ? args.get("sound").getAsString() : "note.pling";
            if (!sound.contains(".")) {
                sound = "note." + sound;
            }
            float volume = args.has("volume") ? args.get("volume").getAsFloat() : 1.0f;
            float pitch;
            if (args.has("semitone")) {
                int semi = Math.max(0, Math.min(24, args.get("semitone").getAsInt()));
                pitch = (float) Math.pow(2.0, (semi - 12.0) / 12.0);
            } else {
                pitch = args.has("pitch") ? args.get("pitch").getAsFloat() : 1.0f;
            }
            world.func_72908_a(d.field_70165_t, d.field_70163_u, d.field_70161_v, sound, volume, pitch);
            res.addProperty("sound", sound);
            res.addProperty("pitch", pitch);
            return res;
        }

        if (m.equals("save_skill")) {
            String name = args.has("name") ? args.get("name").getAsString() : "custom_skill";
            String desc = args.has("description") ? args.get("description").getAsString() : "";
            String code = args.has("code") ? args.get("code").getAsString() : "";
            String tags = args.has("tags") ? args.get("tags").getAsString() : "";
            SkillLibrary.SkillEntry s = this.manager.getSkillLibrary().saveSkill(name, desc, code, tags);
            res.addProperty("saved", s != null);
            if (s != null) {
                res.addProperty("name", s.getName());
            }
            return res;
        }

        if (m.equals("search_skills") || m.equals("list_skills")) {
            String query = args.has("query") ? args.get("query").getAsString() : "";
            int limit = args.has("limit") ? args.get("limit").getAsInt() : 10;
            List<SkillLibrary.SkillEntry> found = this.manager.getSkillLibrary().searchSkills(query, limit);
            res.addProperty("count", found.size());
            res.add("skills", this.manager.getSkillLibrary().toJsonArray(found));
            return res;
        }

        res.addProperty("ok", false);
        res.addProperty("error", "Unhandled effects RPC method: " + m);
        return res;
    }
}
