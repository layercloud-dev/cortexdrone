package ir.layercloud.cortexdrone.command.impl;

import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.harness.skill.Skill;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumChatFormatting;

import java.util.List;

/**
 * {@code /drone skills} — inspect and search the composable skill suite.
 *
 * <p>Extracted from {@link DroneCommand}. It prints what the library already
 * knows; it never executes a skill (that is the task agent's job).</p>
 */
public final class SkillCommands {
    /**
     * Source lines previewed per skill.
     */
    private static final int CODE_PREVIEW_LINES = 8;
    /**
     * Longest single source line shown in chat.
     */
    private static final int LINE_PREVIEW = 160;
    /**
     * Longest description shown in the list view.
     */
    private static final int DESCRIPTION_PREVIEW = 120;
    /**
     * Default number of skills listed.
     */
    private static final int DEFAULT_LIMIT = 40;
    /**
     * Hard ceiling for one listing.
     */
    private static final int MAX_LIMIT = 80;
    /**
     * Maximum search hits.
     */
    private static final int SEARCH_HITS = 12;

    private SkillCommands() {
    }

    public static void handle(ICommandSender sender, SkillLibrary library, String[] args) {
        if (library == null) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "[Drone] Skill library is not initialized yet.");
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase() : "list";
        if ("show".equals(action) || "load".equals(action)) {
            show(sender, library, args);
            return;
        }
        if ("disable".equals(action) || "enable".equals(action)) {
            toggle(sender, library, action, args);
            return;
        }
        if ("disabled".equals(action) || "retired".equals(action)) {
            disabled(sender, library);
            return;
        }
        list(sender, library, action, args);
    }

    /**
     * {@code skills disable|enable <name>} — operator only, because the skill
     * library is global to the server.
     */
    private static void toggle(ICommandSender sender, SkillLibrary library, String action, String[] args) {
        EntityPlayerMP player = sender instanceof EntityPlayerMP ? (EntityPlayerMP) sender : null;
        if (!AdminCommands.isOperator(player, sender)) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "[Drone] Only server operators can " + action + " a stored skill.");
            return;
        }
        String name = args.length >= 3 ? SessionCommands.join(args, 2) : "";
        if (name.trim().isEmpty()) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "Usage: /drone skills " + action + " <name>");
            return;
        }
        boolean disabling = "disable".equals(action);
        boolean changed = disabling ? library.disable(name) : library.enable(name);
        CortexDroneMod.sendMsg(sender, changed
                ? EnumChatFormatting.GREEN + "[Drone] Skill '" + SkillLibrary.normalize(name)
                        + "' " + (disabling ? "retired to skills-disabled/." : "enabled again.")
                : EnumChatFormatting.RED + "[Drone] Could not " + action + " '" + name
                        + "'. " + (disabling
                        ? "Only a learned skill directory can be disabled."
                        : "Only a directory in skills-disabled/ can be enabled."));
    }

    private static void disabled(ICommandSender sender, SkillLibrary library) {
        List<String> names = library.disabledNames();
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Retired skills ("
                + names.size() + ") ===");
        if (names.isEmpty()) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + "None.");
            return;
        }
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA
                + String.join(", ", names) + EnumChatFormatting.GRAY
                + "  (/drone skills enable <name>)");
    }

    private static void show(ICommandSender sender, SkillLibrary library, String[] args) {
        String name = args.length >= 3 ? SessionCommands.join(args, 2) : "";
        Skill skill = library.get(name);
        if (skill == null) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED
                    + "[Drone] No skill named '" + name + "'. Use /drone skills search <q>.");
            return;
        }
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Skill "
                + skill.getName() + " v" + skill.getVersion() + " ("
                + skill.getSource() + ", uses " + skill.getUses() + ") ===");
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + skill.getDescription());
        if (skill.getRequires() != null && !skill.getRequires().isEmpty()) {
            List<String> missing = library.missingRequires(skill.getName());
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + "requires: "
                    + EnumChatFormatting.WHITE + skill.getRequires()
                    + (missing.isEmpty() ? EnumChatFormatting.GRAY + " (all present)"
                    : EnumChatFormatting.RED + " missing " + missing));
        }
        String[] lines = skill.getCode().split("\n");
        int shown = Math.min(lines.length, CODE_PREVIEW_LINES);
        for (int i = 0; i < shown; i++) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.DARK_GRAY + "  "
                    + EnumChatFormatting.GRAY + SessionCommands.cap(lines[i], LINE_PREVIEW));
        }
        if (lines.length > shown) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.DARK_GRAY
                    + "  ... " + (lines.length - shown) + " more lines");
        }
    }

    private static void list(ICommandSender sender, SkillLibrary library, String action, String[] args) {
        List<Skill> found;
        if ("search".equals(action)) {
            found = library.search(args.length >= 3 ? SessionCommands.join(args, 2) : "", SEARCH_HITS);
        } else {
            int limit = args.length >= 3
                    ? SessionCommands.parsePositive(args[2], DEFAULT_LIMIT) : DEFAULT_LIMIT;
            found = library.search("", Math.min(MAX_LIMIT, limit));
        }
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Skills ("
                + found.size() + " shown, " + library.all().size() + " total) ===");
        for (Skill skill : found) {
            StringBuilder line = new StringBuilder();
            line.append(EnumChatFormatting.AQUA).append(skill.getName())
                    .append(EnumChatFormatting.GRAY).append(" v").append(skill.getVersion());
            if (skill.getRequires() != null && !skill.getRequires().isEmpty()) {
                line.append(EnumChatFormatting.DARK_GRAY).append(" -> ")
                        .append(EnumChatFormatting.GRAY)
                        .append(String.join(", ", skill.getRequires()));
            }
            CortexDroneMod.sendMsg(sender, line.toString());
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.DARK_GRAY + "   "
                    + SessionCommands.cap(skill.getDescription(), DESCRIPTION_PREVIEW));
        }
    }
}
