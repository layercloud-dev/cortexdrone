package ir.layercloud.cortexdrone.harness.tools;

/**
 * Tool-facing alias of the inventory bounds.
 *
 * <p>The values are owned by {@link ir.layercloud.cortexdrone.service.inventory.InventoryLimits}
 * so the service layer does not have to depend on the harness. This class exists
 * so tool schemas read naturally; it declares no numbers of its own.</p>
 */
public final class InventoryLimits {
    private InventoryLimits() {
    }

    /** @see ir.layercloud.cortexdrone.service.inventory.InventoryLimits#MIN_RADIUS */
    public static final int MIN_RADIUS =
            ir.layercloud.cortexdrone.service.inventory.InventoryLimits.MIN_RADIUS;
    /** @see ir.layercloud.cortexdrone.service.inventory.InventoryLimits#MAX_RADIUS */
    public static final int MAX_RADIUS =
            ir.layercloud.cortexdrone.service.inventory.InventoryLimits.MAX_RADIUS;
    /** @see ir.layercloud.cortexdrone.service.inventory.InventoryLimits#MAX_FURNACE_RADIUS */
    public static final int MAX_FURNACE_RADIUS =
            ir.layercloud.cortexdrone.service.inventory.InventoryLimits.MAX_FURNACE_RADIUS;
    /** @see ir.layercloud.cortexdrone.service.inventory.InventoryLimits#DEFAULT_RADIUS */
    public static final int DEFAULT_RADIUS =
            ir.layercloud.cortexdrone.service.inventory.InventoryLimits.DEFAULT_RADIUS;
    /** @see ir.layercloud.cortexdrone.service.inventory.InventoryLimits#DEFAULT_CHEST_RADIUS */
    public static final int DEFAULT_CHEST_RADIUS =
            ir.layercloud.cortexdrone.service.inventory.InventoryLimits.DEFAULT_CHEST_RADIUS;
    /** @see ir.layercloud.cortexdrone.service.inventory.InventoryLimits#MAX_TRANSFER_COUNT */
    public static final int MAX_TRANSFER_COUNT =
            ir.layercloud.cortexdrone.service.inventory.InventoryLimits.MAX_TRANSFER_COUNT;
    /** @see ir.layercloud.cortexdrone.service.inventory.InventoryLimits#MAX_RESULTS */
    public static final int MAX_RESULTS =
            ir.layercloud.cortexdrone.service.inventory.InventoryLimits.MAX_RESULTS;
}
