package ir.layercloud.cortexdrone.service.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Word-boundary lexical & structural item/block matcher and relevance scorer.
 * Tokenizes camelCase, snake_case, and registry identifiers so words are compared cleanly
 * without substring false positives (e.g. "chest" never matches "chestplate", and base
 * materials do not accidentally match derivative shapes like stairs/slabs/bricks unless requested).
 */
@UtilityClass
public final class LexicalItemMatcher {
    private static final Set<String> DERIVATIVE_SHAPE_TOKENS = new HashSet<String>(Arrays.asList(
        "stairs", "stair", "slab", "slabs", "wall", "walls", "fence", "gate",
        "brick", "bricks", "pillar", "chiseled", "pane", "bars", "nugget",
        "dust", "tiny", "powder", "crushed", "gear", "plate", "wire", "cable",
        "chestplate", "leggings", "helmet", "boots", "tunic", "cap", "pants"
    ));

    private static final Set<String> SPECIAL_CONTAINER_QUALIFIERS = new HashSet<String>(Arrays.asList(
        "ender", "sky", "skystone", "trapped", "personal", "void"
    ));

    private static final Set<String> FILLED_LIQUID_MODIFIERS = new HashSet<String>(Arrays.asList(
        "water", "lava", "milk", "oil", "fuel", "acid", "blood", "sludge", "toxic", "coolant", "cryotheum", "pyrotheum"
    ));

