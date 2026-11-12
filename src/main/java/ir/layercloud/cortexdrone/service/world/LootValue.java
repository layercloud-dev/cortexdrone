package ir.layercloud.cortexdrone.service.world;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Is this drop worth picking up?" - one owner for the loot heuristic.
 *
 * <p>Mining and hunting leave a mix of valuable and worthless drops on the
 * ground. Sucking everything fills the drone's few slots with cobblestone while
 * an ore drop waits two blocks away, so the skills ask this class instead of
 * carrying their own word lists.</p>
 *
 * <p>The test is deliberately generous on the valuable side (metals, gems,
 * dusts, food, logs) and explicit on the junk side, matching the vanilla
 * "keep" list used by {@code drop_junk}. An unknown modded item is treated as
 * valuable: picking up an unfamiliar drop is recoverable, ignoring a mod's
 * platinum ore is not.</p>
 */
public final class LootValue {
    /**
     * OreDictionary tag stems that mark a valuable item.
     */
    private static final Set<String> VALUABLE_TAGS = new HashSet<>(Arrays.asList(
            "ore", "ingot", "gem", "dust", "nugget", "block", "plate", "raw",
            "food", "crop", "log", "plank", "tree", "wood"));

    /**
     * OreDictionary tag stems that mark a worthless item even when a broader
     * tag looks valuable.
     */
    private static final Set<String> JUNK_TAGS = new HashSet<>(Arrays.asList(
            "stone", "cobble", "dirt", "gravel", "sand", "seed", "sapling",
            "leaf", "stick", "string", "bone", "clay"));

    /**
     * Words in the display/registry name that mark a valuable item.
     */
    private static final Set<String> VALUABLE_WORDS = new HashSet<>(Arrays.asList(
            "ore", "ingot", "ingots", "gem", "gems", "crystal", "dust", "dusts",
            "nugget", "nuggets", "plate", "plates", "raw",
            "diamond", "emerald", "lapis", "redstone", "coal", "charcoal", "quartz",
            "certus", "uranium", "thorium", "plutonium", "steel", "copper", "tin",
            "lead", "silver", "nickel", "gold", "iron", "aluminum", "aluminium",
            "titanium", "tungsten", "osmium", "platinum", "iridium", "cobalt",
            "ardite", "manyullyn", "ender", "pearl", "blaze", "ghast", "slime",
            "food", "meat", "beef", "porkchop", "chicken", "mutton", "fish",
            "bread", "apple", "carrot", "potato", "wheat", "rice", "corn",
            "melon", "pumpkin", "cactus", "sugarcane", "bamboo",
            "log", "logs", "plank", "planks", "wood"));

    /**
     * Words that mark a worthless item even when a tag looks valuable
     * ("blockGravel" is still gravel).
     */
    private static final Set<String> JUNK_WORDS = new HashSet<>(Arrays.asList(
            "cobble", "cobblestone", "stone", "dirt", "gravel", "flint",
            "andesite", "diorite", "granite", "sand", "sandstone", "clay",
            "leaves", "leaf", "stick", "sticks", "string", "rotten", "bone",
            "bones", "gunpowder", "arrow", "arrows", "spider", "snowball", "egg",
            "feather", "torch", "bucket", "paper", "seed", "seeds", "sapling",
            "saplings"));

    private LootValue() {
    }

    /**
     * True when {@code stack} is worth collecting.
     */
    public static boolean isValuable(net.minecraft.item.ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) return false;
        return isValuable(stack.getDisplayName(),
                ItemClassifier.getItemIdentifier(stack),
                ItemClassifier.getOreDictNames(stack));
    }

    /**
     * Value test for the parts of an item that the JSON layer already has.
     */
    public static boolean isValuable(String displayName, String registryName, List<String> tags) {
        if (isJunk(displayName, registryName)) return false;
        if (hasTag(tags, JUNK_TAGS)) return false;
        if (hasTag(tags, VALUABLE_TAGS)) return true;
        if (containsWord(displayName, VALUABLE_WORDS)) return true;
        if (containsWord(registryName, VALUABLE_WORDS)) return true;
        // An unknown item is not automatically garbage.
        return true;
    }

    /**
     * True when the item is a known junk drop.
     */
    public static boolean isJunk(String displayName, String registryName) {
        return containsWord(displayName, JUNK_WORDS) || containsWord(registryName, JUNK_WORDS);
    }

    /**
     * Matches a drop filter: {@code all}, {@code valuable}, {@code junk}, or a
     * name/registry phrase.
     */
    public static boolean matchesFilter(net.minecraft.item.ItemStack stack, String filter) {
        if (filter == null || filter.trim().isEmpty()) return true;
        String f = filter.trim().toLowerCase(Locale.ROOT);
        if (f.equals("all") || f.equals("any") || f.equals("*")) return true;
        boolean valuable = isValuable(stack);
        if (f.equals("valuable") || f.equals("good") || f.equals("loot")) return valuable;
        if (f.equals("junk") || f.equals("trash")) return !valuable;
        return ItemClassifier.matchesBlockOrItemName(stack.getDisplayName(),
                ItemClassifier.getItemIdentifier(stack), stack.getItemDamage(), filter);
    }

    /**
     * First word stem of a camelCase tag: {@code oreTin -> ore}.
     */
    private static String tagStem(String tag) {
        String spaced = tag.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        int space = spaced.indexOf(' ');
        String first = (space > 0 ? spaced.substring(0, space) : spaced).toLowerCase(Locale.ROOT);
        return first.replaceAll("[^a-z0-9]", "");
    }

    private static boolean hasTag(List<String> tags, Set<String> stems) {
        if (tags == null) return false;
        for (String tag : tags) {
            if (tag == null) continue;
            if (stems.contains(tagStem(tag))) return true;
        }
        return false;
    }

    /**
     * Whole-word match, with camelCase split so {@code ingotSteel} yields
     * {@code ingot} and {@code steel}. A substring test would make "Forest"
     * match "ore" and turn a sapling into loot.
     */
    private static boolean containsWord(String text, Set<String> words) {
        if (text == null || text.isEmpty()) return false;
        String spaced = text.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        String[] tokens = spaced.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        for (String token : tokens) {
            if (!token.isEmpty() && words.contains(token)) return true;
        }
        return false;
    }
}
