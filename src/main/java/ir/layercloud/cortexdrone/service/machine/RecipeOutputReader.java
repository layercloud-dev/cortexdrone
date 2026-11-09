package ir.layercloud.cortexdrone.service.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Reads the output side of a machine recipe.
 *
 * <p>Extracted from {@code MachineRecipeParser}. The hard part is telling a
 * molten-fluid output ({@code ["Steel", 72]}) from an item output: item outputs
 * always carry a mod-id colon, so a bare token with a numeric amount is a fluid.
 * That rule lives here, in one place, with the reason written down.</p>
 */
final class RecipeOutputReader {
    private RecipeOutputReader() {
    }

    /** Reads every item and fluid output declared by {@code recipe}. */
    static void read(JsonObject recipe, MachineRecipeService.NormalizedRecipe out) {
        for (String key : RecipeKeys.OUTPUTS) {
            readKey(recipe, key, out);
        }
        readFluidOutputArray(recipe, "fluidOutput", out);
    }

    private static void readKey(JsonObject recipe, String key,
                                MachineRecipeService.NormalizedRecipe out) {
        if (!recipe.has(key)) return;
        JsonElement element = recipe.get(key);
        if (element == null || element.isJsonNull()) return;
        if (RecipeKeys.isFluidOutput(key)) {
            readFluidValue(element, out);
            return;
        }
        if (element.isJsonArray()) {
            readOutputArray(element.getAsJsonArray(), out);
        } else if (element.isJsonPrimitive()) {
            addFluid(out, element.getAsString(), 0);
        }
    }

    /** A key explicitly typed as fluid: one entry or a list of entries. */
    private static void readFluidValue(JsonElement element,
                                       MachineRecipeService.NormalizedRecipe out) {
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.size() == 0) return;
            if (array.get(0).isJsonArray()) {
                for (JsonElement entry : array) {
                    JsonObject fluid = MachineRecipeParser.parseFluidEntry(entry);
                    if (fluid != null) out.outputFluids.add(fluid);
                }
            } else {
                JsonObject fluid = MachineRecipeParser.parseFluidEntry(element);
                if (fluid != null) out.outputFluids.add(fluid);
            }
        } else if (element.isJsonPrimitive()) {
            addFluid(out, element.getAsString(), 0);
        }
    }

    private static void readOutputArray(JsonArray array, MachineRecipeService.NormalizedRecipe out) {
        if (array.size() == 0) return;

        // A flat [name, amount] pair whose name has no colon is molten fluid.
        if (isBareFluidPair(array)) {
            addFluid(out, array.get(0).getAsString(), array.get(1).getAsInt());
            return;
        }
        if (array.get(0).isJsonArray()) {
            for (JsonElement element : array) {
                // Crucible molten outputs pair each inner array with a fluid
                // amount (["Steel", 16]); detect before item parse.
                JsonObject fluid = MachineRecipeParser.tryParseFluidShapedInput(element);
                if (fluid != null) {
                    out.outputFluids.add(fluid);
                    continue;
                }
                JsonObject item = MachineRecipeParser.parseOutputEntry(element);
                if (item != null) out.outputs.add(item);
            }
            return;
        }
        if (array.get(0).isJsonPrimitive()) {
            String first = array.get(0).getAsString();
            if ("single".equals(first)) {
                addSingle(array, out);
                return;
            }
            // Bare-material output [name, quanta] is molten-fluid transfer
            // (e.g. Rotary ["Steel",72], Crucible molten). Item outputs always
            // carry a modid colon.
            if (!first.contains(":")) {
                if (isBareFluidPair(array)) {
                    addFluid(out, first, array.get(1).getAsInt());
                } else {
                    addSingle(array, out);
                }
                return;
            }
        }
        addSingle(array, out);
    }

    /** Parses the whole array as one item, falling back to each element. */
    private static void addSingle(JsonArray array, MachineRecipeService.NormalizedRecipe out) {
        JsonObject item = MachineRecipeParser.parseOutputEntry(array);
        if (item != null) {
            out.outputs.add(item);
            return;
        }
        for (JsonElement element : array) {
            JsonObject entry = MachineRecipeParser.parseOutputEntry(element);
            if (entry != null) out.outputs.add(entry);
        }
    }

    /**
     * True for {@code [name, amount]} where {@code name} is a bare fluid token.
     *
     * <p>Ordering matters: the name heuristic runs only after the colon check,
     * so {@code minecraft:iron_ingot} can never be mistaken for a fluid.</p>
     */
    private static boolean isBareFluidPair(JsonArray array) {
        if (array.size() < 2 || array.size() > 3) return false;
        if (!array.get(0).isJsonPrimitive() || !array.get(1).isJsonPrimitive()) return false;
        if (!array.get(1).getAsJsonPrimitive().isNumber()) return false;
        String name = array.get(0).getAsString();
        if (name.contains(":") || name.startsWith(HbmDescriptor.MOD_ID + ":")
                || name.startsWith("minecraft:")) return false;
        return MachineRecipeParser.isFluidNameHeuristic(name);
    }

    /** A key explicitly holding a fluid-output list. */
    private static void readFluidOutputArray(JsonObject recipe, String key,
                                             MachineRecipeService.NormalizedRecipe out) {
        if (!recipe.has(key) || !recipe.get(key).isJsonArray()) return;
        JsonArray array = recipe.get(key).getAsJsonArray();
        if (array.size() == 0) return;
        if (array.get(0).isJsonArray()) {
            for (JsonElement entry : array) {
                JsonObject fluid = MachineRecipeParser.parseFluidEntry(entry);
                if (fluid != null) out.outputFluids.add(fluid);
            }
        } else {
            JsonObject fluid = MachineRecipeParser.parseFluidEntry(recipe.get(key));
            if (fluid != null) out.outputFluids.add(fluid);
        }
    }

    private static void addFluid(MachineRecipeService.NormalizedRecipe out, String name, int amount) {
        if (name == null || name.isEmpty()) return;
        JsonObject fluid = MachineRecipeParser.parseFluidEntryJson(name, amount);
        if (fluid != null) out.outputFluids.add(fluid);
    }
}
