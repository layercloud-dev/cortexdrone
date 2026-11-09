package ir.layercloud.cortexdrone.service.machine;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Data descriptor for the HBM mod integration.
 *
 * <p>The class names, the recipe directory and the wrapper classes were string
 * literals scattered through {@code MachineRecipeReflection},
 * {@code HbmRecipeFileHook} and {@code DocLookupService}. They are facts about
 * one optional mod, not about this mod's logic, so they live in one descriptor:
 * adding a second machine mod means adding a descriptor, not editing three
 * classes.</p>
 *
 * <p>Everything here is best-effort: HBM may be absent, and every consumer must
 * survive a {@link ClassNotFoundException}.</p>
 */
public final class HbmDescriptor {
    /** Mod id used as a registry prefix. */
    public static final String MOD_ID = "hbm";
    /** Directory under {@code config/} holding flat recipe JSON files. */
    public static final String RECIPE_DIRECTORY = "hbmRecipes";

    /**
     * Classes probed for live in-memory recipes, most specific first.
     *
     * <p>Each is attempted independently: a partial install still contributes
     * whatever it registers.</p>
     */
    private static final List<String> RECIPE_CLASSES = Collections.unmodifiableList(Arrays.asList(
            "com.hbm.inventory.recipes.anvil.AnvilRecipes",
            "com.hbm.inventory.recipes.loader.SerializableRecipe"
    ));

    /**
     * Wrapper classes whose fields are unwrapped when reading a live recipe.
     *
     * <p>HBM wraps inputs/outputs in {@code ComparableStack}, {@code ItemStack}
     * and fluid tuples; these names let the reader find the real value without
     * hard-coding a field list per machine.</p>
     */
    private static final List<String> WRAPPER_CLASSES = Collections.unmodifiableList(Arrays.asList(
            "com.hbm.inventory.material.Mats$MatStack",
            "com.hbm.inventory.fluid.FluidStack",
            "com.hbm.inventory.RecipesCommon$ComparableStack",
            "com.hbm.inventory.RecipesCommon$AStack"
    ));

    /**
     * Fallback recipe handlers, probed when the dynamic handler list is empty.
     *
     * <p>Each entry is {@code {class, machine kind}}.</p>
     */
    private static final List<String[]> FALLBACK_HANDLERS =
            Collections.unmodifiableList(Arrays.asList(
                    new String[]{"com.hbm.inventory.recipes.AssemblyMachineRecipes", "assembly_machine"},
                    new String[]{"com.hbm.inventory.recipes.ChemicalPlantRecipes", "chemical_plant"},
                    new String[]{"com.hbm.inventory.recipes.BlastFurnaceRecipesNT", "blast_furnace"}
            ));

    /** External documentation endpoints, most specific first. */
    private static final List<String> DOC_ENDPOINTS = Collections.unmodifiableList(Arrays.asList(
            "https://ntm.fandom.com/api.php",
            "https://minecraft.wiki/api.php"
    ));

    private HbmDescriptor() {
    }

    /** Fallback handlers as {@code {className, machineKind}} pairs. */
    public static List<String[]> fallbackHandlers() {
        return FALLBACK_HANDLERS;
    }

    /** Recipe-bearing classes to probe reflectively. */
    public static List<String> recipeClasses() {
        return RECIPE_CLASSES;
    }

    /** Wrapper classes to unwrap when reading live recipes. */
    public static List<String> wrapperClasses() {
        return WRAPPER_CLASSES;
    }

    /** Wiki API endpoints used by the documentation fallback. */
    public static List<String> docEndpoints() {
        return DOC_ENDPOINTS;
    }

    /** Host used for the HBM wiki. */
    public static final String HBM_WIKI_HOST = "ntm.fandom.com";
    /** Host used for the vanilla/Minecraft wiki. */
    public static final String VANILLA_WIKI_HOST = "minecraft.wiki";

    /** True when {@code registryName} belongs to this mod. */
    public static boolean ownsRegistryName(String registryName) {
        return registryName != null
                && registryName.toLowerCase(java.util.Locale.ROOT).startsWith(MOD_ID + ":");
    }

    /** True when {@code className} is one of the classes this mod provides. */
    public static boolean isOwnClass(String className) {
        return className != null
                && className.toLowerCase(java.util.Locale.ROOT).startsWith("com.hbm.");
    }
}
