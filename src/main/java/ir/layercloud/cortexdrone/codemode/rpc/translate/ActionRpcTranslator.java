package ir.layercloud.cortexdrone.codemode.rpc.translate;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.model.DroneController;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Single JSON boundary for all action categories. Action services and actions
 * only see typed requests/results; this router is the only place that knows
 * both JSON and action operations.
 */
public final class ActionRpcTranslator {
    private final MovementRpcTranslator movement;
    private final BuildingRpcTranslator building;
    private final InteractionRpcTranslator interaction;
    private final VisualsRpcTranslator visuals;
    private final InventoryRpcTranslator inventory;

    public ActionRpcTranslator(AIDroneManager manager) {
        this.movement = new MovementRpcTranslator(manager.getMovementService());
        this.building = new BuildingRpcTranslator(manager.getBuildingService());
        this.interaction = new InteractionRpcTranslator(manager.getInteractionService());
        this.visuals = new VisualsRpcTranslator(manager, manager.getVisualsService());
        this.inventory = new InventoryRpcTranslator(manager.getInventoryActionService());
    }

    public JsonObject handle(DroneController controller, EntityPlayerMP player,
                             String operation, JsonObject args) {
        if (isMovement(operation)) return this.movement.handle(controller, player, operation, args);
        if ("break_block".equals(operation) || "place_block".equals(operation)) {
            return this.building.handle(controller, player, operation, args);
        }
        if (isInteraction(operation)) return this.interaction.handle(controller, player, operation, args);
        if (isVisuals(operation)) return this.visuals.handle(controller, player, operation, args);
        if ("transfer".equals(operation) || "craft".equals(operation)) {
            return this.inventory.handle(controller, player, operation, args);
        }
        return RpcJson.error("No action registered for: " + operation);
    }

    public JsonObject status(DroneController controller, EntityPlayerMP player, JsonObject args) {
        return this.visuals.status(controller, player, args);
    }

    public JsonObject skills(DroneController controller, EntityPlayerMP player,
                             String operation, JsonObject args) {
        return this.visuals.skills(controller, player, operation, args);
    }

    public JsonObject configureLight(DroneController controller, JsonObject args) {
        return this.visuals.configureLight(controller, args);
    }

    private static boolean isMovement(String operation) {
        return "navigate".equals(operation) || "tunnel".equals(operation)
            || "digup".equals(operation) || "digdown".equals(operation)
            || "get_breadcrumbs".equals(operation);
    }

    private static boolean isInteraction(String operation) {
        return "interact_block".equals(operation) || "use_item".equals(operation)
            || "pick_entity".equals(operation) || "unpick_entity".equals(operation);
    }

    private static boolean isVisuals(String operation) {
        return "set_led".equals(operation) || "led_effect".equals(operation)
            || "spawn_particles".equals(operation) || "play_sound".equals(operation)
            || "play_note".equals(operation) || "say".equals(operation)
            || "set_status_text".equals(operation);
    }
}
