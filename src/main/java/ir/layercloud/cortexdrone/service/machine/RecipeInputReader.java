package ir.layercloud.cortexdrone.service.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Reads the input side of a machine recipe.
 *
 * <p>Extracted from {@code MachineRecipeParser}. A recipe file may express an
 * input as an item dict, a bare fluid name, a {@code [name, amount]} pair, a
 * nested list, or a pedestal grid. These are all accepted shapes of the same
 * concept, so they are handled in one ordered reader instead of an if/else
 * ladder inside the normaliser.</p>
 */
final class RecipeInputReader {
    private RecipeInputReader() {
    }

    /**
     * Reads every item and fluid input declared by {@code recipe}.
     */
    static void read(JsonObject recipe, MachineRecipeService.NormalizedRecipe out) {
        readArray(recipe, "inputItem", out);
        readArray(recipe, "inputs", out);
        for (String key : RecipeKeys.ITEM_INPUTS) {
            readItemKey(recipe, key, out);
        }
        // A bare scalar "input" is a fluid name (crucible/electrolyser style).
        if (recipe.has("input") && recipe.get("input").isJsonPrimitive()
                && !recipe.get("input").isJsonNull()) {
            addFluid(out.inputFluids, recipe.get("input").getAsString(), 0);
        }
        for (String key : RecipeKeys.TYPED_INPUTS) {
            readTypedKey(recipe, key, out);
        }
        readFluidArray(recipe, "fluid", out);
        readFluidArray(recipe, "fluidIn", out);
        readPedestalGrid(recipe, out);
    }

    /**
     * The pedestal/grid shape: an {@code input} array of cells whose first
     * element is a type token ({@code dict}/{@code item}/{@code dictframe}).
     *
     * <p>Only those cells are inputs; anything else is ignored. Duplicates are
     * collapsed so a repeated grid cell does not double the ingredient.</p>
     */
    private static void readPedestalGrid(JsonObject recipe, MachineRecipeService.NormalizedRecipe out) {
        if (!recipe.has("input") || !recipe.get("input").isJsonArray()) return;
        for (JsonElement element : recipe.getAsJsonArray("input")) {
            if (element == null || element.isJsonNull() || !element.isJsonArray()) continue;
            JsonArray cell = element.getAsJsonArray();
            if (cell.size() == 0 || !cell.get(0).isJsonPrimitive()) continue;
            String kind = cell.get(0).getAsString();
            if (!"dict".equals(kind) && !"item".equals(kind) && !"dictframe".equals(kind)) continue;
            JsonObject input = MachineRecipeParser.parseInputEntry(element);
            if (input == null) continue;
            // A cell that parsed to its own type token is not an ingredient.
            if (input.has("dict") && kind.equals(input.get("dict").getAsString())) continue;
            if (!isDuplicate(out.inputs, input)) out.inputs.add(input);
        }
    }

    private static boolean isDuplicate(List<JsonObject> existing, JsonObject candidate) {
        if (!candidate.has("dict")) return false;
        String dict = candidate.get("dict").getAsString();
        int amount = candidate.has("amount") ? candidate.get("amount").getAsInt() : 0;
        for (JsonObject entry : existing) {
            if (!entry.has("dict") || !dict.equals(entry.get("dict").getAsString())) continue;
            int other = entry.has("amount") ? entry.get("amount").getAsInt() : 0;
            if (other == amount) return true;
        }
        return false;
    }

    private static void readArray(JsonObject recipe, String key,
                                  MachineRecipeService.NormalizedRecipe out) {
        if (!recipe.has(key) || !recipe.get(key).isJsonArray()) return;
        for (JsonElement element : recipe.getAsJsonArray(key)) {
            JsonObject input = MachineRecipeParser.parseInputEntry(element);
            if (input != null) out.inputs.add(input);
        }
    }

