package ir.layercloud.cortexdrone.command.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.DebugEntry;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.navigation.DroneSpeed;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumChatFormatting;

import java.util.List;
import java.util.Set;

/**
 * {@code /drone debug}, {@code /drone config}, {@code /drone list} and
 * {@code /drone js}.
 *
 * <p>Extracted from {@link DroneCommand}: these four subcommands are about the
 * server/drone process rather than the agent, and two of them cross a trust
 * boundary. Keeping them together makes the permission checks easy to audit.</p>
 */
@SuppressWarnings("unused")
final class AdminCommands {
    /**
     * Console debug lines shown by {@code debug tail}.
     */
    private static final int DEBUG_TAIL = 10;

    private AdminCommands() {
    }

    /**
     * {@code debug [on|off|toggle|tail|clear]}
     */
    static void debug(ICommandSender sender, EntityPlayerMP player, DroneLogger logger, String[] args) {
        String mode = args.length >= 2 ? args[1].toLowerCase() : "toggle";
        if ("tail".equals(mode)) {
            List<DebugEntry> tail = logger.getRecentDebugEntries(DEBUG_TAIL);
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD
                    + "=== Drone Debug Log (last " + tail.size() + ") ===");
            for (DebugEntry entry : tail) {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + entry.toLogLine());
            }
            return;
        }
        if ("clear".equals(mode)) {
            logger.clearDebugLog();
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GREEN
                    + "[DroneDebug] Cleared debug log buffer and file.");
            return;
        }
        if (player == null) {
            CortexDroneMod.sendMsg(sender, "Use /drone debug tail or /drone debug clear from console.");
            return;
        }
        String name = player.getCommandSenderName();
        Set<String> subscribers = logger.getSubscribers();
        boolean enable = "on".equals(mode) || ("toggle".equals(mode) && !subscribers.contains(name));
        if (enable) {
            subscribers.add(name);
            CortexDroneMod.sendMsg(player, EnumChatFormatting.GREEN
                    + "[DroneDebug] Live debug stream ENABLED for your chat. Use '/drone debug off' to stop.");
        } else {
            subscribers.remove(name);
            CortexDroneMod.sendMsg(player, EnumChatFormatting.YELLOW
                    + "[DroneDebug] Live debug stream DISABLED.");
        }
    }

    /**
     * {@code list}
     */
    static void list(ICommandSender sender, AIDroneManager manager) {
        if (manager.controllerCount() == 0) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.YELLOW + "[Drone] No active AI drones.");
            return;
        }
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD
                + "=== Active AI Drones (" + manager.controllerCount() + ") ===");
        for (DroneController controller : manager.controllers()) {
            if (controller.drone == null || controller.drone.isDead) continue;
            CortexDroneMod.sendMsg(sender, String.format(
                    EnumChatFormatting.AQUA + "#%d %s" + EnumChatFormatting.GRAY + " (Owner: %s) "
                            + EnumChatFormatting.YELLOW + "[%s] " + EnumChatFormatting.WHITE
                            + "@ (%.1f, %.1f, %.1f) " + EnumChatFormatting.GREEN + "Items: %d",
                    controller.id, controller.name, controller.ownerName,
                    manager.getNavigationManager().isActive(controller) ? "navigating" : "idle",
                    controller.drone.posX, controller.drone.posY, controller.drone.posZ,
                    InventoryService.countInventoryItems(controller.drone.mainInventory())));
        }
    }

    /**
     * {@code config [<url|model|key|speed> <value>]}; operator only.
     */
    static void config(ICommandSender sender, EntityPlayerMP player, AIDroneManager manager, String[] args) {
        if (!isOperator(player, sender)) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "[Drone] Only server operators can change AI config.");
            return;
        }
        ModConfig config = manager.getConfig();
        if (args.length < 3) {
            printConfig(sender, config);
            return;
        }
        String key = args[1].toLowerCase();
        String value = args[2];
        switch (key) {
            case "url":
                config.setApiUrl(value);
                break;
            case "model":
                config.setModel(value);
                break;
            case "key":
                config.setApiKey("clear".equalsIgnoreCase(value) ? "" : value);
                break;
            case "speed":
                Double multiplier = parseMultiplier(value);
                if (multiplier == null) {
                    CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                            + "[Drone] Speed must be a number such as 1, 2.5 or 5.");
                    return;
                }
                config.setDroneSpeedMultiplier(DroneSpeed.clampMultiplier(multiplier));
                break;
            default:
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                        + "Usage: /drone config <url|model|key|speed> <value>");
                return;
        }
        manager.saveConfig();
        String applied = "speed".equals(key)
                ? config.getDroneSpeedMultiplier() + "x" : "saved";
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GREEN + "[Drone] Updated AI config '" + key
                + "' (" + applied + ") and saved to config/cortexdrone/config.json.");
    }

    /**
     * {@code js <code>} — runs arbitrary Rhino with Java reflection inside the
     * server JVM, so it is operator-only.
     */
    static void js(EntityPlayerMP player, AIDroneManager manager, DroneController ctrl, String code) {
        if (!isOperator(player, player)) {
            CortexDroneMod.sendMsg(player, EnumChatFormatting.RED
                    + "[Drone] Only server operators can run /drone js.");
            return;
        }
        final DroneController controller = ctrl;
        final EntityPlayerMP sender = player;
        final String script = code;
        CortexDroneMod.AGENT_POOL.submit(() -> {
            final com.google.gson.JsonObject result = manager.getJsEngine().execute(
                    new ir.layercloud.cortexdrone.harness.tool.ToolContext(
                            manager.getHarness(), controller, sender, 0),
                    script, manager.getConfig().getExecuteTimeoutMs());
            manager.getMod().queueMain(
                    () -> manager.droneSay(sender, controller, "JS Result: " + result));
        });
    }

    /**
     * True when the sender may change server-level settings.
     */
    static boolean isOperator(EntityPlayerMP player, ICommandSender sender) {
        return player == null || CommandPermissions.isOperator(playerLevel(player));
    }

    private static void printConfig(ICommandSender sender, ModConfig config) {
        String masked = mask(config.getApiKey());
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "[Drone AI Config] "
                + EnumChatFormatting.YELLOW + "URL: " + EnumChatFormatting.WHITE + config.getApiUrl()
                + EnumChatFormatting.YELLOW + " | Model: " + EnumChatFormatting.WHITE + config.getModel()
                + EnumChatFormatting.YELLOW + " | Key: " + EnumChatFormatting.WHITE + masked);
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "[Drone] Speed: "
                + EnumChatFormatting.WHITE + config.getDroneSpeedMultiplier() + "x"
                + EnumChatFormatting.GRAY + " (1 = normal, max "
                + DroneSpeed.MAX_MULTIPLIER + "x)");
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY
                + "Usage: /drone config <url|model|key|speed> <value>");
    }

    /**
     * Shows only a recognisable prefix and suffix of a secret.
     */
    static String mask(String secret) {
        if (secret == null || secret.isEmpty()) return "<not set>";
        if (secret.length() <= 6) return "***";
        return secret.substring(0, 4) + "..." + secret.substring(secret.length() - 2);
    }

    private static Double parseMultiplier(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /**
     * Vanilla operator level for the executing player.
     */
    static int playerLevel(EntityPlayerMP player) {
        if (player == null) return 0;
        try {
            if (player.mcServer == null || player.mcServer.getConfigurationManager() == null) return 0;
            return player.mcServer.getConfigurationManager()
                    .func_152596_g(player.getGameProfile())
                    ? CommandPermissions.OP_LEVEL : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
