package ir.layercloud.cortexdrone.harness.skill;

import ir.layercloud.cortexdrone.service.world.LexicalItemMatcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.experimental.UtilityClass;

/**
 * BM25 retrieval over skills, with the same fuzzy token matching used by item
 * search. Metadata (name/tags/triggers) is weighted by repetition; the body is
 * deliberately not indexed because code tokens are noisy.
 */
@UtilityClass
public final class SkillSearch {
    /** BM25 smoothing term; keeps the idf finite for unseen terms. */
    private static final double BM25_SMOOTHING = 0.5D;
    private static final double K1 = 1.5D;
    private static final double B = 0.75D;

    public static List<Skill> search(List<Skill> skills, String query, int limit) {
        List<Skill> all = new ArrayList<>(skills);
        int max = Math.max(1, limit);
        if (query == null || query.trim().isEmpty()) {
            all.sort((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()));
            return new ArrayList<>(all.subList(0, Math.min(max, all.size())));
        }

        List<String> queryTokens = LexicalItemMatcher.tokenize(query);
        int docCount = Math.max(1, all.size());
        Map<String, List<String>> docTokens = new LinkedHashMap<>();
        double totalLength = 0.0D;
        for (Skill skill : all) {
            List<String> tokens = LexicalItemMatcher.tokenize(document(skill));
            docTokens.put(skill.getName(), tokens);
            totalLength += tokens.size();
        }
        double averageLength = Math.max(1.0D, totalLength / docCount);

        Map<String, Double> scores = new LinkedHashMap<>();
        for (String queryToken : queryTokens) {
            if (queryToken.length() < 3) continue;
            int documentFrequency = 0;
            for (Skill skill : all) {
                if (LexicalItemMatcher.tokenListContains(docTokens.get(skill.getName()), queryToken)) {
                    documentFrequency++;
                }
            }
            if (documentFrequency == 0) continue;
            // BM25 smoothing constant, unrelated to block geometry.
            double idf = Math.log(1.0D + (docCount - documentFrequency + BM25_SMOOTHING)
                    / (documentFrequency + BM25_SMOOTHING));
            for (Skill skill : all) {
                List<String> tokens = docTokens.get(skill.getName());
                int termFrequency = 0;
                for (String token : tokens) {
                    if (LexicalItemMatcher.fuzzyTokenMatches(token, queryToken)) termFrequency++;
                }
                if (termFrequency == 0) continue;
                double length = tokens.size();
                double termScore = idf * ((termFrequency * (K1 + 1.0D))
                        / (termFrequency + K1 * (1.0D - B + B * (length / averageLength))));
                scores.merge(skill.getName(), termScore, Double::sum);
            }
        }

        List<Skill> matched = new ArrayList<>();
        for (Skill skill : all) {
            if (scores.containsKey(skill.getName())) matched.add(skill);
        }
        matched.sort((a, b) -> {
            int compare = Double.compare(scores.getOrDefault(b.getName(), 0.0D),
                    scores.getOrDefault(a.getName(), 0.0D));
            if (compare != 0) return compare;
            int uses = Integer.compare(b.getUses(), a.getUses());
            if (uses != 0) return uses;
            return Long.compare(b.getUpdatedAt(), a.getUpdatedAt());
        });
        return new ArrayList<>(matched.subList(0, Math.min(max, matched.size())));
    }

    private static String document(Skill skill) {
        StringBuilder builder = new StringBuilder();
        appendRepeated(builder, skill.getName(), 2);
        appendRepeated(builder, skill.getTags(), 2);
        if (skill.getTriggers() != null) {
            for (String trigger : skill.getTriggers()) appendRepeated(builder, trigger, 2);
        }
        builder.append(' ').append(skill.getDescription() != null ? skill.getDescription() : "");
        return builder.toString();
    }

    private static void appendRepeated(StringBuilder builder, String text, int times) {
        if (text == null || text.isEmpty()) return;
        for (int i = 0; i < times; i++) builder.append(text).append(' ');
    }
}
