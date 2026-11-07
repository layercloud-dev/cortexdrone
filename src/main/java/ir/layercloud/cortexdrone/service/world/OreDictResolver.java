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

    private static final String[] AGGREGATE_PREFIXES = {
            "listall", "filler"
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
    private static volatile int indexedTagCount = -1;

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
        Index index = buildIndex(knownTags.toArray(new String[0]));
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
    public static boolean isTagQuery(String rawFilter) {
        return !resolveTagQuery(rawFilter).isEmpty();
    }

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

    public static boolean matchesItemTag(ItemStack stack, String rawFilter) {
        return scoreItemTagMatch(stack, rawFilter) > 0;
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
    public static com.google.gson.JsonObject describeTagQuery(String rawFilter, int limit) {
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        out.addProperty("query", normalizeRaw(rawFilter));
        com.google.gson.JsonArray tagsJson = new com.google.gson.JsonArray();
        com.google.gson.JsonArray itemsJson = new com.google.gson.JsonArray();
        List<String> tags = resolveTagQuery(rawFilter);
        int max = Math.max(1, limit);
        Set<String> seenEntries = new HashSet<>();
        for (String tag : tags) {
            tagsJson.add(new com.google.gson.JsonPrimitive(tag));
            if (itemsJson.size() >= max) continue;
            List<ItemStack> entries = safeGetOres(tag);
            for (ItemStack stack : entries) {
                if (itemsJson.size() >= max) break;
                if (stack == null || stack.getItem() == null) continue;
                String key = Item.itemRegistry.getNameForObject(stack.getItem())
                        + ":" + stack.getItemDamage();
                if (!seenEntries.add(key)) continue;
                com.google.gson.JsonObject io = ItemClassifier.itemStackToJson(-1, stack);
                io.addProperty("oreDict", tag);
                itemsJson.add(io);
            }
        }
        out.add("tags", tagsJson);
        out.add("items", itemsJson);
        out.addProperty("tagCount", tagsJson.size());
        out.addProperty("itemCount", itemsJson.size());
        return out;
    }

    // ------------------------------------------------------------------
    // Registry index
    // ------------------------------------------------------------------

    private static void refreshIndex() {
        String[] names;
        try {
            names = OreDictionary.getOreNames();
        } catch (Throwable unavailable) {
            names = new String[0];
        }
        if (names == null) names = new String[0];
        if (names.length == indexedTagCount) return;
        Index index = buildIndex(names);
        compactIndex = index.compact;
        tokenIndex = index.tokens;
        indexedTagCount = names.length;
        QUERY_CACHE.clear();
    }

    private static final class Index {
        final Map<String, String> compact;
        final Map<String, List<String>> tokens;

        Index(Map<String, String> compact, Map<String, List<String>> tokens) {
            this.compact = compact;
            this.tokens = tokens;
        }
    }

    private static Index buildIndex(String[] names) {
        Map<String, String> compact = new HashMap<>();
        Map<String, List<String>> tokens = new HashMap<>();
        if (names == null) return new Index(compact, tokens);
        for (String name : names) {
            if (name == null || name.isEmpty()) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (startsWithAny(lower, AGGREGATE_PREFIXES)) continue;
            compact.putIfAbsent(compactKey(name), name);
            List<String> parts = LexicalItemMatcher.tokenize(name);
            if (parts.isEmpty()) continue;
            List<String> sorted = new ArrayList<>(parts);
            Collections.sort(sorted);
            String key = joinTokens(sorted);
            List<String> bucket = tokens.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                tokens.put(key, bucket);
            }
            bucket.add(name);
        }
        return new Index(compact, tokens);
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
        }
        return out;
    }

    private static List<ItemStack> safeGetOres(String tag) {
        try {
            List<ItemStack> stacks = OreDictionary.getOres(tag, false);
            return stacks != null ? stacks : Collections.emptyList();
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
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

    // ------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------

    private static String normalizeRaw(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Lowercase letters+digits only; lets ore_iron/ore iron/oreIron collide. */
    public static String compactKey(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    private static String joinTokens(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (sb.length() > 0) sb.append('|');
            sb.append(token);
        }
        return sb.toString();
    }

    private static boolean startsWithAny(String value, String[] prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) return true;
        }
        return false;
    }

    /** True when {@code word} occurs in {@code text} with non-alphanumeric boundaries. */
    static boolean containsWord(String text, String word) {
        if (text == null || word == null || word.isEmpty()) return false;
        int from = 0;
        while (true) {
            int idx = text.indexOf(word, from);
            if (idx < 0) return false;
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
            int end = idx + word.length();
            boolean rightOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (leftOk && rightOk) return true;
            from = idx + 1;
        }
    }
}
