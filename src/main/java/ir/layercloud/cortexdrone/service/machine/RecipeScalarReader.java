package ir.layercloud.cortexdrone.service.machine;

import com.google.gson.JsonObject;

/**
 * Reads the scalar fields of a machine recipe: name, duration, power and tier.
 *
 * <p>Extracted from {@code MachineRecipeParser}. Several files express the same
 * concept under different keys ({@code duration}/{@code frequency},
 * {@code power}/{@code consumption}/{@code momentum}/{@code steam}/{@code flux}/
 * {@code antimatter}/{@code consumptionPerTick}); the recipe keeps the largest
 * value, because that is the energy the machine must supply.</p>
 */
final class RecipeScalarReader {
    private RecipeScalarReader() {
    }

    /**
     * Power keys that all describe the same energy cost.
     */
    private static final String[] POWER_KEYS = {
            "power", "consumption", "momentum", "steam", "flux",
            "antimatter", "consumptionPerTick"
    };

    /**
     * Fills the scalar fields of {@code out} from {@code recipe}.
     */
    static void read(JsonObject recipe, MachineRecipeService.NormalizedRecipe out,
                     String fileName) {
        out.name = recipe.has("name")
                ? recipe.get("name").getAsString()
                : fileName.replace(".json", "") + ".";
        out.displayName = recipe.has("nameWrapper")
                ? recipe.get("nameWrapper").getAsString() : out.name;

        if (recipe.has("duration")) out.duration = recipe.get("duration").getAsInt();
        if (isNumber(recipe, "frequency")) {
            out.duration = Math.max(out.duration, recipe.get("frequency").getAsInt());
        }
        for (String key : POWER_KEYS) {
            if (!isNumber(recipe, key)) continue;
            out.power = Math.max(out.power, recipe.get(key).getAsLong());
        }
        if (recipe.has("tierLower")) out.tierLower = recipe.get("tierLower").getAsInt();
        if (recipe.has("tierUpper")) out.tierUpper = recipe.get("tierUpper").getAsInt();
        if (recipe.has("overlay")) out.overlay = recipe.get("overlay").getAsString();
    }

    /**
     * True when the key exists and holds a JSON number.
     */
    private static boolean isNumber(JsonObject recipe, String key) {
        return recipe.has(key) && recipe.get(key).isJsonPrimitive()
                && recipe.get(key).getAsJsonPrimitive().isNumber();
    }
}
