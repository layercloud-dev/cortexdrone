package ir.layercloud.cortexdrone.harness.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.ModPaths;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Skill memory: bundled workflows plus learned/refined skills.
 *
 * <p>Storage is {@code config/cortexdrone/skills.json} (not the world save).
 * Bundled skills are read from {@code resources/skills/} and can be overridden
 * by a saved skill with the same name. The library never decides when to use a
 * skill; it only stores, searches and versions them.</p>
 */
public final class SkillLibrary {
    private static final Logger LOG = Logger.getLogger("CortexDrone");
    private static final int MAX_SKILL_CODE_CHARS = 16_000;

    private final File storageFile;
    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public SkillLibrary(File configRoot) {
        this.storageFile = ModPaths.skillsFile(configRoot);
        loadBundled();
        loadFromDisk();
    }

    public synchronized List<Skill> all() {
        return new ArrayList<>(this.skills.values());
    }

    public synchronized Skill get(String name) {
        return this.skills.get(normalize(name));
    }

    public synchronized List<Skill> search(String query, int limit) {
        return SkillSearch.search(new ArrayList<>(this.skills.values()), query, limit);
    }

    public synchronized Skill save(String name, String description, String tags,
                                   List<String> triggers, String code, String source) {
        if (name == null || name.trim().isEmpty() || code == null || code.trim().isEmpty()) return null;
        if (code.length() > MAX_SKILL_CODE_CHARS) return null;
        String key = normalize(name);
        Skill skill = this.skills.get(key);
        if (skill == null) {
            skill = new Skill();
            skill.setName(key);
            skill.setCreatedAt(System.currentTimeMillis());
        } else {
            skill.setVersion(skill.getVersion() + 1);
        }
        skill.setDescription(description != null ? description.trim() : "");
        skill.setTags(tags != null ? tags.trim() : "");
        skill.setTriggers(triggers != null ? new ArrayList<>(triggers) : new ArrayList<>());
        skill.setCode(code.trim());
        skill.setSource(source != null ? source : "saved");
        skill.touch();
        this.skills.put(key, skill);
        saveToDisk();
        return skill;
    }

    /** Records that the model actually used a skill (retrieval ranking signal only). */
    public synchronized void recordUse(String name) {
        Skill skill = this.skills.get(normalize(name));
        if (skill == null) return;
        skill.setUses(skill.getUses() + 1);
        skill.touch();
        saveToDisk();
    }

    public synchronized boolean delete(String name) {
        Skill removed = this.skills.remove(normalize(name));
        if (removed == null) return false;
        saveToDisk();
        return true;
    }

    public synchronized JsonArray toJsonArray(List<Skill> list, boolean includeCode) {
        JsonArray array = new JsonArray();
        for (Skill skill : list) {
            JsonObject object = new JsonObject();
            object.addProperty("name", skill.getName());
            object.addProperty("description", skill.getDescription());
            object.addProperty("tags", skill.getTags());
            object.addProperty("version", skill.getVersion());
            object.addProperty("uses", skill.getUses());
            object.addProperty("source", skill.getSource());
            JsonArray triggers = new JsonArray();
            if (skill.getTriggers() != null) {
                for (String trigger : skill.getTriggers()) triggers.add(new com.google.gson.JsonPrimitive(trigger));
            }
            object.add("triggers", triggers);
            if (includeCode) object.addProperty("code", skill.getCode());
            array.add(object);
        }
        return array;
    }

    public File storageFile() {
        return this.storageFile;
    }

