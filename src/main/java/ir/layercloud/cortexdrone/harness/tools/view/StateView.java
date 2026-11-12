package ir.layercloud.cortexdrone.harness.tools.view;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.visuals.VisualsState;
import ir.layercloud.cortexdrone.harness.session.DroneSession;
import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.manager.navigation.impl.NavigationStatus;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.ProtectionZones;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

/**
 * JSON boundary for the {@code state} tool: one complete live snapshot of the
 * drone, its task, navigation session, lighting and inventory.
 */
@RequiredArgsConstructor
public final class StateView {
    private final AIDroneManager manager;

    public JsonObject status(DroneController controller, JsonObject args) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return ToolJson.error("No active drone or world");
        }
        Drone drone = controller.drone;
        DroneSession session = this.manager.getSessionManager().session(controller);
        NavigationState nav = this.manager.getNavigationManager().state(controller);
        NavigationStatus navigation =
                this.manager.getNavigationManager().status(controller);
        VisualsState visuals = this.manager.getVisualsManager().state(controller);

        JsonObject response = ToolJson.ok();
        if (ToolArgs.has(args, "loopId") && args.get("loopId").getAsInt() > 0
                && session.isLoopCancelled(args.get("loopId").getAsInt())) {
            response.addProperty("aborted", true);
        }

        response.addProperty("id", controller.id);
        response.addProperty("name", controller.name);
        response.addProperty("owner", controller.ownerName);
        response.addProperty("lastThought", session.lastThought() != null ? session.lastThought() : "");
        response.addProperty("x", drone.posX);
        response.addProperty("y", drone.posY);
        response.addProperty("z", drone.posZ);
        // Compatibility alias: generated scripts frequently write s.position.x.
        // The live log shows five separate "Cannot read property x from
        // undefined" turns caused only by the missing alias. Keep x/y/z as the
        // canonical flat fields and expose the object as well.
        JsonObject position = new JsonObject();
        position.addProperty("x", drone.posX);
        position.addProperty("y", drone.posY);
        position.addProperty("z", drone.posZ);
        position.addProperty("dimension", drone.dimension);
        response.add("position", position);
        response.addProperty("dimension", drone.dimension);
        response.addProperty("protectedZones", ProtectionZones.count());

        response.addProperty("activeTask", session.isActive());
        response.addProperty("taskPrompt", session.getPrompt());
        response.addProperty("taskSummary", session.getSummary());
        response.addProperty("taskAgeMs", session.getAgeMs());
        JsonArray notes = new JsonArray();
        for (String note : session.getNotes()) notes.add(new JsonPrimitive(note));
        response.add("taskNotes", notes);

        double distanceToGoal = nav == null || Double.isNaN(nav.goalX) ? -1.0
                : Math.sqrt((drone.posX - nav.goalX) * (drone.posX - nav.goalX)
                + (drone.posY - nav.goalY) * (drone.posY - nav.goalY)
                + (drone.posZ - nav.goalZ) * (drone.posZ - nav.goalZ));
        response.addProperty("navigationActive", navigation.isActive());
        response.addProperty("navigationPhase", navigation.getPhase());
        response.addProperty("navigationReason", navigation.getReason());
        response.addProperty("navigationError", nav != null ? nav.error : "");
        response.addProperty("distanceToGoal", Math.round(distanceToGoal * 100.0) / 100.0);
        response.addProperty("arrived", !navigation.isActive()
                && distanceToGoal >= 0.0 && distanceToGoal <= 1.0);
        response.addProperty("blockedWaypoints", nav != null ? nav.solidWaypointHits.size() : 0);
        response.addProperty("busy", navigation.isActive()
                || this.manager.getActionRegistry().activeContextCount(controller) > 0);

        response.addProperty("lightActive", visuals.lightAura);
        response.addProperty("lightRadius", visuals.lightRadius);
        response.addProperty("lightLevel", visuals.lightIntensity);
        response.addProperty("lightColor", String.format("0x%06X", visuals.lightColor & 0xFFFFFF));

        JsonArray inventory = new JsonArray();
        IInventory inv = drone.mainInventory();
        int slots = inv != null ? inv.getSizeInventory() : 0;
        int usedSlots = 0;
        int totalItems = 0;
        if (inv != null) {
            for (int i = 0; i < slots; i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack != null && stack.stackSize > 0) {
                    usedSlots++;
                    totalItems += stack.stackSize;
                    JsonObject item = ItemClassifier.itemStackToJson(i, stack);
                    // OpenComputers callbacks are 1-based. The live log shows
                    // select(0) -> "invalid slot" and a learner having to
                    // discover the offset by trial. Expose both explicitly.
                    item.addProperty("ocSlot", i + 1);
                    inventory.add(item);
                }
            }
        }
        // Report the drone's real inventory size. The old code assumed 8 slots,
        // so a drone with no inventory upgrades looked like it had room.
        response.addProperty("slots", slots);
        response.addProperty("freeSlots", Math.max(0, slots - usedSlots));
        response.addProperty("totalItems", totalItems);
        response.addProperty("isFull", InventoryService.isInventoryFull(inv));
        response.add("inventory", inventory);
        return response;
    }
}