    /**
     * Splits any camelCase, snake_case, namespace:id, or display name string into lowercase word tokens.
     */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<String>();
        if (text == null || text.isEmpty()) return tokens;
        StringBuilder cur = new StringBuilder();
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (Character.isUpperCase(c) && cur.length() > 0) {
                    char prev = text.charAt(i - 1);
                    if (Character.isLowerCase(prev)) {
                        addNormalizedToken(tokens, cur.toString());
                        cur.setLength(0);
                    }
                }
                cur.append(Character.toLowerCase(c));
            } else {
                if (cur.length() > 0) {
                    addNormalizedToken(tokens, cur.toString());
                    cur.setLength(0);
                }
            }
        }
        if (cur.length() > 0) {
            addNormalizedToken(tokens, cur.toString());
        }
        return tokens;
    }

    private static void addNormalizedToken(List<String> out, String rawTok) {
        if (rawTok == null || rawTok.isEmpty()) return;
        if ("tile".equals(rawTok) || "item".equals(rawTok) || "name".equals(rawTok)) return;
        out.add(rawTok);
    }

    public static String singularize(String token) {
        if (token == null || token.length() <= 3) return token;
        if (token.endsWith("ies") && token.length() > 4) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.endsWith("s") && !token.endsWith("ss") && !token.equals("glass") && !token.equals("grass") && !token.equals("asbestos")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    public static int damerauLevenshteinDistance(String a, String b) {
        if (a == null || b == null) return 99;
        int lenA = a.length(), lenB = b.length();
        if (Math.abs(lenA - lenB) > 2) return Math.abs(lenA - lenB);
        int[][] dp = new int[lenA + 1][lenB + 1];
        for (int i = 0; i <= lenA; i++) dp[i][0] = i;
        for (int j = 0; j <= lenB; j++) dp[0][j] = j;
        for (int i = 1; i <= lenA; i++) {
            for (int j = 1; j <= lenB; j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(
                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                );
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    dp[i][j] = Math.min(dp[i][j], dp[i - 2][j - 2] + 1);
                }
            }
        }
        return dp[lenA][lenB];
    }

    public static boolean fuzzyTokenMatches(String candidateTok, String queryTok) {
        if (candidateTok == null || queryTok == null) return false;
        if (candidateTok.equals(queryTok)) return true;
        String cSing = singularize(candidateTok);
        String qSing = singularize(queryTok);
        if (cSing.equals(qSing)) return true;
        // Typo-tolerant Damerau-Levenshtein matching when first letter matches and length >= 4
        if (cSing.length() >= 4 && qSing.length() >= 4 && cSing.charAt(0) == qSing.charAt(0)) {
            int maxDist = (cSing.length() >= 7 && qSing.length() >= 7) ? 2 : 1;
            if (damerauLevenshteinDistance(cSing, qSing) <= maxDist) {
                return true;
            }
        }
        return false;
    }

    public static boolean tokenListContains(List<String> list, String queryTok) {
        for (String t : list) {
            if (fuzzyTokenMatches(t, queryTok)) {
                return true;
            }
            if (queryTok.length() >= 5 && t.length() >= 5 && (t.equals(queryTok) || t.startsWith(queryTok) || queryTok.startsWith(t))) {
                if (!DERIVATIVE_SHAPE_TOKENS.contains(t) && !DERIVATIVE_SHAPE_TOKENS.contains(queryTok)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Computes a match relevance score in [0..100] (0 = no match, 100 = exact display name match).
     */
    public static int scoreSingleMatch(String displayName, String fullId, int meta, String rawFilter) {
        if (rawFilter == null) return 0;
        String f = rawFilter.trim().toLowerCase().replace('_', ' ');
        if (f.isEmpty()) return 0;

        int colonIdx = f.lastIndexOf(':');
        if (colonIdx > 0 && colonIdx < f.length() - 1) {
            String after = f.substring(colonIdx + 1);
            try {
                int reqMeta = Integer.parseInt(after);
                if (meta != reqMeta) return 0;
                f = f.substring(0, colonIdx).trim();
            } catch (NumberFormatException ignored) {
            }
        }

        String disp = (displayName != null ? displayName : "").trim().toLowerCase().replace('_', ' ');
        String id = (fullId != null ? fullId : "").toLowerCase();

        if (disp.equals(f)) {
            return 100;
        }

        List<String> queryTokens = tokenize(f);
        if (queryTokens.isEmpty()) return 0;
        List<String> dispTokens = tokenize(disp);
        List<String> idTokens = tokenize(id);

        // 1) Guard against derivative shapes (stairs, slabs, bricks, chestplate, etc.) when the query didn't ask for them
        for (String dt : dispTokens) {
            if (DERIVATIVE_SHAPE_TOKENS.contains(dt) && !tokenListContains(queryTokens, dt)) {
                return 0;
            }
        }
        for (String it : idTokens) {
            if (("chestplate".equals(it) || "leggings".equals(it) || "helmet".equals(it) || "boots".equals(it))
                && !tokenListContains(queryTokens, it)) {
                return 0;
            }
        }

        // 2) Guard against special chests (Sky Stone Chest, Ender Chest, Trapped Chest) when querying plain "chest"
        if (tokenListContains(queryTokens, "chest")) {
            for (String sq : SPECIAL_CONTAINER_QUALIFIERS) {
                if ((dispTokens.contains(sq) || idTokens.contains(sq)) && !tokenListContains(queryTokens, sq)) {
                    return 0;
                }
            }
        }

        // 3) Guard against filled buckets when querying plain "bucket" or "empty bucket"
        if (tokenListContains(queryTokens, "bucket")) {
            boolean queryAsksLiquid = false;
            for (String lm : FILLED_LIQUID_MODIFIERS) {
                if (tokenListContains(queryTokens, lm)) {
                    queryAsksLiquid = true;
                    break;
                }
            }
            if (!queryAsksLiquid) {
                for (String lm : FILLED_LIQUID_MODIFIERS) {
                    if (dispTokens.contains(lm) || idTokens.contains(lm) || id.contains("bucket" + lm)) {
                        return 0;
                    }
                }
            }
        }

        // 4) Check if all query tokens match dispTokens (highest token tier)
        boolean allInDisp = true;
        for (String qt : queryTokens) {
            if ("minecraft".equals(qt) || "empty".equals(qt)) continue;
            if (!tokenListContains(dispTokens, qt)) {
                allInDisp = false;
                break;
            }
        }
        if (allInDisp) {
            if (dispTokens.size() == queryTokens.size()) {
                return 90;
            }
            return Math.max(65, 85 - Math.abs(dispTokens.size() - queryTokens.size()) * 5);
        }

        // 5) Check if all query tokens are present across displayName + fullId (e.g., "oak_log" -> "oak" in "Oak Wood" + "log" in "minecraft:log")
        List<String> combinedTokens = new ArrayList<String>(dispTokens.size() + idTokens.size());
        combinedTokens.addAll(dispTokens);
        combinedTokens.addAll(idTokens);

        boolean allInCombined = true;
        for (String qt : queryTokens) {
            if ("empty".equals(qt)) continue;
            if (!tokenListContains(combinedTokens, qt)) {
                allInCombined = false;
                break;
            }
        }
        if (allInCombined) {
            return 60;
        }

        // 6) Compound word fallback (e.g., "skystone" matching "Sky Stone", or "sugarcane" matching "Sugar Canes")
        String fCompact = f.replace(" ", "");
        String dispCompact = disp.replace(" ", "");
        if (fCompact.length() >= 4 && (dispCompact.equals(fCompact) || dispCompact.equals(singularize(fCompact)))) {
            return 85;
        }
        if (fCompact.length() >= 5 && (dispCompact.contains(fCompact) || id.replace("_", "").contains(fCompact))) {
            return 45;
        }

        return 0;
    }
}
