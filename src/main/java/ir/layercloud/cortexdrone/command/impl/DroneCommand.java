package ir.layercloud.cortexdrone.command.impl;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.command.BaseCommand;
import ir.layercloud.cortexdrone.config.AIConfig;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DebugEntry;
import ir.layercloud.cortexdrone.model.DroneController;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumChatFormatting;

@RequiredArgsConstructor
public class DroneCommand extends BaseCommand {
    private final String cmdName;
    private final AIDroneManager manager;

    @Override
    public String getCommandName() {
        return this.cmdName;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/" + this.cmdName + " <spawn|link|despawn|list|status|setbase|debug|config|js|help>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args,
                "spawn", "link", "despawn", "list", "status", "setbase",
                "debug", "config", "js", "help");
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("debug")) {
                return getListOfStringsMatchingLastWord(args, "on", "off", "tail", "clear");
            }
            if (sub.equals("config")) {
                return getListOfStringsMatchingLastWord(args, "url", "model", "key");
            }
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        CortexDroneMod mod = this.manager.getMod();
        DroneLogger logger = this.manager.getLogger();
        AIConfig config = this.manager.getConfig();
        EntityPlayerMP player = (sender instanceof EntityPlayerMP) ? (EntityPlayerMP) sender : null;

        if (player != null && !mod.isAuthenticated(player)) {
            CortexDroneMod.sendMsg(player, EnumChatFormatting.RED + "[Auth] Please login first!");
            return;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== CortexDrone v1.4.0 (Master-Tool Harness) Commands ===");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone spawn [name] | link | despawn [name] | list | status");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone setbase | js <code>");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone debug [on|off|tail|clear] | config");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "@drone <message>" + EnumChatFormatting.GRAY + " - One task/session; questions, notes, modifications and cancellations are handled by intent");
            return;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("debug")) {
            String mode = args.length >= 2 ? args[1].toLowerCase() : "toggle";
            if (mode.equals("tail")) {
                List<DebugEntry> tail = logger.getRecentDebugEntries(10);
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Drone Debug Log (last " + tail.size() + ") ===");
                for (DebugEntry e : tail) {
                    CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + e.toLogLine());
                }
                return;
            }
            if (mode.equals("clear")) {
                logger.clearDebugLog();
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.GREEN + "[DroneDebug] Cleared debug log buffer and file.");
                return;
            }
            if (player != null) {
                String pName = player.getCommandSenderName();
                Set<String> subs = logger.getSubscribers();
                boolean enable = mode.equals("on") || (mode.equals("toggle") && !subs.contains(pName));
                if (enable) {
                    subs.add(pName);
                    CortexDroneMod.sendMsg(player, EnumChatFormatting.GREEN + "[DroneDebug] Live debug stream ENABLED for your chat. Use '/drone debug off' to stop.");
                } else {
                    subs.remove(pName);
                    CortexDroneMod.sendMsg(player, EnumChatFormatting.YELLOW + "[DroneDebug] Live debug stream DISABLED.");
                }
            } else {
                CortexDroneMod.sendMsg(sender, "Use /drone debug tail or /drone debug clear from console.");
            }
            return;
        }

        if (sub.equals("list")) {
            if (this.manager.getControllersById().isEmpty()) {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.YELLOW + "[Drone] No active AI drones.");
                return;
            }
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Active AI Drones (" + this.manager.getControllersById().size() + ") ===");
            for (DroneController c : this.manager.getControllersById().values()) {
                if (c.drone == null || c.drone.isDead) continue;
                CortexDroneMod.sendMsg(sender, String.format(
                    EnumChatFormatting.AQUA + "#%d %s" + EnumChatFormatting.GRAY + " (Owner: %s) "
                        + EnumChatFormatting.YELLOW + "[%s] " + EnumChatFormatting.WHITE + "@ (%.1f, %.1f, %.1f) "
                        + EnumChatFormatting.GREEN + "Items: %d",
                    c.id, c.name, c.ownerName, c.mode.name(),
                    c.drone.posX, c.drone.posY, c.drone.posZ,
                    InventoryService.countInventoryItems(c.drone.mainInventory())
                ));
            }
            return;
        }

        if (sub.equals("config")) {
            boolean isOp = !(sender instanceof EntityPlayerMP) || sender.canCommandSenderUseCommand(2, "drone");
            if (!isOp) {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED + "[Drone] Only server operators can change AI config.");
                return;
            }
            if (args.length < 3) {
                String maskedKey = (config.getApiKey() != null && config.getApiKey().length() > 6)
                    ? (config.getApiKey().substring(0, 4) + "..." + config.getApiKey().substring(config.getApiKey().length() - 2))
                    : (config.getApiKey() == null || config.getApiKey().isEmpty() ? "<not set>" : "***");
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "[Drone AI Config] "
                    + EnumChatFormatting.YELLOW + "URL: " + EnumChatFormatting.WHITE + config.getApiUrl()
                    + EnumChatFormatting.YELLOW + " | Model: " + EnumChatFormatting.WHITE + config.getModel()
                    + EnumChatFormatting.YELLOW + " | Key: " + EnumChatFormatting.WHITE + maskedKey);
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + "Usage: /drone config <url|model|key> <value>");
                return;
            }
            String key = args[1].toLowerCase();
            String val = args[2];
            if (key.equals("url")) {
                config.setApiUrl(val);
            } else if (key.equals("model")) {
                config.setModel(val);
            } else if (key.equals("key")) {
                config.setApiKey(val.equalsIgnoreCase("clear") ? "" : val);
            } else {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED + "Usage: /drone config <url|model|key> <value>");
                return;
            }
            this.manager.saveConfig();
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GREEN + "[Drone] Updated AI config '" + key + "' and saved to world/layercloud-ai.json.");
            return;
        }

        if (player == null) {
            CortexDroneMod.sendMsg(sender, "Use /drone list, /drone debug tail, or /drone config from console, or HTTP API on 127.0.0.1:25580.");
            return;
        }

        if (sub.equals("spawn")) {
            String customName = args.length >= 2 ? joinArgs(args, 1) : null;
            DroneController ctrl = this.manager.spawnDroneForPlayer(player, customName);
            this.manager.droneSay(player, ctrl, "Online and ready! Hovering nearby — look at me anytime to open my 8-slot inventory.");
            return;
        }

        if (sub.equals("link")) {
            DroneController ctrl = this.manager.linkNearestDrone(player);
            if (ctrl == null) {
                CortexDroneMod.sendMsg(player, EnumChatFormatting.RED + "[Drone] No OpenComputers Drone found within 8 blocks.");
            } else {
                this.manager.droneSay(player, ctrl, "Linked to AI Controller! Ready for commands.");
            }
            return;
        }

        DroneController ctrl = this.manager.getPlayerDrone(player.getCommandSenderName());
        if (sub.equals("despawn")) {
            if (args.length >= 2) {
                String nameArg = args[1];
                for (DroneController c : this.manager.getControllersById().values()) {
                    if (c.name.equalsIgnoreCase(nameArg) || ("#" + c.id).equals(nameArg) || String.valueOf(c.id).equals(nameArg)) {
                        ctrl = c;
                        break;
                    }
                }
            }
            if (ctrl == null) {
                CortexDroneMod.sendMsg(player, EnumChatFormatting.RED + "[Drone] You don't have an active AI drone.");
                return;
            }
            String oldName = ctrl.name;
            int dropped = this.manager.despawnDrone(ctrl, player);
            CortexDroneMod.sendMsg(player, EnumChatFormatting.GREEN + "[Drone] Despawned '" + oldName + "' and dropped " + dropped + " item(s) at your feet.");
            return;
        }

        if (ctrl == null) {
            ctrl = this.manager.linkNearestDrone(player);
            if (ctrl == null) {
                ctrl = this.manager.spawnDroneForPlayer(player, null);
                this.manager.droneSay(player, ctrl, "Auto-spawned your AI Drone!");
            }
        }

        if (sub.equals("status")) {
            this.manager.sendDroneStatus(player, ctrl);
            return;
        }

        if (sub.equals("setbase")) {
            double bx = player.posX;
            double by = player.posY;
            double bz = player.posZ;
            if (args.length >= 4) {
                try {
                    bx = parseCoord(bx, args[1]);
                    by = parseCoord(by, args[2]);
                    bz = parseCoord(bz, args[3]);
                } catch (NumberFormatException ignored) {
                }
            }
            config.setPlayerBase(player.getCommandSenderName(), bx, by, bz);
            this.manager.saveConfig();
            this.manager.droneSay(player, ctrl, String.format("Saved your base location at (%.1f, %.1f, %.1f)!", bx, by, bz));
            return;
        }

        if (sub.equals("js")) {
            final String code = args.length >= 2 ? joinArgs(args, 1) : "return await drone.state();";
            final DroneController fCtrl = ctrl;
            final EntityPlayerMP fPlayer = player;
            CortexDroneMod.HTTP_POOL.submit(new Runnable() {
                @Override
                public void run() {
                    final JsonObject res = manager.getCodeModeRuntime().executeJavaScript(fCtrl, fPlayer, code);
                    manager.getMod().queueMain(new Runnable() {
                        @Override
                        public void run() {
                            manager.droneSay(fPlayer, fCtrl, "JS Result: " + res.toString());
                        }
                    });
                }
            });
            return;
        }

        String fullPrompt = joinArgs(args, 0);
        this.manager.handleNaturalInstruction(player, fullPrompt);
    }

    private static double parseCoord(double base, String raw) {
        if (raw.startsWith("~")) {
            if (raw.length() == 1) return base;
            return base + Double.parseDouble(raw.substring(1));
        }
        return Double.parseDouble(raw);
    }

    private static String joinArgs(String[] args, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }
}
