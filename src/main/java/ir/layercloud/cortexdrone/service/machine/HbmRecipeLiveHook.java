package ir.layercloud.cortexdrone.service.machine;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional hook: live HBM recipe objects read by reflection.
 *
 * <p>Some recipe families (smithing pairs, construction-index tags) exist only in
 * memory. The hook is unavailable when the HBM recipe classes are absent, so a
 * server without HBM never pays for reflection.</p>
 */
public final class HbmRecipeLiveHook implements MachineRecipeHook {
    private static final String PROBE_CLASS = "com.hbm.inventory.recipes.anvil.AnvilRecipes";

    @Override
    public String id() {
        return "hbm-live";
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName(PROBE_CLASS);
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }

    @Override
    public List<MachineRecipeService.NormalizedRecipe> load() {
        if (!isAvailable()) return new ArrayList<MachineRecipeService.NormalizedRecipe>();
        return MachineRecipeReflection.readLive();
    }
}
