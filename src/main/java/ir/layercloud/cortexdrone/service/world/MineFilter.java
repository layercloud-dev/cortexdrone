package ir.layercloud.cortexdrone.service.world;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;

import java.util.Locale;

/**
 * The block-filter language used by mining and scanning.
 *
 * <p>Extracted from {@code ItemClassifier}. A filter is one of three things, in
 * priority order:</p>
 * <ol>
 *   <li>a <b>category</b> word ({@code ores}, {@code logs}, {@code crops}, ...);</li>
 *   <li>an <b>explicit tag</b> ({@code #oreTin}, {@code oredict:}, {@code dict:}) —
 *       these decide on the dictionary alone, so {@code #oreTin} cannot match a
 *       block merely called "tin";</li>
 *   <li>a <b>material word</b>, which resolves through the OreDictionary first,
 *       then the block's name.</li>
 * </ol>
 */
public final class MineFilter {
    private MineFilter() {
    }

    /** True when the block matches one clause of a mine filter. */
    public static boolean matches(Block block, int meta, String filter) {
        if (block == null || filter == null) return false;
        String clause = normalize(filter);
        int normalizedMeta = RegistryIntrospector.normalizeBlockMeta(block, meta);
        String display = RegistryNames.blockDisplayName(block, normalizedMeta);
        String identifier = RegistryNames.blockIdentifier(block, normalizedMeta);

        Boolean category = matchCategory(block, normalizedMeta, clause);
        if (category != null) return category;

        // An explicit tag filter decides on the dictionary alone.
        String raw = filter.trim();
        if (OreDictResolver.hasExplicitPrefix(raw)) {
            return OreDictResolver.scoreIdentifierTagMatch(identifier, raw) > 0;
        }
        // A player word resolves through the dictionary first, so "tin" reaches
        // a mod block named "Cassiterite" whose tags include oreTin.
        if (!clause.isEmpty() && OreDictResolver.scoreIdentifierTagMatch(identifier, clause) > 0) {
            return true;
        }
        if (identifier.contains("ore" + clause.replace(" ", ""))) return true;
        if (!clause.contains("block") && !clause.contains("ingot")
                && ItemClassifier.isOreBlock(block, normalizedMeta)
                && identifier.contains(clause)) {
            return true;
        }
        return LexicalItemMatcher.scoreSingleMatch(display, identifier, normalizedMeta, clause) > 0;
    }

    /** Category words resolve without touching the dictionary. */
    private static Boolean matchCategory(Block block, int meta, String clause) {
        switch (clause) {
            case "all non air":
            case "*":
                return block != Blocks.air;
            case "ore":
            case "ores":
            case "vein":
            case "all":
                return ItemClassifier.isOreBlock(block, meta);
            case "tree":
            case "trees":
            case "wood":
            case "log":
            case "logs":
                return ItemClassifier.isLogBlock(block);
            case "crop":
            case "crops":
            case "plant":
            case "plants":
                return ItemClassifier.isCropOrReedBlock(block);
            default:
                return null;
        }
    }

    private static String normalize(String filter) {
        return filter.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
