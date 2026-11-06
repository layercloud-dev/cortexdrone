package ir.layercloud.cortexdrone.service.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
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
 * <p>Core API only. JSON schema parsing lives in
 * {@link MachineRecipeParser}; ranking, filtering, and ingredient
 * extraction live in {@link MachineRecipeRanker}.
 */
@RequiredArgsConstructor
public class MachineRecipeService {
    // Recipe JSON is re-read from disk on every call; cache it for a few seconds
    // so repeated HUD/lookup calls in the same tick window do not thrash I/O.
    private static final long RECIPE_CACHE_TTL_MS = 3000L;
    private List<NormalizedRecipe> cachedRecipes;
    private long cachedRecipesAtMs = -1L;

    private final InventoryService inventoryService;

    static class NormalizedRecipe {
        String machine;
        String file;
        String name;
        String displayName;
        List<JsonObject> inputs;
        List<JsonObject> outputs;
        List<JsonObject> inputFluids;
        List<JsonObject> outputFluids;
        int duration;
        long power;
        int tierLower;
        int tierUpper;
        String overlay;
        int constructionIndex = -1;

        NormalizedRecipe() {
            this.inputs = new ArrayList<JsonObject>();
            this.outputs = new ArrayList<JsonObject>();
            this.inputFluids = new ArrayList<JsonObject>();
            this.outputFluids = new ArrayList<JsonObject>();
        }
    }

    @Data
    static class Availability {
        private int available;
        private int missing;
        private int needed;
        private boolean sufficient;
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

    public JsonObject getBestMachineRecipe(World world, EntityPlayerMP player, String targetFilter, String machineFilter, int radius) {
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
        MachineRecipeService.Availability av = MachineRecipeRanker.assess(this.inventoryService, ranked.get(0), world, player, radius);
        for (NormalizedRecipe cand : ranked) {
            MachineRecipeService.Availability a = MachineRecipeRanker.assess(this.inventoryService, cand, world, player, radius);
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
            return new ArrayList<NormalizedRecipe>(this.cachedRecipes);
        }
        List<NormalizedRecipe> out = new ArrayList<NormalizedRecipe>();
        File configDir = resolveConfigDirectory();
        File hbmDir = new File(configDir, "hbmRecipes");
        if (hbmDir.isDirectory()) {
            File[] files = hbmDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.getName().endsWith(".json")) continue;
                    String machine = MachineTileInspector.recipeFileToMachine(f.getName());
                    List<NormalizedRecipe> parsed = parseFile(f, machine, f.getName());
                    out.addAll(parsed);
                }
            }
        }
        if (out.isEmpty()) {
            out.addAll(readLiveRecipesViaReflection());
        } else {
            // JSON templates cover most kinds, but some recipe families exist ONLY
            // in memory (smithing pairs and construction-index tags). Merge the
            // live-only entries without duplicating what JSON already produced.
            List<NormalizedRecipe> live = readLiveRecipesViaReflection();
            Set<String> seen = new HashSet<String>();
            for (NormalizedRecipe r : out) {
                seen.add(r.machine + "\n" + r.name);
            }
            for (NormalizedRecipe r : live) {
                if (!seen.contains(r.machine + "\n" + r.name)) out.add(r);
            }
        }
        if (!out.isEmpty()) {
            this.cachedRecipes = out;
            this.cachedRecipesAtMs = now;
            return new ArrayList<NormalizedRecipe>(out);
        }
        return out;
    }

    private List<NormalizedRecipe> readLiveRecipesViaReflection() {
        return MachineRecipeReflection.readLive(this);
    }

    private List<NormalizedRecipe> parseFile(File f, String machine, String fileName) {
        List<NormalizedRecipe> out = new ArrayList<NormalizedRecipe>();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            if (sb.length() == 0) return out;
            JsonElement parsed = new JsonParser().parse(sb.toString());
            if (!parsed.isJsonObject()) return out;
            JsonObject root = parsed.getAsJsonObject();
            JsonArray recipes = root.has("recipes") ? root.getAsJsonArray("recipes") : null;
            if (recipes == null) return out;
            for (JsonElement el : recipes) {
                if (!el.isJsonObject()) continue;
                NormalizedRecipe r = MachineRecipeParser.normalizeJsonRecipe(el.getAsJsonObject(), machine, fileName);
                if (r != null) out.add(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static File resolveConfigDirectory() {
        try {
            net.minecraft.server.MinecraftServer srv = net.minecraft.server.MinecraftServer.getServer();
            if (srv != null) {
                File f = srv.getFile("config");
                if (f != null && f.isDirectory()) return f;
            }
        } catch (Throwable ignored) {
        }
        File rel = new File("config");
        if (rel.isDirectory()) return rel;
        return new File("/root/forge-smp/config");
    }
}
