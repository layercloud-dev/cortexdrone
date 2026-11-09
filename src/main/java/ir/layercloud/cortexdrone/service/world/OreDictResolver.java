package ir.layercloud.cortexdrone.service.world;

import lombok.experimental.UtilityClass;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forge OreDictionary-backed filter resolution.
 *
 * <p>A player rarely knows a mod's registry name. "Bring me tin" must match a
 * block whose display name is "Cassiterite Ore" when it is registered as
 * {@code oreTin}; "32 iron ingots" must match every {@code ingotIron} variant
 * from every mod. This resolver turns a natural-language or tag-shaped filter
 * into canonical OreDictionary tags and scores items/blocks against them.</p>
 *
 * <p>Supported filter shapes:</p>
 * <ul>
 *   <li>explicit tag — {@code #oreIron}, {@code oredict:oreIron},
 *       {@code ore_dict:oreIron}, {@code dict:oreTin}, {@code tag:plankWood};</li>
 *   <li>exact tag name, any case/spacing — {@code oreIron}, {@code ore_iron},
 *       {@code ore iron};</li>
 *   <li>token query — {@code iron ingot} resolves {@code ingotIron},
 *       {@code tin} resolves {@code oreTin}/{@code ingotTin}/...</li>
 * </ul>
 *
 * <p>Derivative tags ({@code dustIron}, {@code nuggetIron}, {@code gearIron},
 * ...) are ignored unless the query itself asks for that shape, so a plain
 * "iron" request never silently returns dust or nuggets.</p>
 */
@UtilityClass
public final class OreDictResolver {
    private static final String[] TAG_PREFIXES = {
            "oredict:", "ore_dict:", "ore-dict:", "dict:", "tag:", "#"
    };

    /** Tag shapes that mean an actual obtainable material, best first. */
    private static final String[] RESOURCE_SHAPE_PREFIXES = {
            "ore", "raw", "ingot", "gem", "crystal", "cluster", "dust", "nugget",
            "plate", "gear", "billet", "coal", "lignite"
    };

    /** Filler words that never select a tag on their own. */
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "of", "the", "a", "an", "some", "any", "me", "my", "please", "bring",
            "get", "give", "fetch", "find", "more", "and", "for", "to", "from", "with"
    ));

    private static final int MAX_CACHED_QUERIES = 256;
    private static final int MAX_TOKEN_FALLBACK_TAGS = 64;
    private static final Map<String, List<String>> QUERY_CACHE = new ConcurrentHashMap<>();

    /** Category words are matched by dedicated code, never by dictionary sweep. */
    private static final Set<String> CATEGORY_TOKENS = new HashSet<>(Arrays.asList(
            "ore", "vein", "all", "any", "stone", "wood", "log", "crop", "crops",
            "plant", "liquid", "block", "blocks", "item", "items", "solid", "tree"
    ));

    private static volatile Map<String, String> compactIndex = Collections.emptyMap();
    private static volatile Map<String, List<String>> tokenIndex = Collections.emptyMap();

    /** True when the filter explicitly names an OreDictionary tag. */
    public static boolean hasExplicitPrefix(String rawFilter) {
        String raw = normalizeRaw(rawFilter);
        if (raw.isEmpty()) return false;
        for (String prefix : TAG_PREFIXES) {
            if (raw.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Removes a leading {@code #}/{@code oredict:}/{@code dict:} marker, preserving case. */
    public static String stripPrefix(String rawFilter) {
        String raw = rawFilter == null ? "" : rawFilter.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        for (String prefix : TAG_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return raw.substring(prefix.length()).trim();
            }
        }
        return raw;
    }

    /**
     * Resolves a filter to canonical OreDictionary tag names.
     *
     * <p>An explicit prefix resolves only the named tag. A bare query first
     * tries an exact/compact tag name and then a token match, so both
     * {@code ore_iron} and {@code iron ore} find {@code oreIron}.</p>
     */
    public static List<String> resolveTagQuery(String rawFilter) {
        String raw = normalizeRaw(rawFilter);
        if (raw.isEmpty()) return Collections.emptyList();
        String cacheKey = (hasExplicitPrefix(raw) ? "#" : "") + compactKey(stripPrefix(raw));
        List<String> cached = QUERY_CACHE.get(cacheKey);
        if (cached != null) return cached;

        refreshIndex();
        List<String> result = resolveAgainst(
                raw, compactIndex, tokenIndex);
        if (QUERY_CACHE.size() >= MAX_CACHED_QUERIES) QUERY_CACHE.clear();
        QUERY_CACHE.put(cacheKey, result);
        return result;
    }

    /**
     * Pure resolution against a known tag universe. Exposed so the matching
     * rules can be tested (and reused) without a booted Forge registry.
     */
    public static List<String> resolveTags(String rawFilter, List<String> knownTags) {
        if (knownTags == null || knownTags.isEmpty()) return Collections.emptyList();
        OreDictIndex.Snapshot index = OreDictIndex.buildFor(knownTags.toArray(new String[0]));
        return resolveAgainst(normalizeRaw(rawFilter), index.compact, index.tokens);
    }

    private static List<String> resolveAgainst(String rawFilter, Map<String, String> compact,
                                               Map<String, List<String>> tokens) {
        String raw = normalizeRaw(rawFilter);
        if (raw.isEmpty()) return Collections.emptyList();
        boolean explicit = hasExplicitPrefix(raw);
        String name = stripPrefix(raw);
        if (name.isEmpty()) return Collections.emptyList();

        List<String> queryTokens = LexicalItemMatcher.tokenize(name);
        List<String> filteredTokens = new ArrayList<>();
        for (String token : queryTokens) {
            String sing = LexicalItemMatcher.singularize(token);
            if (!STOPWORDS.contains(sing)) filteredTokens.add(token);
        }
        // Category words ("ores", "stone", "all", "logs") belong to dedicated
        // code. Never let one sweep the dictionary and shadow name matching.
        if (!explicit && filteredTokens.size() <= 1) {
            String only = filteredTokens.isEmpty() ? "" : LexicalItemMatcher.singularize(filteredTokens.get(0));
            if (only.isEmpty() || CATEGORY_TOKENS.contains(only)) return Collections.emptyList();
        }

        List<String> out = new ArrayList<>();
        String exact = compact.get(compactKey(name));
        if (exact != null) out.add(exact);

        if (!explicit && out.isEmpty() && !filteredTokens.isEmpty()) {
            List<String> sorted = new ArrayList<>(filteredTokens);
            Collections.sort(sorted);
            List<String> sameTokens = tokens.get(joinTokens(sorted));
            if (sameTokens != null) out.addAll(sameTokens);
            if (out.isEmpty()) {
                out.addAll(tagsContainingAll(filteredTokens, tokens));
            }
        }

        List<String> filtered = filterDerivativeTags(out, queryTokens);
        return Collections.unmodifiableList(filtered);
    }

    /** True when the query resolves to at least one registered tag. */

    /**
     * Dictionary score for an item stack. 100 = explicit tag hit, 95 = exact
     * tag-name hit, 80 = token-derived tag hit, 0 = no dictionary match.
     */
    public static int scoreItemTagMatch(ItemStack stack, String rawFilter) {
        if (stack == null || stack.getItem() == null) return 0;
        String raw = normalizeRaw(rawFilter);
        if (raw.isEmpty()) return 0;
        boolean explicit = hasExplicitPrefix(raw);
        List<String> tags = resolveTagQuery(raw);
        if (tags.isEmpty()) return 0;

        Set<String> own = lowerTags(stack);
        if (own.isEmpty()) return 0;

        boolean hit = false;
        for (String tag : tags) {
            if (own.contains(tag.toLowerCase(Locale.ROOT))) {
                hit = true;
                break;
            }
        }
        if (!hit) return 0;

        String wanted = compactKey(stripPrefix(raw));
        for (String tag : tags) {
            if (compactKey(tag).equals(wanted)) return explicit ? 100 : 95;
        }
        return explicit ? 0 : 80;
    }

    /**
     * Dictionary score for an already-built item/block identifier string.
     *
     * <p>{@link ItemClassifier#getBlockIdentifier} and
     * {@link ItemClassifier#getItemIdentifier} append every OreDictionary tag,
     * so scans can avoid per-voxel dictionary lookups and still match tags.</p>
     */
    public static int scoreIdentifierTagMatch(String identifier, String rawFilter) {
        if (identifier == null || identifier.isEmpty()) return 0;
        String raw = normalizeRaw(rawFilter);
        if (raw.isEmpty()) return 0;
        boolean explicit = hasExplicitPrefix(raw);
        List<String> tags = resolveTagQuery(raw);
        if (tags.isEmpty()) return 0;

        String haystack = identifier.toLowerCase(Locale.ROOT);
        String wanted = compactKey(stripPrefix(raw));
        int best = 0;
        for (String tag : tags) {
            if (!containsWord(haystack, tag.toLowerCase(Locale.ROOT))) continue;
            int score = compactKey(tag).equals(wanted) ? (explicit ? 100 : 95) : 80;
            if (score > best) best = score;
        }
        return best;
    }

    /** JSON description of tags and their registered stacks, for {@code doc_lookup}. */
    /** @see OreDictDescribe#tagsAndItems(String, int, java.util.List) */
    public static com.google.gson.JsonObject describeTagQuery(String rawFilter, int limit) {
        return OreDictDescribe.tagsAndItems(normalizeRaw(rawFilter), limit, resolveTagQuery(rawFilter));
    }

    // ------------------------------------------------------------------
    // Registry index
    // ------------------------------------------------------------------

    private static void refreshIndex() {
        OreDictIndex.refresh();
        compactIndex = OreDictIndex.compact();
        tokenIndex = OreDictIndex.tokens();
    }

    private static List<String> tagsContainingAll(List<String> queryTokens,
                                                  Map<String, List<String>> tokenIndex) {
        if (queryTokens.isEmpty() || queryTokens.size() > 3) return Collections.emptyList();
        List<String> querySingular = new ArrayList<>();
        for (String token : queryTokens) {
            String sing = LexicalItemMatcher.singularize(token);
            if (!CATEGORY_TOKENS.contains(sing) && !STOPWORDS.contains(sing)) querySingular.add(sing);
        }
        if (querySingular.isEmpty()) return Collections.emptyList();
        boolean singleToken = querySingular.size() == 1;
        if (singleToken && querySingular.get(0).length() < 3) return Collections.emptyList();

        List<String> exactShape = new ArrayList<>();
        List<String> loose = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : tokenIndex.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            boolean all = true;
            for (String want : querySingular) {
                boolean found = false;
                for (String part : parts) {
                    if (LexicalItemMatcher.singularize(part).equals(want)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    all = false;
                    break;
                }
            }
            if (!all) continue;
            for (String tag : entry.getValue()) {
                if (hasResourceShapePrefix(tag)) exactShape.add(tag);
                else loose.add(tag);
            }
        }
        // Multi-word queries need a real containment match, not a pile of
        // single-word tags: "iron ingot" must not return every oreIron block.
        List<String> out = exactShape.isEmpty() ? loose : exactShape;
        if (!singleToken) {
            List<String> strict = new ArrayList<>();
            for (String tag : out) {
                List<String> tagTokens = new ArrayList<>();
                for (String part : LexicalItemMatcher.tokenize(tag)) {
                    tagTokens.add(LexicalItemMatcher.singularize(part));
                }
                if (tagTokens.size() <= querySingular.size() + 1
                        && tagTokens.containsAll(querySingular)) {
                    strict.add(tag);
                }
            }
            out = strict;
        }
        if (out.size() > MAX_TOKEN_FALLBACK_TAGS) {
            return new ArrayList<>(out.subList(0, MAX_TOKEN_FALLBACK_TAGS));
        }
        return out;
    }

    /** True when a tag starts with a material shape prefix such as {@code ore}. */
    private static boolean hasResourceShapePrefix(String tag) {
        if (tag == null) return false;
        String lower = tag.toLowerCase(Locale.ROOT);
        for (String prefix : RESOURCE_SHAPE_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    private static List<String> filterDerivativeTags(List<String> tags, List<String> queryTokens) {
        if (tags.isEmpty() || queryTokens.isEmpty()) return tags;
        for (String token : queryTokens) {
            if (LexicalItemMatcher.isDerivativeShapeToken(token)) return tags;
        }
        List<String> plain = new ArrayList<>(tags.size());
        List<String> derivative = new ArrayList<>(tags.size());
        for (String tag : tags) {
            boolean isDerivative = false;
            for (String token : LexicalItemMatcher.tokenize(tag)) {
                if (LexicalItemMatcher.isDerivativeShapeToken(token)) {
                    isDerivative = true;
                    break;
                }
            }
            if (isDerivative) derivative.add(tag);
            else plain.add(tag);
        }
        // A plain "iron" prefers oreIngot/ingotIron over dustIron, but when the
        // material only exists in a derivative shape ("redstone" ->
        // dustRedstone) that lone result must survive.
        return plain.isEmpty() ? derivative : plain;
    }

    private static Set<String> lowerTags(ItemStack stack) {
        return OreDictIndex.tagsOf(stack);
    }


    /** Canonical tag for a block/metadata pair, or null when unregistered. */
    public static String tagForBlock(Block block, int meta) {
        if (block == null || block == Blocks.air) return null;
        try {
            Item item = Item.getItemFromBlock(block);
            if (item == null) return null;
            int damage = RegistryIntrospector.normalizeBlockMeta(block, meta);
            List<String> tags = RegistryIntrospector.getOreDictNames(new ItemStack(item, 1, damage));
            return tags.isEmpty() ? null : tags.get(0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // Text helpers live in OreDictText; these delegates keep the old call sites.

    private static String normalizeRaw(String raw) {
        return OreDictText.normalizeRaw(raw);
    }

    /** @see OreDictText#compactKey(String) */
    public static String compactKey(String value) {
        return OreDictText.compactKey(value);
    }

    private static String joinTokens(List<String> tokens) {
        return OreDictText.joinTokens(tokens);
    }


    /** @see OreDictText#containsWord(String, String) */
    static boolean containsWord(String text, String word) {
        return OreDictText.containsWord(text, word);
    }
}
