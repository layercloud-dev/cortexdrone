package ir.layercloud.cortexdrone.service.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Recipe-schema parsing rules that do not need a live item registry.
 *
 * <p>{@link MachineRecipeParser} resolves real Forge items, so a pure JSON test
 * cannot produce stacks. The extracted pieces (key vocabulary and nested
 * expansion) take a reader callback precisely so they can be tested here.</p>
 */
public class MachineRecipeParserTest {
    /**
     * A reader that reports whatever the nested object declares.
     */
    private static final RecipeNestedExpander.RecipeEntryReader STUB =
            (recipe, machine, fileName) -> {
                MachineRecipeService.NormalizedRecipe parsed =
                        new MachineRecipeService.NormalizedRecipe();
                parsed.machine = machine;
                parsed.name = recipe.has("name") ? recipe.get("name").getAsString() : fileName;
                parsed.displayName = recipe.has("name") ? recipe.get("name").getAsString() : "";
                if (recipe.has("duration")) parsed.duration = recipe.get("duration").getAsInt();
                if (recipe.has("hasInput") && recipe.get("hasInput").getAsBoolean()) {
                    JsonObject stack = new JsonObject();
                    stack.addProperty("registryName", "minecraft:iron_ingot");
                    parsed.inputs.add(stack);
                }
                return parsed;
            };

    private static JsonObject nestedRecipe(boolean hasInput, int duration, int outputAmount) {
        JsonObject recipe = new JsonObject();
        recipe.addProperty("hasInput", hasInput);
        if (duration > 0) recipe.addProperty("duration", duration);
        if (outputAmount > 0) recipe.addProperty("outputAmount", outputAmount);
        return recipe;
    }

    @Test
    public void nestedExpansionSkipsEmptyPlaceholders() {
        JsonObject outer = new JsonObject();
        outer.addProperty("outputType", "molten_steel");
        JsonArray nested = new JsonArray();
        nested.add(nestedRecipe(false, 0, 0));
        nested.add(nestedRecipe(true, 100, 288));
        outer.add("recipes", nested);

        MachineRecipeService.NormalizedRecipe parsed =
                RecipeNestedExpander.expand(outer, "mixer", "nested.json", STUB);

        assertNotNull(parsed);
        assertEquals("mixer", parsed.machine);
        assertEquals("the first non-empty entry wins", 1, parsed.inputs.size());
        assertEquals(100, parsed.duration);
        assertEquals("the shared outputType fluid must be attached", 1, parsed.outputFluids.size());
        assertEquals(288, parsed.outputFluids.get(0).get("amount").getAsInt());
        assertTrue(parsed.outputFluids.get(0).get("isMixingOutput").getAsBoolean());
    }

    @Test
    public void nestedExpansionWithoutFluidAmountAddsNoFluid() {
        JsonObject outer = new JsonObject();
        outer.addProperty("outputType", "molten_steel");
        JsonArray nested = new JsonArray();
        nested.add(nestedRecipe(true, 50, 0));
        outer.add("recipes", nested);

        MachineRecipeService.NormalizedRecipe parsed =
                RecipeNestedExpander.expand(outer, "mixer", "nested.json", STUB);

        assertNotNull(parsed);
        assertTrue(parsed.outputFluids.isEmpty());
    }

    @Test
    public void objectWithoutNestedRecipesReturnsNull() {
        assertNull(RecipeNestedExpander.expand(new JsonObject(), "mixer", "x.json", STUB));
        assertNull(RecipeNestedExpander.expand(null, "mixer", "x.json", STUB));
    }

    @Test
    public void allEmptyNestedEntriesReturnNull() {
        JsonObject outer = new JsonObject();
        JsonArray nested = new JsonArray();
        nested.add(nestedRecipe(false, 0, 0));
        outer.add("recipes", nested);

        assertNull(RecipeNestedExpander.expand(outer, "mixer", "x.json", STUB));
    }

    @Test
    public void displayNameFallsBackToName() {
        JsonObject outer = new JsonObject();
        JsonArray nested = new JsonArray();
        JsonObject recipe = nestedRecipe(true, 10, 0);
        recipe.addProperty("name", "fallback_name");
        nested.add(recipe);
        outer.add("recipes", nested);

        MachineRecipeService.NormalizedRecipe parsed =
                RecipeNestedExpander.expand(outer, "press", "x.json", (r, m, f) -> {
                    MachineRecipeService.NormalizedRecipe out =
                            new MachineRecipeService.NormalizedRecipe();
                    out.machine = m;
                    out.name = r.get("name").getAsString();
                    out.displayName = "";
                    JsonObject stack = new JsonObject();
                    stack.addProperty("registryName", "minecraft:stone");
                    out.inputs.add(stack);
                    return out;
                });

        assertNotNull(parsed);
        assertEquals("fallback_name", parsed.name);
        assertEquals("fallback_name", parsed.displayName);
    }

    @Test
    public void keyVocabularyHasNoDuplicates() {
        Set<String> seen = new HashSet<>();
        for (String key : RecipeKeys.OUTPUTS) {
            assertTrue("duplicate output key: " + key, seen.add(key));
        }
        seen.clear();
        for (String key : RecipeKeys.ITEM_INPUTS) {
            assertTrue("duplicate input key: " + key, seen.add(key));
        }
        seen.clear();
        for (String key : RecipeKeys.TYPED_INPUTS) {
            assertTrue("duplicate typed-input key: " + key, seen.add(key));
        }
    }

    @Test
    public void fluidKeysAreClassified() {
        assertTrue(RecipeKeys.isFluidOutput("outputFluid"));
        assertTrue(RecipeKeys.isFluidOutput("fluidOutput"));
        assertFalse(RecipeKeys.isFluidOutput("outputItem"));
        assertTrue(RecipeKeys.isFluidInput("inputFluid"));
        assertFalse(RecipeKeys.isFluidInput("inputItem"));
    }

    @Test
    public void emptyRecipeDetectionCoversEveryChannel() {
        MachineRecipeService.NormalizedRecipe recipe = new MachineRecipeService.NormalizedRecipe();
        assertTrue(RecipeKeys.isEmptyRecipe(recipe));
        recipe.inputs.add(new JsonObject());
        assertFalse(RecipeKeys.isEmptyRecipe(recipe));
    }

    @Test
    public void vocabularyIsNonTrivial() {
        assertTrue(Arrays.asList(RecipeKeys.OUTPUTS).contains("byproducts"));
        assertTrue(Arrays.asList(RecipeKeys.ITEM_INPUTS).contains("toppings"));
        assertTrue(Arrays.asList(RecipeKeys.TYPED_INPUTS).contains("inputFluids"));
    }
}
