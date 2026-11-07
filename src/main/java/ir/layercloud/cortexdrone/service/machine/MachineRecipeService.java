package ir.layercloud.cortexdrone.service.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import lombok.Data;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dynamic, zero-hardcoded HBM/modified machine recipe introspection.
 * Parses every flat or live-edited HBM recipe JSON in the server config
 * hbmRecipes directory (discovered by listing the directory, not a static
 * file list) and also reads live GenericRecipes / AnvilRecipes via reflection
 * when the config files are absent (HBM-ModName missile recipes without files).
 * All item/dict matching mirrors SerializableRecipe.readAStack /
 * readItemStack / readItemStackChance and GenericRecipes$ChanceOutput /
 * ChanceOutputMulti / AnvilRecipes$AnvilConstructionRecipe semantics,
 * driven by OreDictionary lookups so new entries light up automatically.
 *
 * <p>Read-only by contract. HBM access is optional and isolated behind
 * {@link MachineRecipeHook} implementations, so a server without HBM simply has
 * no recipes. JSON schema parsing lives in {@link MachineRecipeParser};
 * ranking, filtering and ingredient extraction live in
 * {@link MachineRecipeRanker}.
 */
public class MachineRecipeService {
    // Recipe JSON is re-read from disk on every call; cache it for a few seconds
    // so repeated HUD/lookup calls in the same tick window do not thrash I/O.
    private static final long RECIPE_CACHE_TTL_MS = 3000L;
    private List<NormalizedRecipe> cachedRecipes;
    private long cachedRecipesAtMs = -1L;

    private final InventoryService inventoryService;
    private final List<MachineRecipeHook> hooks = new ArrayList<>();

    public static class NormalizedRecipe {
        String machine;
        String name;
        String displayName;
        final List<JsonObject> inputs;
        final List<JsonObject> outputs;
        final List<JsonObject> inputFluids;
        final List<JsonObject> outputFluids;
        int duration;
        long power;
        int tierLower;
        int tierUpper;
        String overlay;
        int constructionIndex = -1;

        NormalizedRecipe() {
            this.inputs = new ArrayList<>();
            this.outputs = new ArrayList<>();
            this.inputFluids = new ArrayList<>();
            this.outputFluids = new ArrayList<>();
        }
    }

    @Data
    static class Availability {
        private int available;
        private int missing;
        private int needed;
        private boolean sufficient;
    }

    public MachineRecipeService(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
        this.hooks.add(new HbmRecipeFileHook());
        this.hooks.add(new HbmRecipeLiveHook());
    }

