package ir.layercloud.cortexdrone.command.impl;

import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.harness.memory.MemoryEntry;
import ir.layercloud.cortexdrone.harness.memory.MemoryStore;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumChatFormatting;

import java.util.Collections;
import java.util.List;

/**
 * {@code /drone memory} — list, search, remember and forget durable facts.
 *
 * <p>Extracted from {@link DroneCommand}. The store owns persistence; this class
 * only translates chat arguments into store calls and prints the result.</p>
 */
public final class MemoryCommands {
    /**
     * Maximum entries shown per query.
     */
    private static final int PAGE_SIZE = 12;

    private MemoryCommands() {
    }

    public static void handle(ICommandSender sender, MemoryStore memory,
                              EntityPlayerMP player, String[] args) {
        if (memory == null) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "[Drone] Memory is not initialized yet.");
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase() : "list";
        switch (action) {
            case "list":
                list(sender, memory);
                return;
            case "search":
                search(sender, memory, args);
                return;
            case "forget":
                forget(sender, memory, args);
                return;
            case "here":
            case "remember":
                remember(sender, memory, player, action, args);
                return;
            default:
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                        + "Usage: /drone memory [list|search <q>|remember <text>|here <name>|forget <id|name>]");
        }
    }

    private static void list(ICommandSender sender, MemoryStore memory) {
        List<MemoryEntry> entries = memory.list(null, PAGE_SIZE);
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Drone Memory ("
                + memory.count() + " stored) ===");
        if (entries.isEmpty()) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + "Nothing stored yet.");
            return;
        }
        for (MemoryEntry entry : entries) print(sender, entry);
    }

    private static void search(ICommandSender sender, MemoryStore memory, String[] args) {
        String query = args.length >= 3 ? SessionCommands.join(args, 2) : "";
        List<MemoryEntry> entries = memory.search(query, null, PAGE_SIZE);
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Memory search: "
                + (query.isEmpty() ? "(all)" : query) + " ===");
        if (entries.isEmpty()) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + "No matches.");
            return;
        }
        for (MemoryEntry entry : entries) print(sender, entry);
    }

    private static void forget(ICommandSender sender, MemoryStore memory, String[] args) {
        String key = args.length >= 3 ? SessionCommands.join(args, 2) : "";
        boolean removed = memory.forget(key);
        CortexDroneMod.sendMsg(sender, removed
                ? EnumChatFormatting.GREEN + "[Drone] Forgot '" + key + "'."
                : EnumChatFormatting.RED + "[Drone] No memory matched '" + key + "'.");
    }

    private static void remember(ICommandSender sender, MemoryStore memory, EntityPlayerMP player,
                                 String action, String[] args) {
        if (player == null) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "[Drone] Use memory here/remember in-game so the position is known.");
            return;
        }
        String text = args.length >= 3 ? SessionCommands.join(args, 2) : "";
        if (text.isEmpty()) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "[Drone] Usage: /drone memory " + action + " <text|name>");
            return;
        }
        MemoryEntry entry;
        if ("here".equals(action)) {
            entry = memory.remember("location", text,
                    "Location set by " + player.getCommandSenderName(),
                    true, player.posX, player.posY, player.posZ, player.dimension,
                    Collections.singletonList("player"), true,
                    "player:" + player.getCommandSenderName());
        } else {
            entry = memory.captureFromChat(player.getCommandSenderName(), player.dimension,
                    player.posX, player.posY, player.posZ, text);
        }
        if (entry == null) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "[Drone] Could not store that memory.");
            return;
        }
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GREEN + "[Drone] Remembered "
                + entry.getKind() + " '" + entry.getName() + "'"
                + (entry.isHasPosition() ? " at " + entry.positionSummary() : "") + ".");
    }

    private static void print(ICommandSender sender, MemoryEntry entry) {
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "[" + entry.getKind() + "] "
                + EnumChatFormatting.WHITE + entry.getName()
                + (entry.isHasPosition() ? EnumChatFormatting.GRAY + " " + entry.positionSummary() : "")
                + EnumChatFormatting.GRAY + " - " + entry.getText());
    }
}
