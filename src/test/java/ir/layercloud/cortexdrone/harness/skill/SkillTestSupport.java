package ir.layercloud.cortexdrone.harness.skill;

import ir.layercloud.cortexdrone.service.inventory.InventoryLimits;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.harness.js.JsEngine;
import ir.layercloud.cortexdrone.harness.tool.ScanJobs;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolInvoker;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.Assert.*;

/**
 * Shared stub-tool harness for the behavioural skill tests.
 *
 * <p>Split out of the former 876-line {@code SkillBehaviorTest}: the harness is
 * the expensive part, while each behavioural area belongs in its own small
 * suite.</p>
 */
abstract class SkillTestSupport {
    /** One recorded tool call: name plus its JSON arguments. */
    static final class Call {
        final String name;
        final JsonObject args;

        Call(String name, JsonObject args) {
            this.name = name;
            this.args = args;
        }
    }

    static JsonObject copy(JsonObject source) {
        return new Gson().toJsonTree(source).getAsJsonObject();
    }

    static final class Harness {
        final JsEngine engine;
        final List<Call> calls = new ArrayList<>();

        Harness(SkillLibrary library, JsonObject state, BiFunction<String, JsonObject, JsonObject> overrides) {
            ToolRegistry registry = new ToolRegistry();
            for (String name : new String[]{"state", "get_player", "get_block", "get_top_y",
                    "scan_blocks", "scan_veins", "scan_furnaces", "scan_chests",
                    "get_recipes", "get_drops", "goto", "break_block", "place_block",
                    "use_item", "craft", "move_item", "give_to_player", "withdraw_chest",
                    "suck_items", "take_from_player", "deposit_chest", "find_item",
                    "survey_build_site", "plan_structure", "get_blocks_in_box",
                    "get_entities", "get_entity_health", "attack_plan", "attack_entity",
                    "pick_entity", "unpick_entity", "find_safe_landing"}) {
                declare(registry, name);
                registry.bind(name, "test", (context, args) -> {
                    this.calls.add(new Call(name, copy(args)));
                    JsonObject override = overrides != null ? overrides.apply(name, args) : null;
                    if (override != null) return copy(override);
                    return defaultResult(name, state, args);
                });
            }
            this.engine = new JsEngine(new ToolInvoker(null, registry, new ScanJobs(null)),
                    registry, new ModConfig(), library);
        }

        JsonObject run(String skill, String paramsJson) {
            JsonObject result = this.engine.execute(null,
                    "return skills.call('" + skill + "', " + paramsJson + ");", 30_000L);
            assertTrue(skill + " threw: " + result, result.get("ok").getAsBoolean());
            return result.getAsJsonObject("result");
        }

        /** Runs the skill through its bound function name, not skills.call. */
        JsonObject runAsFunction(String skill, String paramsJson) {
            JsonObject result = this.engine.execute(null,
                    "return skills." + skill + "(" + paramsJson + ");", 30_000L);
            assertTrue(skill + " (function form) threw: " + result,
                    result.get("ok").getAsBoolean());
            return result.getAsJsonObject("result");
        }
    }

