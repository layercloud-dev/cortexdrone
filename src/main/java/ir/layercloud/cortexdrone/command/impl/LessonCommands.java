package ir.layercloud.cortexdrone.command.impl;

import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.harness.lesson.Lesson;
import ir.layercloud.cortexdrone.harness.lesson.LessonStore;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.EnumChatFormatting;

import java.util.List;

/**
 * {@code /drone lesson} — inspect or forget learned procedural rules.
 *
 * <p>Extracted from {@link DroneCommand}: read-only listing plus one destructive
 * action ({@code forget}) that delegates to the store.</p>
 */
public final class LessonCommands {
    /**
     * Longest lesson summary shown in chat.
     */
    private static final int SUMMARY_PREVIEW = 160;
    /**
     * Maximum lessons shown per query.
     */
    private static final int PAGE_SIZE = 12;

    private LessonCommands() {
    }

    public static void handle(ICommandSender sender, LessonStore lessons, String[] args) {
        if (lessons == null) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "[Drone] Lessons are not initialized yet.");
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase() : "list";
        if ("forget".equals(action)) {
            String key = args.length >= 3 ? SessionCommands.join(args, 2) : "";
            boolean removed = lessons.forget(key);
            CortexDroneMod.sendMsg(sender, removed
                    ? EnumChatFormatting.GREEN + "[Drone] Forgot lesson '" + key + "'."
                    : EnumChatFormatting.RED + "[Drone] No lesson matched '" + key + "'.");
            return;
        }
        if (!"list".equals(action) && !"search".equals(action)) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "Usage: /drone lesson [list|search <q>|forget <id|title>]");
            return;
        }
        String query = "search".equals(action) && args.length >= 3
                ? SessionCommands.join(args, 2) : "";
        List<Lesson> entries = lessons.search(query, null, PAGE_SIZE);
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Drone Lessons ("
                + lessons.count() + " stored) ===");
        if (entries.isEmpty()) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + "Nothing stored yet.");
            return;
        }
        for (Lesson entry : entries) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "[" + entry.getScope() + "/"
                    + entry.getOutcome() + "] " + EnumChatFormatting.WHITE + entry.getTitle()
                    + EnumChatFormatting.GRAY + " - " + entry.summary(SUMMARY_PREVIEW));
        }
    }
}
