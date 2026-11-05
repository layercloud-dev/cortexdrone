package ir.layercloud.cortexdrone.codemode;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ir.layercloud.cortexdrone.CortexDroneMod;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SkillLibrary {
    public static class SkillEntry {
        public String name;
        public String description;
        public String code;
        public String tags;
        public int uses;
        public long updatedAt;
    }

    private final File storageFile;
    private final Map<String, SkillEntry> skills = new LinkedHashMap<String, SkillEntry>();

    public SkillLibrary(File worldDir) {
        File dir = worldDir != null ? worldDir : new File(".");
        this.storageFile = new File(dir, "layercloud-drone-skills.json");
        loadFromDisk();
        seedDefaultsIfMissing();
    }

    private synchronized void seedDefaultsIfMissing() {
        boolean changed = false;
        if (!this.skills.containsKey("hunt_cook_deposit")) {
            putInternal(
                "hunt_cook_deposit",
                "Hunt animals for raw meat, cook all meat in the nearest furnace, and deposit into a target chest or deliver to player",
                "await drone.hunt('cow|pig|sheep|chicken', 32);\nawait drone.cook('meat', 64);\nawait drone.depositChest();",
                "hunt cook meat beef porkchop mutton furnace chest food"
            );
            changed = true;
        }
        if (!this.skills.containsKey("crate_to_chest_sort")) {
            putInternal(
                "crate_to_chest_sort",
                "Transfer all items from crates into chests, auto-craft/place extra chests if full, and sort all chests by category",
                "return await drone.transferContainers('crate', 'chest', 32, true);",
                "crate chest transfer move sort storage"
            );
            changed = true;
        }
        if (!this.skills.containsKey("craft_place_double_chest")) {
            putInternal(
                "craft_place_double_chest",
                "Craft vanilla chests from wood/planks and place adjacent pairs next to the player to form double chests",
                "const p = await player.get();\nawait drone.craft('planks', 16);\nawait drone.craft('chest', 2);\nawait drone.placeBlock(p.blockX + 1, p.blockY, p.blockZ, 'minecraft:chest');\nawait drone.placeBlock(p.blockX + 1, p.blockY, p.blockZ + 1, 'minecraft:chest');",
                "craft place double chest planks wood"
            );
            changed = true;
        }
        if (!this.skills.containsKey("escape_cave_and_return")) {
            putInternal(
                "escape_cave_and_return",
                "Retrace session 3D breadcrumbs or auto-dig out of an underground cave/tunnel and return to the player",
                "await drone.escapeCave();\nawait drone.come();",
                "stuck cave underground tunnel escape backtrack breadcrumb dig"
            );
            changed = true;
        }
        if (!this.skills.containsKey("rainbow_dance_show")) {
            putInternal(
                "rainbow_dance_show",
                "Cycle rainbow LED colors in a loop while performing a choreographed dance, figure-8 flight, musical notes, and particle effects",
                "await drone.rainbow(3000, 80, true);\nawait drone.dance(5000);\nawait drone.fireworks(3);",
                "rainbow led color dance disco party spin flip trick fun music"
            );
            changed = true;
        }
        if (!this.skills.containsKey("build_house_or_cabin")) {
            putInternal(
                "build_house_or_cabin",
                "Automatically locate a flat build site near the player and construct a complete house/cabin with foundation, floor, corners, walls, doorway, windows, and roof",
                "const site = await world.findBuildSite(5, 5, 22);\nawait drone.mineRect(site.x1, site.floorY + 1, site.z1, site.x2, site.floorY + 5, site.z2, 'all,!air');\nawait drone.buildStructure({ x1: site.x1, z1: site.z1, x2: site.x2, z2: site.z2, floorY: site.floorY, height: 3, floor: 'cobblestone', walls: 'cobblestone', corners: 'oak_log', roof: 'planks', doorSide: site.doorSide, windows: true });",
                "build house cabin shelter home room cobblestone wood planks roof"
            );
            changed = true;
        }
        if (changed) {
            saveToDisk();
        }
    }

    private void putInternal(String name, String description, String code, String tags) {
        String key = name.trim().toLowerCase().replace(' ', '_');
        SkillEntry s = new SkillEntry();
        s.name = key;
        s.description = description != null ? description.trim() : "";
        s.code = code != null ? code.trim() : "";
        s.tags = tags != null ? tags.trim() : "";
        s.uses = 1;
        s.updatedAt = System.currentTimeMillis();
        this.skills.put(key, s);
    }

    public synchronized SkillEntry saveSkill(String name, String description, String code, String tags) {
        if (name == null || name.trim().isEmpty() || code == null || code.trim().isEmpty()) return null;
        String key = name.trim().toLowerCase().replaceAll("[^a-z0-9_]+", "_");
        SkillEntry existing = this.skills.get(key);
        SkillEntry s = existing != null ? existing : new SkillEntry();
        s.name = key;
        s.description = description != null ? description.trim() : "";
        s.code = code.trim();
        s.tags = tags != null ? tags.trim() : "";
        s.uses = (existing != null ? existing.uses + 1 : 1);
        s.updatedAt = System.currentTimeMillis();
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
                s.name + " " + s.name + " " + (s.tags != null ? s.tags + " " + s.tags : "") + " " + s.description
            );
            docTokensMap.put(s.name, dToks);
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
                if (ir.layercloud.cortexdrone.world.LexicalItemMatcher.tokenListContains(docTokensMap.get(s.name), qt)) {
                    docFreq++;
                }
            }
            if (docFreq == 0) continue;
            double idf = Math.log(1.0 + (numDocs - docFreq + 0.5) / (docFreq + 0.5));

            for (SkillEntry s : all) {
                List<String> dToks = docTokensMap.get(s.name);
                int tf = 0;
                for (String dt : dToks) {
                    if (ir.layercloud.cortexdrone.world.LexicalItemMatcher.fuzzyTokenMatches(dt, qt)) {
                        tf++;
                    }
                }
                if (tf > 0) {
                    double docLen = dToks.size();
                    double termScore = idf * ((tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * (docLen / avgDocLen))));
                    Double prev = bm25Scores.get(s.name);
                    bm25Scores.put(s.name, (prev != null ? prev.doubleValue() : 0.0) + termScore);
                }
            }
        }

        for (SkillEntry s : all) {
            if (bm25Scores.containsKey(s.name)) {
                matched.add(s);
            }
        }
        Collections.sort(matched, new Comparator<SkillEntry>() {
            @Override
            public int compare(SkillEntry a, SkillEntry b) {
                double sa = bm25Scores.containsKey(a.name) ? bm25Scores.get(a.name) : 0.0;
                double sb = bm25Scores.containsKey(b.name) ? bm25Scores.get(b.name) : 0.0;
                int cmp = Double.compare(sb, sa);
                if (cmp != 0) return cmp;
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        return matched.subList(0, Math.min(Math.max(1, limit), matched.size()));
    }

    public synchronized JsonArray toJsonArray(List<SkillEntry> list) {
        JsonArray arr = new JsonArray();
        for (SkillEntry s : list) {
            JsonObject o = new JsonObject();
            o.addProperty("name", s.name);
            o.addProperty("description", s.description);
            o.addProperty("code", s.code);
            o.addProperty("tags", s.tags);
            o.addProperty("uses", s.uses);
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
                        if (s != null && s.name != null) {
                            this.skills.put(s.name, s);
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
