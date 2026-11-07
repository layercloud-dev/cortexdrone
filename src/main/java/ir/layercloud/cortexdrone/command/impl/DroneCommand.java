package ir.layercloud.cortexdrone.command.impl;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.command.BaseCommand;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.harness.lesson.Lesson;
import ir.layercloud.cortexdrone.harness.lesson.LessonStore;
import ir.layercloud.cortexdrone.harness.memory.MemoryEntry;
import ir.layercloud.cortexdrone.harness.memory.MemoryStore;
import ir.layercloud.cortexdrone.harness.session.SessionJournal;
import ir.layercloud.cortexdrone.harness.session.SessionOutcome;
import ir.layercloud.cortexdrone.config.DebugEntry;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumChatFormatting;

import java.io.File;
import java.util.List;
import java.util.Set;

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
        return "/" + this.cmdName + " <spawn|link|despawn|list|status|debug|config|memory|lesson|sessions|js|help>";
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
                    "spawn", "link", "despawn", "list", "status",
                    "debug", "config", "memory", "lesson", "sessions", "js", "help");
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("debug")) {
                return getListOfStringsMatchingLastWord(args, "on", "off", "tail", "clear");
            }
            if (sub.equals("config")) {
                return getListOfStringsMatchingLastWord(args, "url", "model", "key");
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
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone spawn [name] | link | despawn [name] | list | status");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone debug [on|off|tail|clear] | config");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone memory [list|search <q>|remember <text>|here <name>|forget <id|name>]");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone lesson [list|search <q>|forget <id|title>]");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone sessions [list [success|failure|...] [n]|stats|show <id>]");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "@drone <message>" + EnumChatFormatting.GRAY + " - One task/session; questions, notes, modifications and cancellations are handled by intent");
            return;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "debug":
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
            case "list":
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
                            c.id, c.name, c.ownerName,
                            this.manager.getNavigationManager().isActive(c) ? "navigating" : "idle",
                            c.drone.posX, c.drone.posY, c.drone.posZ,
                            InventoryService.countInventoryItems(c.drone.mainInventory())
                    ));
                }
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
            case "config":
                boolean isOp = player == null || player.canCommandSenderUseCommand(2, "drone");
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
                    CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY
                            + "Usage: /drone config <url|model|key> <value>");
                    return;
                }
                String key = args[1].toLowerCase();
                String val = args[2];
                switch (key) {
                    case "url":
                        config.setApiUrl(val);
                        break;
                    case "model":
                        config.setModel(val);
                        break;
                    case "key":
                        config.setApiKey(val.equalsIgnoreCase("clear") ? "" : val);
                        break;
                    default:
                        CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED + "Usage: /drone config <url|model|key> <value>");
                        return;
                }
                this.manager.saveConfig();
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.GREEN + "[Drone] Updated AI config '" + key + "' and saved to config/cortexdrone/config.json.");
                return;
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

        if (sub.equals("js")) {
            final String code = args.length >= 2 ? joinArgs(args, 1) : "return await drone.state();";
            final DroneController fCtrl = ctrl;
            final EntityPlayerMP fPlayer = player;
            CortexDroneMod.AGENT_POOL.submit(() -> {
                final JsonObject res = manager.getJsEngine().execute(
                        new ir.layercloud.cortexdrone.harness.tool.ToolContext(
                                manager.getHarness(), fCtrl, fPlayer, 0), code,
                        manager.getConfig().getExecuteTimeoutMs());
                manager.getMod().queueMain(() -> manager.droneSay(fPlayer, fCtrl, "JS Result: " + res.toString()));
            });
            return;
        }

        String fullPrompt = joinArgs(args, 0);
        this.manager.handleNaturalInstruction(player, fullPrompt);
    }

    private void handleSessions(ICommandSender sender, String[] args) {
        SessionJournal journal = this.manager.getHarness() != null
                ? this.manager.getHarness().journal() : null;
        if (journal == null) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "[Drone] Session journal is not initialized yet.");
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase() : "list";

        if (action.equals("stats")) {
            java.util.Map<String, Integer> counts = journal.counts();
            int total = 0;
            for (Integer value : counts.values()) total += value;
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Session Journal ("
                    + total + " recorded) ===");
            for (java.util.Map.Entry<String, Integer> entry : counts.entrySet()) {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + entry.getKey()
                        + EnumChatFormatting.GRAY + ": " + EnumChatFormatting.WHITE + entry.getValue());
            }
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + "Folder: "
                    + journal.sessionsRoot().getAbsolutePath());
            return;
        }

        if (action.equals("show")) {
            String key = args.length >= 3 ? joinArgs(args, 2) : "";
            JsonObject entry = journal.find(key);
            if (entry == null) {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                        + "[Drone] No session matched '" + key + "'. Use /drone sessions list.");
                return;
            }
            File file = journal.fileFor(entry);
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Session "
                    + jsonString(entry, "id") + " ===");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "outcome: "
                    + EnumChatFormatting.WHITE + jsonString(entry, "outcome")
                    + EnumChatFormatting.AQUA + "  topic: " + EnumChatFormatting.WHITE
                    + jsonString(entry, "topic")
                    + EnumChatFormatting.AQUA + "  reason: " + EnumChatFormatting.WHITE
                    + jsonString(entry, "reason"));
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "drone: "
                    + EnumChatFormatting.WHITE + jsonString(entry, "drone")
                    + EnumChatFormatting.AQUA + "  owner: " + EnumChatFormatting.WHITE
                    + jsonString(entry, "owner")
                    + EnumChatFormatting.AQUA + "  player: " + EnumChatFormatting.WHITE
                    + jsonString(entry, "player"));
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "turns/tools/errors: "
                    + EnumChatFormatting.WHITE + jsonInt(entry, "turns") + "/"
                    + jsonInt(entry, "tool_calls") + "/" + jsonInt(entry, "tool_errors")
                    + EnumChatFormatting.AQUA + "  world actions: "
                    + EnumChatFormatting.WHITE + jsonInt(entry, "world_actions"));
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "prompt: "
                    + EnumChatFormatting.WHITE + jsonString(entry, "prompt"));
            JsonObject full = readSessionFile(file);
            if (full != null && full.has("final_reply")) {
                String reply = jsonString(full, "final_reply");
                if (!reply.isEmpty()) {
                    CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "final: "
                            + EnumChatFormatting.WHITE + capChat(reply, 300));
                }
            }
            if (file != null) {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + "file: "
                        + file.getAbsolutePath());
            }
            return;
        }

        SessionOutcome filter = SessionOutcome.fromId(action);
        int limit = 10;
        if (filter != null) {
            if (args.length >= 3) limit = parseInt(args[2], 10);
        } else if (action.equals("list")) {
            if (args.length >= 3) {
                SessionOutcome maybe = SessionOutcome.fromId(args[2]);
                if (maybe != null) {
                    filter = maybe;
                    if (args.length >= 4) limit = parseInt(args[3], 10);
                } else {
                    limit = parseInt(args[2], 10);
                }
            }
        } else {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "Usage: /drone sessions [list [success|failure|...] [n]|stats|show <id>]");
            return;
        }

        java.util.List<JsonObject> entries = journal.recent(limit, filter);
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Sessions ("
                + (filter != null ? filter.id() : "all") + ", last " + entries.size() + ") ===");
        if (entries.isEmpty()) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY
                    + "Nothing recorded yet. Sessions are written after each task.");
            return;
        }
        long now = System.currentTimeMillis();
        for (JsonObject entry : entries) {
            long at = entry.has("at_ms") ? entry.get("at_ms").getAsLong() : 0L;
            String prompt = jsonString(entry, "prompt");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "["
                    + jsonString(entry, "outcome") + "/" + jsonString(entry, "topic") + "] "
                    + EnumChatFormatting.GRAY + ago(now, at) + " "
                    + EnumChatFormatting.WHITE + jsonString(entry, "drone")
                    + EnumChatFormatting.GRAY + " " + jsonInt(entry, "turns") + "t/"
                    + jsonInt(entry, "tool_calls") + "c/" + jsonInt(entry, "tool_errors") + "e \""
                    + capChat(prompt, 90) + "\""
                    + EnumChatFormatting.DARK_GRAY + " id=" + jsonString(entry, "id"));
        }
    }

    private static JsonObject readSessionFile(File file) {
        if (file == null || !file.isFile()) return null;
        try (java.io.Reader reader = new java.io.FileReader(file)) {
            com.google.gson.JsonElement parsed = new com.google.gson.JsonParser().parse(reader);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String jsonString(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : "";
    }

    private static int jsonInt(JsonObject object, String key) {
        return object != null && object.has(key) ? object.get(key).getAsInt() : 0;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String ago(long now, long then) {
        if (then <= 0) return "?";
        long seconds = Math.max(0L, (now - then) / 1000L);
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    private static String capChat(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ')
                .replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, Math.max(0, max - 3)) + "...";
    }

    private void handleLesson(ICommandSender sender, String[] args) {
        LessonStore lessons = this.manager.getHarness() != null
                ? this.manager.getHarness().lessons() : null;
        if (lessons == null) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED + "[Drone] Lessons are not initialized yet.");
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase() : "list";
        if (action.equals("forget")) {
            String key = args.length >= 3 ? joinArgs(args, 2) : "";
            boolean removed = lessons.forget(key);
            CortexDroneMod.sendMsg(sender, removed
                    ? EnumChatFormatting.GREEN + "[Drone] Forgot lesson '" + key + "'."
                    : EnumChatFormatting.RED + "[Drone] No lesson matched '" + key + "'.");
            return;
        }
        if (!action.equals("list") && !action.equals("search")) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "Usage: /drone lesson [list|search <q>|forget <id|title>]");
            return;
        }
        String query = action.equals("search") && args.length >= 3 ? joinArgs(args, 2) : "";
        java.util.List<Lesson> entries = lessons.search(query, null, 12);
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Drone Lessons ("
                + lessons.count() + " stored) ===");
        if (entries.isEmpty()) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + "Nothing stored yet.");
            return;
        }
        for (Lesson entry : entries) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "[" + entry.getScope() + "/"
                    + entry.getOutcome() + "] " + EnumChatFormatting.WHITE + entry.getTitle()
                    + EnumChatFormatting.GRAY + " - " + entry.summary(160));
        }
    }

    private void handleMemory(ICommandSender sender, EntityPlayerMP player, String[] args) {
        MemoryStore memory = this.manager.getHarness() != null
                ? this.manager.getHarness().memory() : null;
        if (memory == null) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED + "[Drone] Memory is not initialized yet.");
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase() : "list";
        if (action.equals("list")) {
            java.util.List<MemoryEntry> entries = memory.list(null, 12);
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Drone Memory ("
                    + memory.count() + " stored) ===");
            if (entries.isEmpty()) {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + "Nothing stored yet.");
                return;
            }
            for (MemoryEntry entry : entries) {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "[" + entry.getKind() + "] "
                        + EnumChatFormatting.WHITE + entry.getName()
                        + (entry.isHasPosition() ? EnumChatFormatting.GRAY + " " + entry.positionSummary() : "")
                        + EnumChatFormatting.GRAY + " - " + entry.getText());
            }
            return;
        }
        if (action.equals("search")) {
            String query = args.length >= 3 ? joinArgs(args, 2) : "";
            java.util.List<MemoryEntry> entries = memory.search(query, null, 12);
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Memory search: "
                    + (query.isEmpty() ? "(all)" : query) + " ===");
            for (MemoryEntry entry : entries) {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "[" + entry.getKind() + "] "
                        + EnumChatFormatting.WHITE + entry.getName()
                        + (entry.isHasPosition() ? EnumChatFormatting.GRAY + " " + entry.positionSummary() : "")
                        + EnumChatFormatting.GRAY + " - " + entry.getText());
            }
            if (entries.isEmpty()) CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + "No matches.");
            return;
        }
        if (action.equals("forget")) {
            String key = args.length >= 3 ? joinArgs(args, 2) : "";
            boolean removed = memory.forget(key);
            CortexDroneMod.sendMsg(sender, removed
                    ? EnumChatFormatting.GREEN + "[Drone] Forgot '" + key + "'."
                    : EnumChatFormatting.RED + "[Drone] No memory matched '" + key + "'.");
            return;
        }
        if (action.equals("here") || action.equals("remember")) {
            if (player == null) {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                        + "[Drone] Use memory here/remember in-game so the position is known.");
                return;
            }
            String text = args.length >= 3 ? joinArgs(args, 2) : "";
            if (text.isEmpty()) {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                        + "[Drone] Usage: /drone memory " + action + " <text|name>");
                return;
            }
            MemoryEntry entry;
            if (action.equals("here")) {
                entry = memory.remember("location", text, "Location set by " + player.getCommandSenderName(),
                        true, player.posX, player.posY, player.posZ, player.dimension,
                        java.util.Collections.singletonList("player"), true,
                        "player:" + player.getCommandSenderName());
            } else {
                entry = memory.captureFromChat(player.getCommandSenderName(), player.dimension,
                        player.posX, player.posY, player.posZ, text);
            }
            if (entry == null) {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED + "[Drone] Could not store that memory.");
                return;
            }
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GREEN + "[Drone] Remembered "
                    + entry.getKind() + " '" + entry.getName() + "'"
                    + (entry.isHasPosition() ? " at " + entry.positionSummary() : "") + ".");
            return;
        }
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                + "Usage: /drone memory [list|search <q>|remember <text>|here <name>|forget <id|name>]");
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