    static void declare(ToolRegistry registry, String name) {
        ToolSchema schema = ToolSchema.object();
        switch (name) {
            case "get_block":
                schema.requiredInt("x", "X").requiredInt("y", "Y").requiredInt("z", "Z");
                break;
            case "get_top_y":
                schema.requiredInt("x", "X").requiredInt("z", "Z");
                break;
            case "get_player":
                schema.optionalString("name", "Player");
                break;
            case "get_recipes":
                schema.optionalString("filter", "Filter");
                break;
            case "craft":
                schema.optionalString("filter", "Filter").optionalInt("count", "Count", 1, InventoryLimits.MAX_TRANSFER_COUNT, 1);
                break;
            case "move_item":
                schema.optionalString("from", "From").optionalString("to", "To")
                        .optionalString("filter", "Filter").optionalInt("count", "Count", 0, InventoryLimits.MAX_TRANSFER_COUNT, 0)
                        .optionalInt("from_slot", "Slot", -1, 255, -1)
                        .optionalInt("to_slot", "Slot", -1, 255, -1)
                        .optionalInt("from_x", "X").optionalInt("from_y", "Y").optionalInt("from_z", "Z")
                        .optionalInt("to_x", "X").optionalInt("to_y", "Y").optionalInt("to_z", "Z");
                break;
            case "goto":
                schema.optionalNumber("x", "X").optionalNumber("y", "Y").optionalNumber("z", "Z")
                        .optionalObject("standoff_of", "Standoff")
                        .optionalBoolean("allow_dig", "Dig", false)
                        .optionalInt("max_wait_ms", "Wait", 1000, 180_000, 120_000)
                        .optionalInt("max_dig_blocks", "Blocks", 0, 512, 0)
                        .optionalInt("recovery_attempts", "Attempts", 0, 32, 12);
                break;
            case "break_block":
            case "place_block":
                schema.optionalInt("x", "X").optionalInt("y", "Y").optionalInt("z", "Z")
                        .optionalString("block", "Block")
                        .optionalInt("max_wait_ms", "Wait", 1000, 180_000, 120_000);
                break;
            case "use_item":
                schema.optionalString("item", "Item").optionalInt("slot", "Slot", 0, 7, null)
                        .optionalInt("x", "X").optionalInt("y", "Y").optionalInt("z", "Z")
                        .optionalInt("side", "Side", 0, 5, 1);
                break;
            case "scan_blocks":
                schema.optionalString("filter", "Filter").optionalInt("radius", "Radius", 1, 64, 24)
                        .optionalInt("limit", "Limit", 1, 2000, 400)
                        .optionalInt("x", "X").optionalInt("y", "Y").optionalInt("z", "Z")
                        .optionalString("shape", "Shape");
                break;
            case "scan_veins":
            case "scan_furnaces":
            case "scan_chests":
                schema.optionalString("filter", "Filter").optionalInt("radius", "Radius", 1, 64, 24)
                        .optionalInt("x", "X").optionalInt("y", "Y").optionalInt("z", "Z");
                break;
            case "withdraw_chest":
            case "deposit_chest":
                schema.optionalString("filter", "Filter").optionalInt("count", "Count", 0, InventoryLimits.MAX_TRANSFER_COUNT, 0)
                        .optionalInt("x", "X").optionalInt("y", "Y").optionalInt("z", "Z");
                break;
            case "give_to_player":
            case "take_from_player":
                schema.optionalString("filter", "Filter").optionalInt("count", "Count", 0, InventoryLimits.MAX_TRANSFER_COUNT, 0)
                        .optionalString("player", "Player");
                break;
            case "suck_items":
                schema.optionalNumber("radius", "Radius", 1, 16, 4);
                break;
            case "find_item":
                schema.optionalString("filter", "Filter").optionalInt("radius", "Radius", 4, 64, 32);
                break;
            case "survey_build_site":
                schema.optionalInt("x1", "X").optionalInt("z1", "Z")
                        .optionalInt("x2", "X").optionalInt("z2", "Z")
                        .optionalInt("y", "Y").optionalInt("height", "Height")
                        .optionalBoolean("allow_liquid", "Liquid", false);
                break;
            case "plan_structure":
                schema.optionalString("style", "Style");
                break;
            case "get_blocks_in_box":
                schema.optionalInt("x1", "X").optionalInt("y1", "Y").optionalInt("z1", "Z")
                        .optionalInt("x2", "X").optionalInt("y2", "Y").optionalInt("z2", "Z")
                        .optionalBoolean("includeAir", "Air", false);
                break;
            case "get_entities":
                schema.optionalString("filter", "Filter").optionalInt("radius", "Radius", 4, 180, 64)
                        .optionalString("anchor", "Anchor");
                break;
            case "get_entity_health":
            case "attack_plan":
            case "attack_entity":
                schema.optionalInt("entity_id", "Entity").optionalString("item", "Item")
                        .optionalInt("slot", "Slot", 0, 7, null);
                break;
            case "pick_entity":
                schema.requiredInt("entity_id", "Entity");
                break;
            case "unpick_entity":
                schema.optionalInt("entity_id", "Entity");
                break;
            case "find_safe_landing":
                schema.optionalInt("x", "X").optionalInt("y", "Y").optionalInt("z", "Z")
                        .optionalInt("radius", "Radius", 1, 24, 12)
                        .optionalBoolean("covered_ok", "Covered", false);
                break;
            default:
                break;
        }
        registry.declare(ToolSpec.builder(name)
                .description("Test stub for " + name + ".")
                .parameters(schema)
                .affinity(ToolAffinity.ASYNC)
                .build());
    }

