package ir.layercloud.cortexdrone.manager.interact.impl;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.interact.InteractionState;
import ir.layercloud.cortexdrone.action.interact.InteractionRequest;
import ir.layercloud.cortexdrone.action.interact.InteractionResult;
import ir.layercloud.cortexdrone.action.interact.impl.*;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.impl.InteractionContext;
import ir.layercloud.cortexdrone.manager.ActionManager;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Long-lived interaction manager. Every interaction tool is its own operation;
 * attacks are {@code attack_entity}, not a flag on {@code use_item}.
 */
public final class InteractionManager extends ActionManager<InteractionContext, InteractionRequest, InteractionResult> {
    private final InventoryService inventoryService;
    private final DroneLogger logger;
    private final Map<DroneController, InteractionState> states = new ConcurrentHashMap<>();

    public InteractionManager(InventoryService inventoryService, DroneLogger logger) {
        super("interact", "interact_block", "use_item", "attack_entity",
                "pick_entity", "unpick_entity");
        this.inventoryService = inventoryService;
        this.logger = logger;
    }

    /** Per-drone interaction state, created on first use. */
    public InteractionState state(DroneController controller) {
        if (controller == null) return null;
        return this.states.computeIfAbsent(controller, ignored -> new InteractionState());
    }

    /** Per-tick cooldown maintenance. */
    public void tickCooldown(DroneController controller) {
        InteractionState state = state(controller);
        if (state != null && state.attackCooldown > 0) state.attackCooldown--;
    }

    public void forget(DroneController controller) {
        if (controller != null) this.states.remove(controller);
    }

    @Override
    protected InteractionContext createContext(DroneController controller, EntityPlayerMP player,
                                               String operation) {
        return new InteractionContext(controller, player, operation);
    }

    @Override
    protected Action<InteractionContext> createAction(InteractionContext context, String operation,
                                                      InteractionRequest request) {
        if ("interact_block".equals(operation)) {
            if (!request.isCoordinates()) {
                return ignored -> context.result(InteractionResult.failure(
                        "Missing x/y/z for interact_block", "activated", false));
            }
            return new InteractBlockAction(this.inventoryService, this.logger, request);
        }
        if ("use_item".equals(operation)) {
            return new UseItemAction(request);
        }
        if ("attack_entity".equals(operation)) {
            if (!request.hasEntity()) {
                return ignored -> context.result(InteractionResult.failure(
                        "attack_entity requires entityId"));
            }
            return new AttackEntityAction(state(context.controller()), request);
        }
        if ("pick_entity".equals(operation)) {
            if (!request.hasEntity()) {
                return ignored -> context.result(InteractionResult.failure(
                        "pick_entity requires entityId", "picked", false));
            }
            return new PickEntityAction(request);
        }
        if ("unpick_entity".equals(operation)) return new UnpickEntityAction(request);
        throw new IllegalArgumentException("Unsupported interaction action: " + operation);
    }

    @Override
    protected InteractionResult result(InteractionContext context) {
        return context.result();
    }
}
