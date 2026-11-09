package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.harness.tool.ToolTimeouts;
import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.Arrays;
import java.util.List;

/**
 * Recipe lookups: vanilla crafting and machine recipes, plus ingredient
 * availability assessment.
 *
 * <p>Split out of {@code InventoryQueryTools}. Every tool here answers "can I
 * make this, and what am I missing"; none of them mutates anything.</p>
 */
public final class RecipeQueryTools {
    /** Default probe item used when the caller names nothing. */
    private static final String DEFAULT_MACHINE_FILTER = "plate_iron";
    /** Default vanilla recipe filter. */
    private static final String DEFAULT_VANILLA_FILTER = "chest";

    private final InventoryService inventoryService;

    public RecipeQueryTools(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("get_recipes")
                    .description("List vanilla crafting-table recipes whose result matches a filter, "
                            + "including shaped/shapeless grid and ingredient counts. Does NOT include "
                            + "machine-only recipes; use get_machine_recipes for those.")
                    .parameters(ToolSchema.object()
                            .optionalString("filter", "Result item name/registry/ore tag.", DEFAULT_VANILLA_FILTER))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.READ_MS).build(),
            ToolSpec.builder("get_machine_recipes")
                    .description("List configured machine recipes for a filter, optionally restricted to a "
                            + "machine kind (assembly_machine, chemical_plant, blast_furnace, centrifuge, "
                            + "mixer, refinery...). Sources include HBM JSON configs and live hooks.")
                    .parameters(ToolSchema.object()
                            .optionalString("filter", "Input/output/item name or phrase.", "")
                            .optionalString("machine", "Machine kind/alias to restrict results."))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.SCAN_MS).build(),
            ToolSpec.builder("get_machine_recipe")
                    .description("Find the best machine recipe for a target item and machine kind, ranked "
                            + "by ingredient availability around the player. Returns the recipe, machine and "
                            + "input list, or an explicit machine_config_only blocker.")
                    .parameters(ToolSchema.object()
                            .requiredString("filter", "Target item/result name.")
                            .optionalString("machine", "Machine kind/alias.")
                            .optionalInt("radius", "Chest search radius for ingredients, " + InventoryLimits.MIN_RADIUS + ".." + InventoryLimits.MAX_RADIUS + ".",
                                    InventoryLimits.MIN_RADIUS, InventoryLimits.MAX_RADIUS,
                                    InventoryLimits.DEFAULT_RADIUS))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.SURVEY_MS).build(),
            ToolSpec.builder("machine_ingredients")
                    .description("For the best machine recipe matching a target, check each ingredient's "
                            + "availability in the drone, player and nearby chests. Use it to decide whether "
                            + "to withdraw, craft, mine, or report a blocker.")
                    .parameters(ToolSchema.object()
                            .requiredString("filter", "Target item/result name.")
                            .optionalString("machine", "Machine kind/alias.")
                            .optionalInt("radius", "Chest search radius, "
                                            + InventoryLimits.MIN_RADIUS + ".." + InventoryLimits.MAX_RADIUS + ".",
                                    InventoryLimits.MIN_RADIUS, InventoryLimits.MAX_RADIUS,
                                    InventoryLimits.DEFAULT_RADIUS))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.SURVEY_MS).build()
    );

    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String tool, JsonObject args) {
        switch (tool) {
            case "get_recipes":
                return vanillaRecipes(args);
            case "get_machine_recipes":
                return machineRecipes(args);
            case "get_machine_recipe":
                return bestRecipe(ctrl, player, args);
            case "machine_ingredients":
                return ingredients(ctrl, player, args);
            default:
                return ToolJson.error("unknown_tool", "Unhandled recipe tool: " + tool, null, false);
        }
    }

    private JsonObject vanillaRecipes(JsonObject args) {
        String filter = InventoryToolSupport.firstString(args, DEFAULT_VANILLA_FILTER, "filter", "item");
        return this.inventoryService.getRecipesForFilter(filter);
    }

    private JsonObject machineRecipes(JsonObject args) {
        String filter = InventoryToolSupport.firstString(args, "", "filter", "target", "item");
        return this.inventoryService.getMachineRecipeService()
                .getMachineRecipesForFilter(filter, InventoryToolSupport.machine(args));
    }

    private JsonObject bestRecipe(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        return InventoryToolSupport.bestRecipe(this.inventoryService, ctrl.drone.worldObj,
                ctrl.drone, player, args, DEFAULT_MACHINE_FILTER);
    }

    /** For the best recipe, report how much of each ingredient is reachable. */
    private JsonObject ingredients(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        int radius = InventoryToolSupport.radius(args, InventoryLimits.DEFAULT_RADIUS);
        JsonObject best = InventoryToolSupport.bestRecipe(this.inventoryService,
                ctrl.drone.worldObj, ctrl.drone, player, args, DEFAULT_MACHINE_FILTER);
        if (!best.has("recipe")) return best;
        JsonObject recipe = best.getAsJsonObject("recipe");

        JsonObject result = ToolJson.ok();
        result.addProperty("machine", recipe.has("machine") ? recipe.get("machine").getAsString() : "");
        result.addProperty("recipe", recipe.has("recipe") ? recipe.get("recipe").getAsString() : "");
        if (!recipe.has("inputs")) return result;

        JsonArray ingredients = new JsonArray();
        JsonArray inputs = recipe.getAsJsonArray("inputs");
        for (int i = 0; i < inputs.size(); i++) {
            JsonObject input = inputs.get(i).getAsJsonObject();
            String probe = probeName(input);
            JsonObject search = this.inventoryService.getContainerSearchService()
                    .findItemEverywhere(ctrl.drone.worldObj, ctrl, player, probe, radius);
            JsonObject entry = new JsonObject();
            entry.add("ingredient", input);
            entry.addProperty("probe", probe);
            entry.addProperty("found", search.has("found") && search.get("found").getAsBoolean());
            entry.addProperty("totalAvailable", intOf(search, "totalAvailable"));
            entry.addProperty("inDroneCount", intOf(search, "inDroneCount"));
            entry.addProperty("inPlayerCount", intOf(search, "inPlayerCount"));
            entry.addProperty("inChestsCount", intOf(search, "inChestsCount"));
            if (search.has("bestChest")) entry.add("bestChest", search.get("bestChest"));
            ingredients.add(entry);
        }
        result.add("ingredients", ingredients);
        return result;
    }

    /** Best human-readable name for an ingredient entry. */
    private static String probeName(JsonObject input) {
        if (input.has("displayName")) return input.get("displayName").getAsString();
        if (input.has("registryName")) return input.get("registryName").getAsString();
        if (input.has("dict")) return input.get("dict").getAsString();
        return "ingredient";
    }

    private static int intOf(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsInt() : 0;
    }
}
