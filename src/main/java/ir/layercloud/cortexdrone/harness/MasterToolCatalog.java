package ir.layercloud.cortexdrone.harness;

/**
 * Canonical, dependency-free list of the master tools exposed to Code Mode.
 * Keeping it in the harness package lets tests assert the surface without
 * loading Minecraft/Forge classes.
 */
public final class MasterToolCatalog {
    public static final String[] METHODS = new String[]{
        "state", "inspect", "navigate", "tunnel", "digup", "digdown",
        "break_block", "place_block", "interact_block", "use_item",
        "pick_entity", "unpick_entity",
        "transfer", "craft", "visuals", "skills", "cancel"
    };

    public static final String[] REMOVED_COMPOSITE_TOOLS = new String[]{
        "queue_mine", "queue_build", "mine_filter", "mine_rect", "mine_box",
        "gather_distant", "hunt", "farm", "chop", "build_structure",
        "replace_room", "replace_box", "replace_blocks", "sort_chests",
        "transfer_containers", "machine_craft", "cook", "smelt"
    };

    private MasterToolCatalog() {
    }
}