    /**
     * One key from the item-input vocabulary.
     */
    private static void readItemKey(JsonObject recipe, String key,
                                    MachineRecipeService.NormalizedRecipe out) {
        if (!recipe.has(key)) return;
        JsonElement element = recipe.get(key);
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            readItemArray(element.getAsJsonArray(), out);
        } else if (element.isJsonPrimitive()) {
            addFluid(out.inputFluids, element.getAsString(), 0);
        }
    }

    private static void readItemArray(JsonArray array, MachineRecipeService.NormalizedRecipe out) {
        if (array.size() == 0) return;
        if (array.get(0).isJsonArray()) {
            // Crucible-style inner arrays are [FluidName, amount] (molten
            // transfer, e.g. ["Iron", 16]) rather than item dicts. Route them to
            // inputFluids so crucible recipes carry molten inputs instead of
            // being dropped silently.
            for (JsonElement element : array) {
                JsonObject fluid = MachineRecipeParser.tryParseFluidShapedInput(element);
                if (fluid != null) {
                    out.inputFluids.add(fluid);
                    continue;
                }
                JsonObject input = MachineRecipeParser.parseInputEntry(element);
                if (input != null) out.inputs.add(input);
            }
            return;
        }
        if (array.get(0).isJsonPrimitive() && !isItemTypeToken(array.get(0).getAsString())) {
            // A fluid-typed single entry like ["BITUMEN", 16000] or
            // ["REDMUD", 450], or a pedestal 9-slot grid.
            if (array.size() >= 2 && array.get(1).isJsonPrimitive()
                    && array.get(1).getAsJsonPrimitive().isNumber()) {
                addFluid(out.inputFluids, array.get(0).getAsString(), array.get(1).getAsInt());
            } else {
                JsonObject input = MachineRecipeParser.parseInputEntry(array);
                if (input != null) out.inputs.add(input);
            }
            return;
        }
        JsonObject single = MachineRecipeParser.parseInputEntry(array);
        if (single != null) {
            out.inputs.add(single);
            return;
        }
        for (JsonElement element : array) {
            JsonObject input = MachineRecipeParser.parseInputEntry(element);
            if (input != null) out.inputs.add(input);
        }
    }

    /**
     * Explicit item/fluid list keys.
     */
    private static void readTypedKey(JsonObject recipe, String key,
                                     MachineRecipeService.NormalizedRecipe out) {
        if (!recipe.has(key)) return;
        JsonElement element = recipe.get(key);
        if (element == null || element.isJsonNull()) return;
        if (RecipeKeys.isFluidInput(key)) {
            if (element.isJsonArray()) {
                JsonArray array = element.getAsJsonArray();
                if (array.size() > 0 && array.get(0).isJsonArray()) {
                    for (JsonElement entry : array) {
                        JsonObject fluid = MachineRecipeParser.parseFluidEntry(entry);
                        if (fluid != null) out.inputFluids.add(fluid);
                    }
                } else if (array.size() > 0) {
                    JsonObject fluid = MachineRecipeParser.parseFluidEntry(element);
                    if (fluid != null) out.inputFluids.add(fluid);
                }
            }
            return;
        }
        if (!element.isJsonArray()) return;
        JsonArray array = element.getAsJsonArray();
        if (array.size() == 0) return;
        if (array.get(0).isJsonArray()) {
            for (JsonElement entry : array) {
                JsonObject input = MachineRecipeParser.parseInputEntry(entry);
                if (input != null) out.inputs.add(input);
            }
        } else {
            JsonObject input = MachineRecipeParser.parseInputEntry(element);
            if (input != null) out.inputs.add(input);
        }
    }

    /**
     * A fluid list key holding either one entry or a list of entries.
     */
    static void readFluidArray(JsonObject recipe, String key,
                               MachineRecipeService.NormalizedRecipe out) {
        if (!recipe.has(key) || !recipe.get(key).isJsonArray()) return;
        JsonArray array = recipe.get(key).getAsJsonArray();
        if (array.size() == 0) return;
        if (array.get(0).isJsonArray()) {
            for (JsonElement entry : array) {
                JsonObject fluid = MachineRecipeParser.parseFluidEntry(entry);
                if (fluid != null) out.inputFluids.add(fluid);
            }
        } else {
            JsonObject fluid = MachineRecipeParser.parseFluidEntry(recipe.get(key));
            if (fluid != null) out.inputFluids.add(fluid);
        }
    }

    /**
     * Bare tokens that mark an item-dict entry rather than a fluid name.
     */
    private static boolean isItemTypeToken(String token) {
        return "item".equals(token) || "dict".equals(token)
                || "nbt".equals(token) || "dictframe".equals(token);
    }

    private static void addFluid(List<JsonObject> target, String name, int amount) {
        if (name == null || name.isEmpty() || "NONE".equalsIgnoreCase(name)) return;
        JsonObject fluid = MachineRecipeParser.parseFluidEntryJson(name, amount);
        if (fluid != null) target.add(fluid);
    }
}