    public JsonObject getMachineRecipesForFilter(String rawFilter, String rawMachine) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        String wantMachine = MachineTileInspector.normalizeMachineAlias(rawMachine);
        String filter = rawFilter != null ? rawFilter.trim().toLowerCase() : "";
        List<NormalizedRecipe> all = loadAllRecipes();
        List<NormalizedRecipe> ranked = MachineRecipeRanker.rankAndFilter(all, filter, wantMachine);
        JsonArray arr = new JsonArray();
        for (NormalizedRecipe r : ranked) {
            if (arr.size() >= 25) break;
            JsonObject o = new JsonObject();
            o.addProperty("machine", r.machine);
            o.addProperty("recipe", r.name);
            o.addProperty("displayName", r.displayName);
            o.addProperty("duration", r.duration);
            o.addProperty("power", r.power);
            if (r.tierLower != 0 || r.tierUpper != -1) {
                o.addProperty("tierLower", r.tierLower);
                o.addProperty("tierUpper", r.tierUpper);
            }
            if (r.overlay != null && !r.overlay.isEmpty()) o.addProperty("overlay", r.overlay);
            o.add("inputs", inputsToJson(r.inputs));
            o.add("outputs", inputsToJson(r.outputs));
            if (!r.inputFluids.isEmpty()) o.add("inputFluids", fluidsToJson(r.inputFluids));
            if (!r.outputFluids.isEmpty()) o.add("outputFluids", fluidsToJson(r.outputFluids));
            o.addProperty("inputCount", r.inputs.size());
            arr.add(o);
        }
        res.addProperty("machine", wantMachine);
        res.addProperty("query", filter);
        res.addProperty("count", arr.size());
        res.addProperty("totalCandidates", ranked.size());
        res.add("recipes", arr);
        return res;
    }

    public JsonObject getBestMachineRecipe(World world, li.cil.oc.common.entity.Drone drone,
                                           EntityPlayerMP player, String targetFilter,
                                           String machineFilter, int radius) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        String f = targetFilter != null ? targetFilter.trim() : "plate_iron";
        String wantMachine = machineFilter != null && !machineFilter.trim().isEmpty()
                ? MachineTileInspector.normalizeMachineAlias(machineFilter) : "";
        List<NormalizedRecipe> all = loadAllRecipes();
        List<NormalizedRecipe> ranked = MachineRecipeRanker.rankAndFilter(all, f, wantMachine);
        if (ranked.isEmpty()) {
            res.addProperty("ok", false);
            res.addProperty("error", "No HBM machine recipe found matching '" + f + "'" + (wantMachine.isEmpty() ? "" : " in " + wantMachine));
            res.addProperty("hint", "Run get_machine_recipes without a filter to list available machines, or kb.lookup(target) for drafting.");
            return res;
        }
        MachineRecipeService.Availability av = MachineRecipeRanker.assess(this.inventoryService, ranked.get(0), world, drone, player, radius);
        for (NormalizedRecipe cand : ranked) {
            MachineRecipeService.Availability a = MachineRecipeRanker.assess(this.inventoryService, cand, world, drone, player, radius);
            if (a.isSufficient()) {
                av = a;
                ranked.remove(cand);
                ranked.add(0, cand);
                break;
            }
        }
        NormalizedRecipe best = ranked.get(0);
        res.addProperty("bestError", av.isSufficient() ? "" : "Ingredients missing in drone+player+chests within radius " + radius + "m");
        res.addProperty("sufficient", av.isSufficient());
        res.addProperty("available", av.getAvailable());
        res.addProperty("missing", av.getMissing());
        JsonObject o = recipeToJson(best);
        res.add("recipe", o);
        res.addProperty("machine", best.machine);
        return res;
    }

    private JsonObject recipeToJson(NormalizedRecipe r) {
        JsonObject o = new JsonObject();
        o.addProperty("machine", r.machine);
        o.addProperty("recipe", r.name);
        o.addProperty("displayName", r.displayName);
        o.addProperty("duration", r.duration);
        o.addProperty("power", r.power);
        if (r.tierLower != 0 || r.tierUpper != -1) {
            o.addProperty("tierLower", r.tierLower);
            o.addProperty("tierUpper", r.tierUpper);
        }
        if (r.overlay != null && !r.overlay.isEmpty()) o.addProperty("overlay", r.overlay);
        if (r.constructionIndex >= 0) o.addProperty("constructionIndex", r.constructionIndex);
        o.add("inputs", inputsToJson(r.inputs));
        o.add("outputs", inputsToJson(r.outputs));
        if (!r.inputFluids.isEmpty()) o.add("inputFluids", fluidsToJson(r.inputFluids));
        if (!r.outputFluids.isEmpty()) o.add("outputFluids", fluidsToJson(r.outputFluids));
        return o;
    }

    private JsonArray inputsToJson(List<JsonObject> list) {
        JsonArray arr = new JsonArray();
        for (JsonObject o : list) arr.add(o);
        return arr;
    }

    private JsonArray fluidsToJson(List<JsonObject> list) {
        JsonArray arr = new JsonArray();
        for (JsonObject o : list) arr.add(o);
        return arr;
    }

    private List<NormalizedRecipe> loadAllRecipes() {
        long now = System.currentTimeMillis();
        if (this.cachedRecipes != null && now - this.cachedRecipesAtMs < RECIPE_CACHE_TTL_MS) {
            return new ArrayList<>(this.cachedRecipes);
        }
        List<NormalizedRecipe> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MachineRecipeHook hook : this.hooks) {
            if (!hook.isAvailable()) continue;
            for (NormalizedRecipe recipe : hook.load()) {
                if (recipe == null) continue;
                String key = recipe.machine + "\n" + recipe.name;
                if (seen.add(key)) out.add(recipe);
            }
        }
        this.cachedRecipes = out;
        this.cachedRecipesAtMs = now;
        return new ArrayList<>(out);
    }
}
