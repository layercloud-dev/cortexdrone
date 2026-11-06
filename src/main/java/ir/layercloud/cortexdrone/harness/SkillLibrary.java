package ir.layercloud.cortexdrone.harness;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ir.layercloud.cortexdrone.CortexDroneMod;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

public class SkillLibrary {
    @Data
    public static class SkillEntry {
        private String name;
        private String description;
        private String code;
        private String tags;
        private int uses;
        private long updatedAt;
    }

    private final File storageFile;
    private final Map<String, SkillEntry> skills = new LinkedHashMap<String, SkillEntry>();

    public SkillLibrary(File worldDir) {
        File dir = worldDir != null ? worldDir : new File(".");
        this.storageFile = new File(dir, "layercloud-drone-skills-v2.json");
        loadBundledSkills();
        loadFromDisk();
    }

    /**
     * Loads built-in skills shipped in {@code resources/skills/}. The index is
     * a JSON array of filenames; saved world skills are loaded afterwards and
     * override built-ins with the same name.
     */
    private synchronized void loadBundledSkills() {
        InputStream indexStream = SkillLibrary.class.getResourceAsStream("/skills/index.json");
        if (indexStream == null) return;
        try (InputStreamReader indexReader = new InputStreamReader(indexStream, "UTF-8")) {
            JsonElement index = new JsonParser().parse(indexReader);
            if (index == null || !index.isJsonArray()) return;
            for (JsonElement item : index.getAsJsonArray()) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) continue;
                String file = item.getAsString();
                InputStream skillStream = SkillLibrary.class.getResourceAsStream("/skills/" + file);
                if (skillStream == null) continue;
                try (InputStreamReader skillReader = new InputStreamReader(skillStream, "UTF-8")) {
                    SkillEntry skill = CortexDroneMod.GSON.fromJson(skillReader, SkillEntry.class);
                    if (skill == null || skill.getName() == null || skill.getName().trim().isEmpty()) continue;
                    String key = skill.getName().trim().toLowerCase().replace(' ', '_');
                    skill.setName(key);
                    if (skill.getDescription() == null) skill.setDescription("");
                    if (skill.getCode() == null) skill.setCode("");
                    if (skill.getTags() == null) skill.setTags("");
                    if (skill.getUses() <= 0) skill.setUses(1);
                    if (skill.getUpdatedAt() <= 0) skill.setUpdatedAt(System.currentTimeMillis());
                    this.skills.put(key, skill);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized SkillEntry saveSkill(String name, String description, String code, String tags) {
        if (name == null || name.trim().isEmpty() || code == null || code.trim().isEmpty()) return null;
        String key = name.trim().toLowerCase().replaceAll("[^a-z0-9_]+", "_");
        SkillEntry existing = this.skills.get(key);
        SkillEntry s = existing != null ? existing : new SkillEntry();
        s.setName(key);
        s.setDescription(description != null ? description.trim() : "");
        s.setCode(code.trim());
        s.setTags(tags != null ? tags.trim() : "");
        s.setUses((existing != null ? existing.getUses() + 1 : 1));
        s.setUpdatedAt(System.currentTimeMillis());
        this.skills.put(key, s);
        saveToDisk();
        return s;
    }

    public synchronized void recordSelfCorrectedSkill(String userPrompt, String thought, String workingCode) {
        if (userPrompt == null || workingCode == null || workingCode.length() < 15 || workingCode.length() > 1400) return;
        String slug = "learned_" + userPrompt.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_");
        if (slug.length() > 36) slug = slug.substring(0, 36);
        saveSkill(slug, (thought != null && !thought.isEmpty()) ? thought : userPrompt, workingCode, userPrompt.toLowerCase());
    }

    public synchronized List<SkillEntry> searchSkills(String query, int limit) {
        List<SkillEntry> all = new ArrayList<SkillEntry>(this.skills.values());
        if (query == null || query.trim().isEmpty()) {
            return all.subList(0, Math.min(limit, all.size()));
        }

        List<String> qTokens = ir.layercloud.cortexdrone.world.LexicalItemMatcher.tokenize(query);
        int numDocs = Math.max(1, all.size());
        Map<String, List<String>> docTokensMap = new LinkedHashMap<String, List<String>>();
        double totalDocLen = 0.0;

        for (SkillEntry s : all) {
            List<String> dToks = ir.layercloud.cortexdrone.world.LexicalItemMatcher.tokenize(
                s.getName() + " " + s.getName() + " " + (s.getTags() != null ? s.getTags() + " " + s.getTags() : "") + " " + s.getDescription()
            );
            docTokensMap.put(s.getName(), dToks);
            totalDocLen += dToks.size();
        }
        double avgDocLen = Math.max(1.0, totalDocLen / numDocs);

        // Okapi BM25 parameters (k1 = 1.5, b = 0.75) with Damerau-Levenshtein fuzzy term matching
        final double k1 = 1.5;
        final double b = 0.75;
        final Map<String, Double> bm25Scores = new LinkedHashMap<String, Double>();
        List<SkillEntry> matched = new ArrayList<SkillEntry>();

        for (String qt : qTokens) {
            if (qt.length() < 3) continue;
            int docFreq = 0;
            for (SkillEntry s : all) {
                if (ir.layercloud.cortexdrone.world.LexicalItemMatcher.tokenListContains(docTokensMap.get(s.getName()), qt)) {
                    docFreq++;
                }
            }
            if (docFreq == 0) continue;
            double idf = Math.log(1.0 + (numDocs - docFreq + 0.5) / (docFreq + 0.5));

            for (SkillEntry s : all) {
                List<String> dToks = docTokensMap.get(s.getName());
                int tf = 0;
                for (String dt : dToks) {
                    if (ir.layercloud.cortexdrone.world.LexicalItemMatcher.fuzzyTokenMatches(dt, qt)) {
                        tf++;
                    }
                }
                if (tf > 0) {
                    double docLen = dToks.size();
                    double termScore = idf * ((tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * (docLen / avgDocLen))));
                    Double prev = bm25Scores.get(s.getName());
                    bm25Scores.put(s.getName(), (prev != null ? prev.doubleValue() : 0.0) + termScore);
                }
            }
        }

        for (SkillEntry s : all) {
            if (bm25Scores.containsKey(s.getName())) {
                matched.add(s);
            }
        }
        Collections.sort(matched, new Comparator<SkillEntry>() {
            @Override
            public int compare(SkillEntry a, SkillEntry b) {
                double sa = bm25Scores.containsKey(a.getName()) ? bm25Scores.get(a.getName()) : 0.0;
                double sb = bm25Scores.containsKey(b.getName()) ? bm25Scores.get(b.getName()) : 0.0;
                int cmp = Double.compare(sb, sa);
                if (cmp != 0) return cmp;
                return Long.compare(b.getUpdatedAt(), a.getUpdatedAt());
            }
        });
        return matched.subList(0, Math.min(Math.max(1, limit), matched.size()));
    }

    public synchronized JsonArray toJsonArray(List<SkillEntry> list) {
        JsonArray arr = new JsonArray();
        for (SkillEntry s : list) {
            JsonObject o = new JsonObject();
            o.addProperty("name", s.getName());
            o.addProperty("description", s.getDescription());
            o.addProperty("code", s.getCode());
            o.addProperty("tags", s.getTags());
            o.addProperty("uses", s.getUses());
            arr.add(o);
        }
        return arr;
    }

    private synchronized void loadFromDisk() {
        if (!this.storageFile.exists()) return;
        try (FileReader reader = new FileReader(this.storageFile)) {
            JsonElement el = new JsonParser().parse(reader);
            if (el != null && el.isJsonArray()) {
                for (JsonElement item : el.getAsJsonArray()) {
                    if (item.isJsonObject()) {
                        SkillEntry s = CortexDroneMod.GSON.fromJson(item, SkillEntry.class);
                        if (s != null && s.getName() != null) {
                            this.skills.put(s.getName(), s);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private synchronized void saveToDisk() {
        try (FileWriter writer = new FileWriter(this.storageFile)) {
            CortexDroneMod.GSON.toJson(new ArrayList<SkillEntry>(this.skills.values()), writer);
        } catch (Exception ignored) {
        }
    }
}
