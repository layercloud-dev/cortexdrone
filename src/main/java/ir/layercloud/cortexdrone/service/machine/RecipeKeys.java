package ir.layercloud.cortexdrone.service.machine;

/**
 * JSON key vocabulary for machine recipe files.
 *
 * <p>These key sets used to be declared inline in the middle of a 336-line
 * parser, with {@code byproducts} listed twice and {@code inputItem} appearing
 * in two different passes. Keeping them together documents the accepted schema
 * and makes overlap obvious, which is what allowed the duplicate handling to go
 * unnoticed.</p>
 */
final class RecipeKeys {
    private RecipeKeys() {
    }

    /**
     * Scalar fields that contribute an item input.
     *
     * <p>Each may hold a single entry, a list of entries, or a nested list.</p>
     */
    static final String[] ITEM_INPUTS = {
            "input", "input1", "input2", "ingredients", "ingredient",
            "toppings", "pcb", "solder", "wafer", "solidInput",
            "acid", "hydrogen", "flux", "stamp", "particle", "gas", "gasIn", "itemIn"
    };

    /**
     * Fields holding explicit item/fluid input lists.
     */
    static final String[] TYPED_INPUTS = {
            "inputItems", "inputItem", "inputFluids", "inputFluid"
    };

    /**
     * Fields that contribute an item or fluid output.
     */
    static final String[] OUTPUTS = {
            "output", "output0", "output1", "output2", "output3", "output4",
            "outputItem", "outputItems", "outputs",
            "solid", "solidOutput", "byproduct", "byproducts",
            "outputFluid", "outputFluids", "fluidOutput"
    };

    /**
     * True when an output key names a fluid.
     */
    static boolean isFluidOutput(String key) {
        return key.contains("Fluid") || "fluidOutput".equals(key);
    }

    /**
     * True when an input key names a fluid.
     */
    static boolean isFluidInput(String key) {
        return key.contains("Fluid");
    }

    /**
     * True when the value carries no recipe content.
     *
     * <p>Used by the nested-recipe expansion to skip empty shapes instead of
     * emitting a recipe with no inputs and no outputs.</p>
     */
    static boolean isEmptyRecipe(MachineRecipeService.NormalizedRecipe recipe) {
        return recipe.inputs.isEmpty() && recipe.outputs.isEmpty()
                && recipe.inputFluids.isEmpty() && recipe.outputFluids.isEmpty();
    }
}
