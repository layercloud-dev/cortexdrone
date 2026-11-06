package ir.layercloud.cortexdrone.harness.rpc.translate;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Single JSON boundary for all action categories. Managers and actions only
 * see typed requests/results; this router is the only place that knows both
 * JSON and action operations.
 */
public final class ActionRpcTranslator {
    private final NavigationRpcTranslator navigation;
    private final BuildingRpcTranslator building;
    private final InteractionRpcTranslator interaction;
    private final VisualsRpcTranslator visuals;
    private final InventoryRpcTranslator inventory;

    public ActionRpcTranslator(AIDroneManager manager) {
        this.navigation = new NavigationRpcTranslator(manager.getNavigationManager());
        this.building = new BuildingRpcTranslator(manager.getBuildingManager());
        this.interaction = new InteractionRpcTranslator(manager.getInteractionManager());
        this.visuals = new VisualsRpcTranslator(manager, manager.getVisualsManager());
        this.inventory = new InventoryRpcTranslator(manager.getInventoryManager());
    }

    public JsonObject handle(DroneController controller, EntityPlayerMP player,
                             String operation, JsonObject args) {
        if ("goto".equals(operation) || "stop".equals(operation)) {
            return this.navigation.handle(controller, player, operation, args);
        }
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

    public JsonObject status(DroneController controller, JsonObject args) {
        return this.visuals.status(controller, args);
    }

    public JsonObject skills(DroneController controller,
                             String operation, JsonObject args) {
        return this.visuals.skills(controller, operation, args);
    }


    private static boolean isInteraction(String operation) {
        return "interact_block".equals(operation) || "use_item".equals(operation)
                || "pick_entity".equals(operation) || "unpick_entity".equals(operation);
    }

    private static boolean isVisuals(String operation) {
        return "set_led".equals(operation)
                || "set_light".equals(operation)
                || "spawn_particles".equals(operation) || "play_sound".equals(operation)
                || "play_note".equals(operation) || "say".equals(operation)
                || "set_status_text".equals(operation);
    }
}