    private synchronized void loadBundled() {
        InputStream indexStream = SkillLibrary.class.getResourceAsStream("/skills/index.json");
        if (indexStream == null) return;
        try (Reader indexReader = new InputStreamReader(indexStream, StandardCharsets.UTF_8)) {
            JsonElement index = new JsonParser().parse(indexReader);
            if (index == null || !index.isJsonArray()) return;
            for (JsonElement item : index.getAsJsonArray()) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) continue;
                loadBundledSkill(item.getAsString());
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read bundled skill index", e);
        }
    }

    private void loadBundledSkill(String fileName) {
        InputStream stream = SkillLibrary.class.getResourceAsStream("/skills/" + fileName);
        if (stream == null) return;
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            Skill skill = CortexDroneMod.GSON.fromJson(reader, Skill.class);
            if (skill == null || skill.getName() == null || skill.getName().trim().isEmpty()) return;
            skill.setName(normalize(skill.getName()));
            skill.setSource("bundled");
            if (skill.getDescription() == null) skill.setDescription("");
            if (skill.getTags() == null) skill.setTags("");
            if (skill.getCode() == null) skill.setCode("");
            if (skill.getTriggers() == null) skill.setTriggers(new ArrayList<>());
            if (skill.getVersion() <= 0) skill.setVersion(1);
            skill.touch();
            this.skills.put(skill.getName(), skill);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read bundled skill " + fileName, e);
        }
    }

    private synchronized void loadFromDisk() {
        if (this.storageFile == null || !this.storageFile.isFile()) return;
        try (Reader reader = new FileReader(this.storageFile)) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed == null || !parsed.isJsonArray()) return;
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                Skill skill = CortexDroneMod.GSON.fromJson(element, Skill.class);
                if (skill == null || skill.getName() == null) continue;
                skill.setName(normalize(skill.getName()));
                if (skill.getDescription() == null) skill.setDescription("");
                if (skill.getTags() == null) skill.setTags("");
                if (skill.getCode() == null) skill.setCode("");
                if (skill.getTriggers() == null) skill.setTriggers(deriveTriggers(skill.getTags()));
                if (skill.getSource() == null) skill.setSource("saved");
                if (skill.getVersion() <= 0) skill.setVersion(1);
                if (usesRemovedApi(skill.getCode())) {
                    LOG.info("Skipping legacy skill '" + skill.getName()
                            + "': it uses a removed drone API and cannot run on the v2 harness.");
                    continue;
                }
                skill.touch();
                this.skills.put(skill.getName(), skill);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read skills " + this.storageFile.getAbsolutePath(), e);
        }
    }

    private synchronized void saveToDisk() {
        if (this.storageFile == null) return;
        File parent = this.storageFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            LOG.warning("Could not create skill directory " + parent.getAbsolutePath());
            return;
        }
        File temp = new File(this.storageFile.getAbsolutePath() + ".tmp");
        try (Writer writer = new FileWriter(temp)) {
            CortexDroneMod.GSON.toJson(new ArrayList<>(this.skills.values()), writer);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not write skills", e);
            return;
        }
        if (this.storageFile.exists() && !this.storageFile.delete()) {
            LOG.warning("Could not replace " + this.storageFile.getAbsolutePath());
        }
        if (!temp.renameTo(this.storageFile)) {
            LOG.warning("Could not move " + temp.getAbsolutePath() + " into place");
        }
    }

    /** Old harness methods that no longer exist; such skills must not override bundled ones. */
    private static boolean usesRemovedApi(String code) {
        if (code == null) return false;
        String[] removed = new String[]{
                "drone.inspect(", "drone.moveTo(", "drone.transfer(", "drone.visuals(",
                "drone.mine(", "drone.hunt(", "drone.farm(", "drone.buildStructure(",
                "drone.sortChests(", "drone.carry(", "drone.queue"
        };
        for (String token : removed) {
            if (code.contains(token)) return true;
        }
        return false;
    }

    private static List<String> deriveTriggers(String tags) {
        List<String> triggers = new ArrayList<>();
        if (tags == null || tags.trim().isEmpty()) return triggers;
        triggers.addAll(Arrays.asList(tags.split("[,\\s]+")));
        return triggers;
    }

    public static String normalize(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase().replace(' ', '_').replaceAll("[^a-z0-9_]+", "_");
    }
}