    static JsonObject defaultResult(String name, JsonObject state, JsonObject args) {
        JsonObject result = ToolJson.ok();
        switch (name) {
            case "state":
                return copy(state);
            case "get_player":
                result.addProperty("name", "Tester");
                result.addProperty("blockX", 0);
                result.addProperty("blockY", 64);
                result.addProperty("blockZ", 0);
                result.addProperty("x", 0.5);
                result.addProperty("y", 64);
                result.addProperty("z", 0.5);
                result.addProperty("facingDx", 0);
                result.addProperty("facingDz", 1);
                return result;
            case "get_block":
                result.addProperty("name", "Air");
                result.addProperty("registryName", "minecraft:air");
                result.addProperty("isAir", true);
                result.addProperty("isLiquid", false);
                result.addProperty("hardness", 0);
                return result;
            case "get_top_y":
                result.addProperty("topY", 64);
                return result;
            case "scan_blocks":
            case "scan_veins":
            case "scan_furnaces":
            case "scan_chests":
                result.addProperty("count", 0);
                result.add("blocks", new JsonArray());
                result.add("veins", new JsonArray());
                result.add("furnaces", new JsonArray());
                result.add("chests", new JsonArray());
                return result;
            case "get_recipes":
                result.addProperty("count", 0);
                result.add("recipes", new JsonArray());
                return result;
            case "get_drops":
                result.addProperty("count", 0);
                result.add("drops", new JsonArray());
                return result;
            case "goto":
                result.addProperty("arrived", true);
                return result;
            case "break_block":
                result.addProperty("broken", true);
                return result;
            case "place_block":
                result.addProperty("placed", true);
                return result;
            case "use_item":
                result.addProperty("used", true);
                return result;
            case "craft":
                result.addProperty("crafted", args.has("count") ? args.get("count").getAsInt() : 1);
                return result;
            case "move_item":
                result.addProperty("moved", args.has("count") ? args.get("count").getAsInt() : 1);
                return result;
            case "give_to_player":
            case "take_from_player":
                result.addProperty("transferred", args.has("count") ? args.get("count").getAsInt() : 1);
                return result;
            case "withdraw_chest":
                result.addProperty("withdrawn", args.has("count") ? args.get("count").getAsInt() : 1);
                return result;
            case "deposit_chest":
                result.addProperty("deposited", 1);
                return result;
            case "suck_items":
                result.addProperty("sucked", 1);
                return result;
            case "find_item":
                result.addProperty("found", true);
                result.addProperty("totalAvailable", 64);
                result.addProperty("inDroneCount", 64);
                return result;
            case "survey_build_site":
                result.addProperty("slope", 0);
                result.addProperty("suggestedFloorY", 63);
                result.addProperty("floorY", 63);
                result.add("blockers", new JsonArray());
                return result;
            case "get_blocks_in_box":
                result.addProperty("count", 0);
                result.addProperty("totalVoxels", 0);
                result.add("blocks", new JsonArray());
                result.add("countsByType", new JsonObject());
                return result;
            case "get_entities":
                result.addProperty("count", 0);
                result.add("entities", new JsonArray());
                return result;
            case "get_entity_health":
            case "attack_plan":
                result.addProperty("found", true);
                result.addProperty("entity_id", args.has("entity_id")
                        ? args.get("entity_id").getAsInt() : 1);
                result.addProperty("name", "Zombie");
                result.addProperty("alive", true);
                result.addProperty("player", false);
                result.addProperty("health", 20);
                result.addProperty("total_health", 20);
                result.addProperty("can_damage", true);
                result.addProperty("attacks_to_kill", 3);
                result.addProperty("damage_per_hit", 7);
                result.addProperty("hurt_resistant_ticks", 0);
                result.addProperty("next_attack_in_ms", 200);
                return result;
            case "attack_entity":
                result.addProperty("killed", true);
                result.addProperty("healthAfter", 0);
                result.addProperty("damageDealt", 7);
                result.addProperty("nextAttackInMs", 500);
                return result;
            case "pick_entity":
                result.addProperty("picked", true);
                result.addProperty("entityId", args.has("entity_id")
                        ? args.get("entity_id").getAsInt() : 1);
                return result;
            case "unpick_entity":
                result.addProperty("unpicked", true);
                return result;
            case "find_safe_landing": {
                // Default fake world: open flat ground at y=64, so a landing is
                // always found unless a test overrides this.
                result.addProperty("found", true);
                result.addProperty("safe", true);
                result.addProperty("safeAtRequested", true);
                result.addProperty("movedFromRequest", false);
                JsonObject spot = new JsonObject();
                spot.addProperty("x", args.has("x") ? args.get("x").getAsInt() : 0);
                spot.addProperty("y", 64);
                spot.addProperty("z", args.has("z") ? args.get("z").getAsInt() : 0);
                spot.addProperty("hoverX", (args.has("x") ? args.get("x").getAsInt() : 0) + 0.5);
                spot.addProperty("hoverY", 64.35);
                spot.addProperty("hoverZ", (args.has("z") ? args.get("z").getAsInt() : 0) + 0.5);
                spot.addProperty("safe", true);
                spot.addProperty("openSky", true);
                spot.addProperty("surfaceLevel", true);
                spot.addProperty("floor", "Stone");
                result.add("spot", spot);
                return result;
            }
            default:
                return result;
        }
    }

    static JsonObject state(String... stacks) {
        JsonObject state = new JsonObject();
        state.addProperty("ok", true);
        state.addProperty("x", 0.5);
        state.addProperty("y", 64);
        state.addProperty("z", 0.5);
        state.addProperty("slots", 8);
        state.addProperty("freeSlots", 8);
        state.addProperty("totalItems", 0);
        state.addProperty("isFull", false);
        JsonArray inventory = new JsonArray();
        for (String description : stacks) {
            String[] parts = description.split(":\\|:");
            JsonObject stack = new JsonObject();
            stack.addProperty("name", parts[0]);
            stack.addProperty("registryName", parts[1]);
            if (parts.length > 2) stack.addProperty("meta", Integer.parseInt(parts[2]));
            stack.addProperty("count", parts.length > 3 ? Integer.parseInt(parts[3]) : 64);
            inventory.add(stack);
        }
        state.add("inventory", inventory);
        return state;
    }

    static SkillLibrary library() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-skill-behavior").toFile();
        return new SkillLibrary(root);
    }
}
