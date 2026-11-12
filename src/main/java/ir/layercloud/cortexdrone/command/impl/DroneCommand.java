package ir.layercloud.cortexdrone.command.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.command.BaseCommand;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.config.ModConfig;
import lombok.RequiredArgsConstructor;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@SuppressWarnings({"unused", "SameReturnValue"})
public class DroneCommand extends BaseCommand {
    private final String cmdName;
    private final AIDroneManager manager;

    @Override
    public String getCommandName() {
        return this.cmdName;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/" + this.cmdName + " <spawn|link|despawn|list|status|debug|config|memory|lesson|sessions|skills|js|help>";
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
                    "spawn", "link", "despawn", "stop", "list", "status",
                    "debug", "config", "memory", "lesson", "sessions", "skills", "js", "help");
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("debug")) {
                return getListOfStringsMatchingLastWord(args, "on", "off", "tail", "clear");
            }
            if (sub.equals("config")) {
                List<String> options = new ArrayList<>(ConfigCatalog.chatKeys());
                options.add("list");
                options.add("reload");
                options.add("save");
                return getListOfStringsMatchingLastWord(args,
                        options.toArray(new String[0]));
            }
            if (sub.equals("memory")) {
                return getListOfStringsMatchingLastWord(args,
                        "list", "search", "remember", "here", "forget");
            }
            if (sub.equals("lesson")) {
                return getListOfStringsMatchingLastWord(args, "list", "search", "forget");
            }
            if (sub.equals("sessions")) {
                return getListOfStringsMatchingLastWord(args,
                        "list", "stats", "show", "success", "failure", "cancelled",
                        "interrupted", "incomplete", "error");
            }
            if (sub.equals("skills")) {
                return getListOfStringsMatchingLastWord(args, "list", "search", "show");
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("config")) {
            ConfigCatalog.Field field = ConfigCatalog.find(args[1]);
            if (field != null) {
                if (field.kind() == ConfigCatalog.Kind.BOOLEAN) {
                    return getListOfStringsMatchingLastWord(args, "true", "false");
                }
                if (field.kind() == ConfigCatalog.Kind.DRONE_LIST) {
                    return getListOfStringsMatchingLastWord(args, "clear");
                }
                if (field.kind() == ConfigCatalog.Kind.TEXT
                        || field.kind() == ConfigCatalog.Kind.SECRET) {
                    return null;
                }
            }
            return getListOfStringsMatchingLastWord(args, "default");
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        CortexDroneMod mod = this.manager.getMod();
        DroneLogger logger = this.manager.getLogger();
        ModConfig config = this.manager.getConfig();
        EntityPlayerMP player = (sender instanceof EntityPlayerMP) ? (EntityPlayerMP) sender : null;

        if (player != null && !mod.isAuthenticated(player)) {
            CortexDroneMod.sendMsg(player, EnumChatFormatting.RED + "[Auth] Please login first!");
            return;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== CortexDrone v"
                    + CortexDroneMod.VERSION + " (Tool Harness) Commands ===");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone spawn [name] | link | despawn [name] | stop | list | status");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone debug [on|off|tail|clear]");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone config [<key> <value>|reload|save|list]");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone memory [list|search <q>|remember <text>|here <name>|forget <id|name>]");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone lesson [list|search <q>|forget <id|title>]");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone sessions [list [success|failure|...] [n]|stats|show <id>]");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone skills [list [n]|search <q>|show <name>]");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "@drone <message>" + EnumChatFormatting.GRAY
                    + " - the drone's AI decides: task, status, note, change or cancel");
            return;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "debug":
                AdminCommands.debug(sender, player, logger, args);
                return;
            case "list":
                AdminCommands.list(sender, this.manager);
                return;
            case "memory":
                handleMemory(sender, player, args);
                return;
            case "lesson":
                handleLesson(sender, args);
                return;
            case "sessions":
                handleSessions(sender, args);
                return;
            case "skills":
                handleSkills(sender, args);
                return;
            case "stop":
            case "cancel": {
                DroneController ctrl = player != null
                        ? this.manager.getPlayerDrone(player.getCommandSenderName()) : null;
                if (ctrl == null) {
                    CortexDroneMod.sendMsg(sender, "No active drone task to stop.");
                    return;
                }
                this.manager.getTaskCoordinator().stop(ctrl);
                CortexDroneMod.sendMsg(sender, "Task stopped. Hovering in place.");
                return;
            }
            case "config":
                AdminCommands.config(sender, player, this.manager, args);
                return;
            default:
                break;
        }

        if (player == null) {
            CortexDroneMod.sendMsg(sender, "Use /drone list, /drone memory list, /drone debug tail, or /drone config from console.");
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
            String nameArg = args.length >= 2 ? args[1] : null;
            int level = playerLevel(player);
            DroneController target = DespawnTargets.select(player.getCommandSenderName(), level,
                    nameArg, ctrl, this.manager.controllers());
            if (target == null) {
                if (nameArg != null && !nameArg.trim().isEmpty()) {
                    CortexDroneMod.sendMsg(player, EnumChatFormatting.RED
                            + "[Drone] No drone named '" + nameArg + "' that you own.");
                } else {
                    CortexDroneMod.sendMsg(player, EnumChatFormatting.RED
                            + "[Drone] You don't have an active AI drone.");
                }
                return;
            }
            ctrl = target;
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

        if (sub.equals("js")) {
            AdminCommands.js(player, this.manager, ctrl,
                    args.length >= 2 ? joinArgs(args, 1) : "return await drone.state();");
            return;
        }

        String fullPrompt = joinArgs(args, 0);
        this.manager.handleNaturalInstruction(player, fullPrompt);
    }

    /**
     * @see AdminCommands#playerLevel(EntityPlayerMP)
     */
    private static int playerLevel(EntityPlayerMP player) {
        return AdminCommands.playerLevel(player);
    }

    private void handleSkills(ICommandSender sender, String[] args) {
        SkillCommands.handle(sender, this.manager.getHarness() != null
                ? this.manager.getHarness().skills() : null, args);
    }

    private void handleSessions(ICommandSender sender, String[] args) {
        SessionCommands.handle(sender, this.manager.getHarness() != null
                ? this.manager.getHarness().journal() : null, args);
    }

    private void handleLesson(ICommandSender sender, String[] args) {
        LessonCommands.handle(sender, this.manager.getHarness() != null
                ? this.manager.getHarness().lessons() : null, args);
    }

    private void handleMemory(ICommandSender sender, EntityPlayerMP player, String[] args) {
        MemoryCommands.handle(sender, this.manager.getHarness() != null
                ? this.manager.getHarness().memory() : null, player, args);
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
