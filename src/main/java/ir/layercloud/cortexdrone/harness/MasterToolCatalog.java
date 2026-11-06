package ir.layercloud.cortexdrone.harness;

import lombok.NoArgsConstructor;

/**
 * Canonical, dependency-free list of the master tools exposed to Code Mode.
 * Keeping it in the harness package lets tests assert the surface without
 * loading Minecraft/Forge classes.
 */
@NoArgsConstructor
public final class MasterToolCatalog {
    public static final String[] METHODS = new String[]{
            "state", "inspect", "goto", "stop",
            "break_block", "place_block", "interact_block", "use_item",
            "pick_entity", "unpick_entity",
            "transfer", "craft", "visuals", "skills", "cancel"
    };
}
