package ir.layercloud.cortexdrone.harness;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.AIConfig;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.world.WorldScanner;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.List;
import java.util.Map;

/**
 * Builds the "Initial World State" JSON that is appended to the system prompt at
 * the start of every task. Read-only: it inspects the player, the drone, saved
 * locations and nearby resources, and never changes world or drone state.
 */
final class DroneContextBuilder {
    private DroneContextBuilder() {
    }

    static JsonObject build(AIDroneManager manager, WorldScanner worldScanner,
                            EntityPlayerMP player, DroneController ctrl) {
        JsonObject ctx = new JsonObject();
        World world = ctrl.drone.worldObj;
        double refX = player != null ? player.posX : ctrl.drone.posX;
        double refY = player != null ? player.posY : ctrl.drone.posY;
        double refZ = player != null ? player.posZ : ctrl.drone.posZ;
        ctx.addProperty("player", player != null ? player.getCommandSenderName() : ctrl.ownerName);
        ctx.addProperty("playerX", MathHelper.floor_double(refX));
        ctx.addProperty("playerY", MathHelper.floor_double(refY));
        ctx.addProperty("playerZ", MathHelper.floor_double(refZ));
        int facing = player != null ? (MathHelper.floor_double((double) (player.rotationYaw * 4.0f / 360.0f) + 0.5D) & 3) : 0;
        String[] dirs = new String[]{"South(+Z)", "West(-X)", "North(-Z)", "East(+X)"};
        ctx.addProperty("playerFacing", dirs[facing]);
        if (player != null) {
            ctx.addProperty("playerYaw", Math.round(player.rotationYaw * 10.0f) / 10.0f);
            ctx.addProperty("playerPitch", Math.round(player.rotationPitch * 10.0f) / 10.0f);
            try {
                JsonObject lookBlock = WorldScanner.raycastPlayerLook(world, player, 80.0);
                if (lookBlock != null) ctx.add("playerCrosshairLookBlock", lookBlock);
                JsonObject supportBlock = WorldScanner.findPlayerSupportBlock(world, player);
                if (supportBlock != null) ctx.add("playerStandingOnBlock", supportBlock);
            } catch (Throwable ignored) {
            }
            ItemStack held = player.getCurrentEquippedItem();
            ctx.addProperty("playerHeldItem", (held != null && held.stackSize > 0)
                    ? (held.getDisplayName() + ":" + held.getItemDamage() + " x" + held.stackSize)
                    : "Empty");
        }
        ctx.addProperty("droneName", ctrl.name);
        ctx.addProperty("droneX", Math.round(ctrl.drone.posX * 10.0) / 10.0);
        ctx.addProperty("droneY", Math.round(ctrl.drone.posY * 10.0) / 10.0);
        ctx.addProperty("droneZ", Math.round(ctrl.drone.posZ * 10.0) / 10.0);
        ctx.addProperty("navigationActive", manager.getNavigationManager().isActive(ctrl));
        boolean canSeeSky = false;
        try {
            canSeeSky = world.canBlockSeeTheSky(MathHelper.floor_double(ctrl.drone.posX),
                    MathHelper.floor_double(ctrl.drone.posY), MathHelper.floor_double(ctrl.drone.posZ));
        } catch (Throwable ignored) {
        }
        ctx.addProperty("droneCanSeeSky", canSeeSky);
        ctx.addProperty("breadcrumbCount", manager.getBreadcrumbService().count(ctrl));
        ctx.addProperty("droneInventory", InventoryService.formatInventorySummary(ctrl.drone.mainInventory()));
        ctx.addProperty("playerInventory", player != null ? InventoryService.formatInventorySummary(player.inventory) : "Offline");

        ctx.addProperty("distToPlayerM", Math.round(Math.hypot(
                ctrl.drone.posX - refX, ctrl.drone.posY - refY) * 10.0) / 10.0);
        ctx.addProperty("activeTaskRunning", ctrl.task.isActive() || ctrl.hasActiveTaskOrLoop(0));
        ctx.addProperty("activeScriptCount", ctrl.activeScriptCount.get());
        ctx.addProperty("taskPrompt", ctrl.task.getPrompt());
        ctx.addProperty("taskSummary", ctrl.task.getSummary());
        ctx.addProperty("singleSession", true);
        ctx.addProperty("lastPathPartial", ctrl.lastPathPartial);
        ctx.addProperty("pathNodesExpanded", ctrl.lastPathNodesExpanded);
        if (!ctrl.lastThought.isEmpty()) ctx.addProperty("lastThought", ctrl.lastThought);
        JsonObject taskState = new JsonObject();
        taskState.addProperty("navigationActive", manager.getNavigationManager().isActive(ctrl));
        taskState.addProperty("activeActions", manager.getActionRegistry().activeContextCount(ctrl));
        ctx.add("task", taskState);

        String ownerKey = player != null ? player.getCommandSenderName() : ctrl.ownerName;
        List<AIConfig.SavedLocation> savedLocs = manager.getConfig().listLocations(ownerKey);
        if (!savedLocs.isEmpty()) {
            JsonObject locsObj = new JsonObject();
            for (AIConfig.SavedLocation loc : savedLocs) {
                locsObj.addProperty(loc.getName(), "(" + loc.getX() + "," + loc.getY() + "," + loc.getZ() + ")"
                        + (loc.getTags() != null && !loc.getTags().isEmpty() ? " [" + loc.getTags() + "]" : ""));
            }
            ctx.add("savedLocations", locsObj);
        }

        JsonObject resources = new JsonObject();
        try {
            Map<String, int[]> scan = worldScanner.scanNearbyResources(world, refX, refY, refZ, 24);
            for (Map.Entry<String, int[]> e : scan.entrySet()) {
                int[] v = e.getValue();
                resources.addProperty(e.getKey(), v[0] + " (nearest: " + v[1] + "," + v[2] + "," + v[3] + ")");
            }
        } catch (Throwable ignored) {
        }
        ctx.add("nearbyBlocks24m", resources);
        return ctx;
    }
}
