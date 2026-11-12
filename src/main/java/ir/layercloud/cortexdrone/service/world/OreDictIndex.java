package ir.layercloud.cortexdrone.service.world;

import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import java.util.*;

/**
 * Cached indexes over the OreDictionary tag list.
 *
 * <p>Extracted from {@code OreDictResolver}. The registry is rebuilt whenever the
 * tag count changes (a mod registering late), so two indexes are kept: a
 * spelling-insensitive compact map for exact lookups, and a token-sorted map for
 * multi-word queries. Both are replaced atomically.</p>
 */
final class OreDictIndex {
    /**
     * Aggregate tags that are not materials (e.g. {@code oreIron} groups).
     */
    private static final String[] AGGREGATE_PREFIXES = {"listAll", "oredict"};

    private static volatile Map<String, String> compact = Collections.emptyMap();
    private static volatile Map<String, List<String>> tokens = Collections.emptyMap();
    private static volatile int indexedTagCount = -1;

    private OreDictIndex() {
    }

    /**
     * One built index pair, for callers that supply their own tag universe.
     */
    static final class Snapshot {
        final Map<String, String> compact;
        final Map<String, List<String>> tokens;

        Snapshot(Map<String, String> compact, Map<String, List<String>> tokens) {
            this.compact = compact;
            this.tokens = tokens;
        }
    }

    /**
     * Builds an index from an explicit tag list (tests, offline resolution).
     */
    static Snapshot buildFor(String[] names) {
        Map<String, String> newCompact = new HashMap<>();
        Map<String, List<String>> newTokens = new HashMap<>();
        fill(names, newCompact, newTokens);
        return new Snapshot(newCompact, newTokens);
    }

    /**
     * The compact spelling-insensitive index, refreshing it when stale.
     */
    static Map<String, String> compact() {
        refresh();
        return compact;
    }

    /**
     * The token-sorted index, refreshing it when stale.
     */
    static Map<String, List<String>> tokens() {
        refresh();
        return tokens;
    }

    /**
     * Every registered tag name, or an empty array when the registry is absent.
     */
    static String[] tagNames() {
        try {
            String[] names = OreDictionary.getOreNames();
            return names;
        } catch (Throwable unavailable) {
            return new String[0];
        }
    }

    /**
     * The tag count observed at the last refresh, or {@code -1} before any.
     */
    static int tagCount() {
        return indexedTagCount;
    }

    /**
     * Rebuilds both indexes when the registry's tag count changed.
     */
    static void refresh() {
        String[] names;
        try {
            names = OreDictionary.getOreNames();
        } catch (Throwable unavailable) {
            names = new String[0];
        }
        if (names.length == indexedTagCount) return;
        build(names);
        indexedTagCount = names.length;
    }


    private static void build(String[] names) {
        Map<String, String> newCompact = new HashMap<>();
        Map<String, List<String>> newTokens = new HashMap<>();
        fill(names, newCompact, newTokens);
        compact = newCompact;
        tokens = newTokens;
    }

    private static void fill(String[] names, Map<String, String> newCompact,
                             Map<String, List<String>> newTokens) {
        if (names == null) return;
        for (String name : names) {
            if (name == null || name.isEmpty()) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (OreDictText.startsWithAny(lower, AGGREGATE_PREFIXES)) continue;
            newCompact.putIfAbsent(OreDictText.compactKey(name), name);
            List<String> parts = LexicalItemMatcher.tokenize(name);
            if (parts.isEmpty()) continue;
            List<String> sorted = new ArrayList<>(parts);
            Collections.sort(sorted);
            String key = OreDictText.joinTokens(sorted);
            List<String> bucket = newTokens.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                newTokens.put(key, bucket);
            }
            bucket.add(name);
        }
    }

    /**
     * The OreDictionary tags of a stack, lower-cased; empty when unregistered.
     */
    static Set<String> tagsOf(ItemStack stack) {
        Set<String> out = new HashSet<>();
        try {
            int[] ids = OreDictionary.getOreIDs(stack);
            for (int id : ids) {
                String name = OreDictionary.getOreName(id);
                if (name != null && !name.isEmpty() && !"Unknown".equalsIgnoreCase(name)) {
                    out.add(name.toLowerCase(Locale.ROOT));
                }
            }
        } catch (Throwable ignored) {
            // An unregistered stack has no tags.
        }
        return out;
    }

    /**
     * The stacks registered under a tag; empty when the tag is unknown.
     */
    static List<ItemStack> ores(String tag) {
        try {
            List<ItemStack> stacks = OreDictionary.getOres(tag, false);
            return stacks != null ? stacks : Collections.emptyList();
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }
}
