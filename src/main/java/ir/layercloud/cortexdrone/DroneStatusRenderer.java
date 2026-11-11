package ir.layercloud.cortexdrone;

import ir.layercloud.cortexdrone.action.visuals.VisualsState;
import ir.layercloud.cortexdrone.manager.ActionRegistry;
import ir.layercloud.cortexdrone.manager.navigation.impl.NavigationManager;
import ir.layercloud.cortexdrone.manager.visuals.impl.VisualsManager;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.text.TextSanitizer;
import li.cil.oc.common.entity.Drone;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.EnumChatFormatting;

/**
 * Chat formatting for drone status and speech.
 *
 * <p>Extracted from {@link AIDroneManager}: presentation is not lifecycle, and
 * keeping the colour codes and layout here means the manager stops being the
 * place every UI string is edited.</p>
 */
public final class DroneStatusRenderer {
    /**
     * Longest status line a drone puts on its own display.
     */
    public static final int STATUS_DISPLAY_CHARS = 10;

    private DroneStatusRenderer() {
    }

    /**
     * Trims a status string to what fits on the drone's own display.
     */
    public static String trimStatus(String value) {
        String clean = TextSanitizer.chat(value);
        return clean.length() > STATUS_DISPLAY_CHARS
                ? clean.substring(0, STATUS_DISPLAY_CHARS) : clean;
    }

    /**
     * Formats one spoken line as a chat message.
     */
    public static String say(String droneName, String message) {
        String cleanName = TextSanitizer.chat(droneName);
        String name = cleanName.isEmpty() ? "AI" : cleanName;
        return EnumChatFormatting.AQUA + "[Drone:" + name + "] "
                + EnumChatFormatting.WHITE + message;
    }

    /**
     * Prints the full status block for one drone.
     */
    public static void render(ICommandSender sender, DroneController ctrl,
                              NavigationManager navigation, ActionRegistry actions,
                              VisualsManager visualsManager) {
        if (sender == null || ctrl == null || ctrl.drone == null) return;
        Drone drone = ctrl.drone;
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== AI Drone Status ["
                + EnumChatFormatting.AQUA + ctrl.name + EnumChatFormatting.GOLD + "] ===");
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.YELLOW + "ID: " + EnumChatFormatting.WHITE
                + "#" + ctrl.id
                + EnumChatFormatting.YELLOW + " | Owner: " + EnumChatFormatting.WHITE + ctrl.ownerName
                + EnumChatFormatting.YELLOW + " | Navigating: " + EnumChatFormatting.GREEN
                + navigation.isActive(ctrl)
                + EnumChatFormatting.YELLOW + " | Actions: " + EnumChatFormatting.GREEN
                + actions.activeContextCount(ctrl));
        CortexDroneMod.sendMsg(sender, String.format(
                EnumChatFormatting.YELLOW + "Pos: " + EnumChatFormatting.WHITE + "(%.1f, %.1f, %.1f)"
                        + EnumChatFormatting.YELLOW + " -> Target: " + EnumChatFormatting.WHITE + "(%.1f, %.1f, %.1f)"
                        + EnumChatFormatting.GRAY + " [Waypoints: %d/%d]",
                drone.posX, drone.posY, drone.posZ,
                (double) drone.targetX(), (double) drone.targetY(), (double) drone.targetZ(),
                navigation.state(ctrl).pathIndex,
                navigation.state(ctrl).pathWaypoints.size()));

        VisualsState visuals = visualsManager.state(ctrl);
        String aura = visuals.lightAura
                ? "ON (#" + String.format("%06X", visuals.lightColor & 0xFFFFFF)
                + ", r=" + visuals.lightRadius + ", level=" + visuals.lightIntensity + ")"
                : "OFF";
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.YELLOW + "LED: "
                + EnumChatFormatting.AQUA + String.format("#%06X", drone.lightColor() & 0xFFFFFF)
                + EnumChatFormatting.YELLOW + " | Light Aura: " + EnumChatFormatting.WHITE + aura
                + EnumChatFormatting.YELLOW + " | Energy: " + EnumChatFormatting.GREEN
                + drone.globalBuffer() + "/" + drone.globalBufferSize());
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.YELLOW + "Inventory (" + DroneController.INVENTORY_SLOTS + " slots): "
                + EnumChatFormatting.WHITE
                + InventoryService.formatInventorySummary(drone.mainInventory()));
    }
}
