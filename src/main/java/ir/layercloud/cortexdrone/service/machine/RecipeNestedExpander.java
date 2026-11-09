package ir.layercloud.cortexdrone.service.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Expands the nested {@code recipes: [...]} shape into one normalised recipe.
 *
 * <p>Extracted from the recipe parser's giant method. The shape is what the
 * mixer-style machines emit: a shared fluid {@code outputType} plus a list of
 * inner recipes that each carry an {@code outputAmount}.</p>
 */
final class RecipeNestedExpander {
    private RecipeNestedExpander() {
    }

    /**
     * Normalises every inner recipe, then returns the first one that actually
     * carries content.
     *
     * <p>Returning the first non-empty recipe (rather than the first entry) is
     * deliberate: some files contain placeholder shapes that must be skipped
     * instead of producing an empty recipe.</p>
     */
    static MachineRecipeService.NormalizedRecipe expand(JsonObject outer, String machine,
                                                        String fileName,
                                                        RecipeEntryReader reader) {
        if (outer == null || !outer.has("recipes") || !outer.get("recipes").isJsonArray()) {
            return null;
        }
        JsonArray inner = outer.getAsJsonArray("recipes");
        String fluidName = outer.has("outputType") ? outer.get("outputType").getAsString() : "";
        MachineRecipeService.NormalizedRecipe best = null;
        for (JsonElement element : inner) {
            if (!element.isJsonObject()) continue;
            JsonObject nested = element.getAsJsonObject();
            MachineRecipeService.NormalizedRecipe candidate =
                    reader.read(nested, machine, fileName);
            if (candidate == null) continue;
            appendFluidOutput(candidate, nested, fluidName);
            if (nested.has("duration")) candidate.duration = nested.get("duration").getAsInt();
            if (RecipeKeys.isEmptyRecipe(candidate)) continue;
            if (candidate.displayName == null || candidate.displayName.isEmpty()) {
                candidate.displayName = candidate.name;
            }
            // Prefer the first non-empty recipe, matching the previous behaviour
            // while no longer dropping meaningful siblings silently.
            if (best == null) best = candidate;
        }
        return best;
    }

    /** Adds the shared {@code outputType} fluid when the inner recipe names an amount. */
    private static void appendFluidOutput(MachineRecipeService.NormalizedRecipe recipe,
                                          JsonObject nested, String fluidName) {
        if (fluidName.isEmpty() || !nested.has("outputAmount")) return;
        int amount = nested.get("outputAmount").getAsInt();
        if (amount <= 0) return;
        JsonObject fluid = MachineRecipeParser.parseFluidEntryJson(fluidName, amount);
        if (fluid == null) return;
        fluid.addProperty("isMixingOutput", true);
        recipe.outputFluids.add(fluid);
    }

    /** Reads one nested recipe object; supplied by the parser to avoid a cycle. */
    interface RecipeEntryReader {
        MachineRecipeService.NormalizedRecipe read(JsonObject recipe, String machine, String fileName);
    }

}
