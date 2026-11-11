package ir.layercloud.cortexdrone.service.search;

import ir.layercloud.cortexdrone.service.world.LexicalItemMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Shared ranked text search for the memory and lesson stores.
 *
 * <p>Both stores had near-identical pinned/recency ordering, token scoring and
 * {@code 50}/{@code 20} caps. The algorithm and caps now live here.</p>
 */
public final class RankedSearch {
    /**
     * Smallest number of results a store search returns.
     */
    public static final int MIN_RESULTS = 1;
    /**
     * Largest number of results a store search returns.
     */
    public static final int MAX_RESULTS = 50;
    /**
     * Largest number of entries injected into task context.
     */
    public static final int MAX_CONTEXT = 20;

    private RankedSearch() {
    }

    /**
     * Scores one entry against the cleaned query and its tokens.
     */
    public interface Scorer<T> {
        double score(T entry, String query, List<String> tokens);
    }

    /**
     * Clamps a search result limit.
     */
    public static int clampLimit(int limit) {
        return Math.max(1, Math.min(MAX_RESULTS, limit));
    }

    /**
     * Clamps a context-injection limit.
     */
    public static int clampContext(int limit) {
        return Math.max(1, Math.min(MAX_CONTEXT, limit));
    }

    /**
     * Orders by pinned/recency when the query is empty, otherwise scores each
     * entry and orders by score then recency.
     */
    public static <T> List<T> search(List<T> all, String query, int limit,
                                     Predicate<T> pinned, ToLongFunction<T> updatedAt,
                                     Scorer<T> scorer) {
        int max = clampLimit(limit);
        String clean = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        if (clean.isEmpty()) {
            all.sort((a, b) -> {
                int byPinned = Boolean.compare(pinned.test(b), pinned.test(a));
                if (byPinned != 0) return byPinned;
                return Long.compare(updatedAt.applyAsLong(b), updatedAt.applyAsLong(a));
            });
            return new ArrayList<>(all.subList(0, Math.min(max, all.size())));
        }
        List<String> tokens = LexicalItemMatcher.tokenize(clean);
        List<Scored<T>> scored = new ArrayList<>();
        for (T entry : all) {
            double score = scorer.score(entry, clean, tokens);
            if (score > 0) scored.add(new Scored<>(entry, score));
        }
        scored.sort((a, b) -> {
            int byScore = Double.compare(b.score, a.score);
            if (byScore != 0) return byScore;
            return Long.compare(updatedAt.applyAsLong(b.entry), updatedAt.applyAsLong(a.entry));
        });
        List<T> out = new ArrayList<>();
        for (int i = 0; i < scored.size() && i < max; i++) out.add(scored.get(i).entry);
        return out;
    }

    private static final class Scored<T> {
        final T entry;
        final double score;

        Scored(T entry, double score) {
            this.entry = entry;
            this.score = score;
        }
    }
}
