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
    @Override
    public boolean isAvailable() {
        // Any recipe class is enough: a partial install still contributes.
        for (String className : HbmDescriptor.recipeClasses()) {
            try {
                Class.forName(className);
                return true;
            } catch (Throwable missing) {
                // Try the next probe.
            }
        }
        return false;
    }

    @Override
    public List<MachineRecipeService.NormalizedRecipe> load() {
        if (!isAvailable()) return new ArrayList<>();
        return MachineRecipeReflection.readLive();
    }
}
