package ir.layercloud.cortexdrone.command.impl;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.DebugEntry;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.harness.tool.ToolContext;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
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
     * {@code config [<key> <value>|reload|save|list|reset <key>]}; operator only.
     *
     * <p>Every field in {@code config.json} is editable: the key catalog is the
     * only list of settings, so the command cannot lag behind the file.</p>
     */
    static void config(ICommandSender sender, EntityPlayerMP player, AIDroneManager manager, String[] args) {
        if (!isOperator(player, sender)) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "[Drone] Only server operators can change AI config.");
            return;
        }
        ModConfig config = manager.getConfig();
        String action = args.length >= 2 ? args[1].toLowerCase() : "";
        if ("reload".equals(action)) {
            reload(sender, manager);
            return;
        }
        if ("save".equals(action)) {
            manager.saveConfig();
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GREEN
                    + "[Drone] Saved the live config to config/cortexdrone/config.json.");
            return;
        }
        if ("list".equals(action) || "keys".equals(action)) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "[Drone] Config keys: "
                    + EnumChatFormatting.WHITE + ConfigCatalog.keys());
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY
                    + "/drone config <key> <value> | <key> default | reload | save");
            return;
        }
        boolean reset = "reset".equals(action);
        String key = reset ? (args.length >= 3 ? args[2] : "") : action;
        String value = reset ? "" : (args.length >= 3 ? joinFrom(args, 2) : "");
        ConfigCatalog.Field field = ConfigCatalog.find(key);
        if (field == null) {
            if (args.length < 3) {
                printConfig(sender, config);
            } else {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                        + "[Drone] Unknown config key '" + args[1] + "'. Use /drone config list.");
            }
            return;
        }
        if (!reset && args.length < 3) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.YELLOW + "[Drone] " + field.key()
                    + " = " + EnumChatFormatting.WHITE + field.display(config));
            return;
        }
        if (reset || ConfigCatalog.isResetValue(value)) value = field.defaultValue();
        String previous = field.display(config);
        String applied;
        try {
            applied = field.apply(config, value);
        } catch (IllegalArgumentException invalid) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED + "[Drone] " + field.key()
                    + ": " + invalid.getMessage() + ".");
            return;
        }
        manager.saveConfig();
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GREEN + "[Drone] " + field.key()
                + ": " + EnumChatFormatting.WHITE + previous
                + EnumChatFormatting.GREEN + " -> " + EnumChatFormatting.WHITE + applied
                + EnumChatFormatting.GRAY + " " + field.bounds()
                + " (saved to config/cortexdrone/config.json)");
    }

    /**
     * Re-reads {@code config.json} and rebuilds the harness around it.
     */
    private static void reload(ICommandSender sender, AIDroneManager manager) {
        manager.reloadConfig();
        ModConfig config = manager.getConfig();
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GREEN
                + "[Drone] Reloaded config/cortexdrone/config.json"
                + EnumChatFormatting.GRAY + " (active task sessions were closed).");
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "[Drone] "
                + EnumChatFormatting.YELLOW + "url=" + EnumChatFormatting.WHITE + config.getApiUrl()
                + EnumChatFormatting.YELLOW + " model=" + EnumChatFormatting.WHITE + config.getModel()
                + EnumChatFormatting.YELLOW + " key=" + EnumChatFormatting.WHITE + mask(config.getApiKey()));
    }

    /**
     * Joins {@code args} from {@code start}, so a value containing spaces
     * (a model id with a suffix, a URL query) is not truncated.
     */
    private static String joinFrom(String[] args, int start) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (out.length() > 0) out.append(' ');
            out.append(args[i]);
        }
        return out.toString();
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
            final JsonObject result = manager.getJsEngine().execute(
                    new ToolContext(
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
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD
                + "=== CortexDrone config (config/cortexdrone/config.json) ===");
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.YELLOW + "Gateway: "
                + EnumChatFormatting.WHITE + "url=" + config.getApiUrl()
                + EnumChatFormatting.YELLOW + " model=" + EnumChatFormatting.WHITE + config.getModel()
                + EnumChatFormatting.YELLOW + " key=" + EnumChatFormatting.WHITE + mask(config.getApiKey()));
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.YELLOW + "Harness: "
                + EnumChatFormatting.WHITE + "maxTurns=" + config.getMaxTurns()
                + EnumChatFormatting.YELLOW + " taskDeadlineSeconds=" + EnumChatFormatting.WHITE
                + config.getTaskDeadlineSeconds()
                + EnumChatFormatting.YELLOW + " maxTokens=" + EnumChatFormatting.WHITE + config.getMaxTokens()
                + EnumChatFormatting.YELLOW + " temperature=" + EnumChatFormatting.WHITE
                + ConfigCatalog.trimNumber(config.getTemperature()));
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.YELLOW + "Context: "
                + EnumChatFormatting.WHITE + "executeTimeoutMs=" + config.getExecuteTimeoutMs()
                + EnumChatFormatting.YELLOW + " maxToolResultChars=" + EnumChatFormatting.WHITE
                + config.getMaxToolResultChars()
                + EnumChatFormatting.YELLOW + " maxContextChars=" + EnumChatFormatting.WHITE
                + config.getMaxContextChars());
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.YELLOW + "Sandbox: "
                + EnumChatFormatting.WHITE + "allowJavaReflection=" + config.isAllowJavaReflection()
                + EnumChatFormatting.YELLOW + " webSearchEnabled=" + EnumChatFormatting.WHITE
                + config.isWebSearchEnabled()
                + EnumChatFormatting.YELLOW + " droneSpeedMultiplier=" + EnumChatFormatting.WHITE
                + ConfigCatalog.trimNumber(config.getDroneSpeedMultiplier())
                + EnumChatFormatting.YELLOW + " savedDrones=" + EnumChatFormatting.WHITE
                + (config.getSavedDrones() == null ? 0 : config.getSavedDrones().size()));
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY
                + "/drone config <key> <value> | <key> default | reload | save | list");
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + "Keys: "
                + ConfigCatalog.keys());
    }

    /**
     * Shows only a recognisable prefix and suffix of a secret.
     */
    static String mask(String secret) {
        if (secret == null || secret.isEmpty()) return "<not set>";
        if (secret.length() <= 6) return "***";
        return secret.substring(0, 4) + "..." + secret.substring(secret.length() - 2);
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
