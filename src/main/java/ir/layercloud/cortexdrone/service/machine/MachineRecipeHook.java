package ir.layercloud.cortexdrone.service.machine;

import java.util.List;

/**
 * One optional source of machine recipe data.
 *
 * <p>A hook is read-only: it discovers recipes and returns normalized values. It
 * never mutates world state, runs an action, or decides what the drone should
 * craft. Hooks exist so the HBM integration is optional: with no HBM installed
 * the hook reports itself unavailable and the service simply has no recipes.</p>
 */
public interface MachineRecipeHook {
    /** True when the mod/config this hook reads is present. */
    boolean isAvailable();

    /** All recipes this source currently exposes. */
    List<MachineRecipeService.NormalizedRecipe> load();
}
