package ir.layercloud.cortexdrone.harness.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.ModPaths;

import java.io.ByteArrayOutputStream;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Skill memory: bundled workflows plus learned/refined skills.
 *
 * <p>Every skill is a directory in the normal {@code SKILL.md} layout:</p>
 *
 * <pre>
 * skills/guard/
 *   SKILL.md   YAML frontmatter (name, description, tags, triggers, requires)
 *              plus usage documentation
 *   code.js    JavaScript function body run with `params` in scope
 * </pre>
 *
 * <p>Bundled skills live under {@code resources/skills/} and are listed by
 * {@code resources/skills/index.json}. Learned/refined skills live under
 * {@code config/cortexdrone/skills/} and override a bundled skill with the same
 * normalized name. The legacy single-file {@code skills.json} is imported once
 * and archived. The library never decides when to use a skill; it only stores,
 * searches, versions and composes them.</p>
 */
public final class SkillLibrary {
    private static final Logger LOG = Logger.getLogger("CortexDrone");
    private static final int MAX_SKILL_CODE_CHARS = 16_000;
    private static final int MAX_DOCS_CHARS = 4_000;

    private final File skillsDir;
    private final File legacyFile;
    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public SkillLibrary(File configRoot) {
        this.skillsDir = ModPaths.skillsDir(configRoot);
        this.legacyFile = ModPaths.skillsFile(configRoot);
        loadBundled();
        loadLearned();
        importLegacyJson();
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
        return save(name, description, tags, triggers, null, code, source);
    }

    public synchronized Skill save(String name, String description, String tags,
                                   List<String> triggers, List<String> requires,
                                   String code, String source) {
        return save(name, description, tags, triggers, requires, null, code, source);
    }

    public synchronized Skill save(String name, String description, String tags,
                                   List<String> triggers, List<String> requires,
                                   String docs, String code, String source) {
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
        skill.setRequires(requires != null ? new ArrayList<>(requires) : new ArrayList<>());
        if (docs != null && !docs.trim().isEmpty()) skill.setDocs(docs.trim());
        skill.setCodeFile(null);
        skill.setCode(code.trim());
        skill.setSource(source != null ? source : "saved");
        skill.touch();
        this.skills.put(key, skill);
        persist(skill);
        return skill;
    }

    /**
     * Records that the model actually used a skill. Bundled skills keep the
     * counter in memory only; learned skills are rewritten so the count and
     * timestamp survive a restart.
     */
    public synchronized void recordUse(String name) {
        Skill skill = this.skills.get(normalize(name));
        if (skill == null) return;
        skill.setUses(skill.getUses() + 1);
        skill.touch();
        if (!"bundled".equalsIgnoreCase(skill.getSource())) persist(skill);
    }

    public synchronized boolean delete(String name) {
        String key = normalize(name);
        Skill removed = this.skills.remove(key);
        if (removed == null) return false;
        File dir = new File(this.skillsDir, key);
        boolean hadOverride = dir.isDirectory();
        deleteRecursively(dir);
        // Removing a learned override should reveal the bundled skill again.
        if (hadOverride) loadBundledByName(key);
        return true;
    }

    /** Names that are missing from the library but required by the given skill. */
    public synchronized List<String> missingRequires(String name) {
        List<String> missing = new ArrayList<>();
        Skill skill = this.skills.get(normalize(name));
        if (skill == null || skill.getRequires() == null) return missing;
        for (String dependency : skill.getRequires()) {
            String key = normalize(dependency);
            if (key.isEmpty()) continue;
            if (!this.skills.containsKey(key)) missing.add(key);
        }
        return missing;
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
            JsonArray requires = new JsonArray();
            if (skill.getRequires() != null) {
                for (String dependency : skill.getRequires()) requires.add(new com.google.gson.JsonPrimitive(dependency));
            }
            object.add("requires", requires);
            if (includeCode) {
                object.addProperty("code", skill.getCode());
                if (skill.getDocs() != null && !skill.getDocs().trim().isEmpty()) {
                    object.addProperty("docs", cap(skill.getDocs().trim(), MAX_DOCS_CHARS));
                }
            }
            array.add(object);
        }
        return array;
    }

    public File skillsDir() {
        return this.skillsDir;
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private synchronized void loadBundled() {
        InputStream indexStream = SkillLibrary.class.getResourceAsStream("/skills/index.json");
        if (indexStream == null) return;
        try (Reader indexReader = new InputStreamReader(indexStream, StandardCharsets.UTF_8)) {
            JsonElement index = new JsonParser().parse(indexReader);
            if (index == null || !index.isJsonArray()) return;
            for (JsonElement item : index.getAsJsonArray()) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) continue;
                String entry = item.getAsString().trim();
                // Tolerate an old index that listed "name.json" files.
                if (entry.endsWith(".json")) entry = entry.substring(0, entry.length() - 5);
                loadBundledByName(entry);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read bundled skill index", e);
        }
    }

    private void loadBundledByName(String entry) {
        String dirName = entry != null ? entry.trim() : "";
        if (dirName.isEmpty()) return;
        String meta = readResource("/skills/" + dirName + "/" + SkillMarkdown.FILE_NAME);
        if (meta == null) {
            LOG.warning("Bundled skill directory '" + dirName + "' has no " + SkillMarkdown.FILE_NAME);
            return;
        }
        Skill skill = SkillMarkdown.read(meta, "bundled");
        if (skill == null) return;
        if (skill.getName() == null || skill.getName().trim().isEmpty()) {
            skill.setName(normalize(dirName));
        } else {
            skill.setName(normalize(skill.getName()));
        }
        String codeName = skill.getCodeFile() != null ? skill.getCodeFile().trim() : "";
        if (codeName.isEmpty()) codeName = SkillMarkdown.DEFAULT_CODE_FILE;
        if (safeFileName(codeName)) {
            String code = readResource("/skills/" + dirName + "/" + codeName);
            if (code != null) skill.setCode(code);
        }
        skill.setCodeFile(null);
        skill.setSource("bundled");
        if (skill.getVersion() <= 0) skill.setVersion(1);
        if (skill.getCode().trim().isEmpty()) {
            LOG.warning("Bundled skill '" + dirName + "' has no code; skipped.");
            return;
        }
        skill.touch();
        this.skills.put(skill.getName(), skill);
    }

    private synchronized void loadLearned() {
        File[] dirs = this.skillsDir != null ? this.skillsDir.listFiles(File::isDirectory) : null;
        if (dirs == null) return;
        Arrays.sort(dirs, Comparator.comparing(File::getName));
        for (File dir : dirs) loadLearnedSkill(dir);
    }

    private void loadLearnedSkill(File dir) {
        File markdown = new File(dir, SkillMarkdown.FILE_NAME);
        if (!markdown.isFile()) return;
        String meta = readFile(markdown);
        Skill skill = SkillMarkdown.read(meta, null);
        if (skill == null) return;
        if (skill.getName() == null || skill.getName().trim().isEmpty()) {
            skill.setName(normalize(dir.getName()));
        } else {
            skill.setName(normalize(skill.getName()));
        }
        String codeName = skill.getCodeFile() != null ? skill.getCodeFile().trim() : "";
        if (codeName.isEmpty()) codeName = SkillMarkdown.DEFAULT_CODE_FILE;
        File codeFile = new File(dir, safeFileName(codeName) ? codeName : SkillMarkdown.DEFAULT_CODE_FILE);
        if (!codeFile.isFile()) {
            LOG.warning("Learned skill '" + skill.getName() + "' has no " + codeFile.getName());
            return;
        }
        skill.setCode(readFile(codeFile));
        skill.setCodeFile(null);
        if (skill.getSource() == null || skill.getSource().trim().isEmpty()) skill.setSource("saved");
        if (skill.getVersion() <= 0) skill.setVersion(1);
        long stamp = Math.max(markdown.lastModified(), codeFile.lastModified());
        if (skill.getCreatedAt() <= 0) skill.setCreatedAt(stamp);
        skill.setUpdatedAt(stamp);
        if (usesRemovedApi(skill.getCode())) {
            LOG.info("Skipping legacy skill '" + skill.getName()
                    + "': it uses a removed drone API and cannot run on the v2 harness.");
            return;
        }
        this.skills.put(skill.getName(), skill);
    }

    /**
     * Imports the old flat {@code skills.json} once, archives it, and writes a
     * SKILL.md directory per learned skill. Stale bundled snapshots are ignored.
     */
    private synchronized void importLegacyJson() {
        if (this.legacyFile == null || !this.legacyFile.isFile()) return;
        int imported = 0;
        try (Reader reader = new FileReader(this.legacyFile)) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed != null && parsed.isJsonArray()) {
                for (JsonElement element : parsed.getAsJsonArray()) {
                    if (!element.isJsonObject()) continue;
                    Skill skill = CortexDroneMod.GSON.fromJson(element, Skill.class);
                    if (skill == null || skill.getName() == null || skill.getName().trim().isEmpty()) continue;
                    skill.setName(normalize(skill.getName()));
                    if ("bundled".equalsIgnoreCase(skill.getSource())) continue;
                    if (skill.getCode() == null || skill.getCode().trim().isEmpty()) continue;
                    if (usesRemovedApi(skill.getCode())) continue;
                    boolean hasDirectory = new File(this.skillsDir, skill.getName()).isDirectory();
                    if (hasDirectory) continue;
                    if (skill.getDescription() == null) skill.setDescription("");
                    if (skill.getTags() == null) skill.setTags("");
                    if (skill.getTriggers() == null) skill.setTriggers(new ArrayList<>());
                    if (skill.getRequires() == null) skill.setRequires(new ArrayList<>());
                    if (skill.getDocs() == null || skill.getDocs().trim().isEmpty()) {
                        skill.setDocs("# " + skill.getName() + "\n\n" + skill.getDescription() + "\n");
                    }
                    skill.setCodeFile(null);
                    if (skill.getSource() == null || skill.getSource().trim().isEmpty()) {
                        skill.setSource("saved");
                    }
                    if (skill.getVersion() <= 0) skill.setVersion(1);
                    skill.touch();
                    this.skills.put(skill.getName(), skill);
                    persist(skill);
                    imported++;
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not import legacy " + this.legacyFile.getAbsolutePath(), e);
            return;
        }
        archiveLegacy();
        if (imported > 0) {
            LOG.info("Imported " + imported + " legacy skill(s) into "
                    + this.skillsDir.getAbsolutePath());
        }
    }

    private void archiveLegacy() {
        File target = new File(this.legacyFile.getAbsolutePath() + ".migrated");
        if (target.exists()) {
            target = new File(this.legacyFile.getAbsolutePath() + ".migrated-"
                    + System.currentTimeMillis());
        }
        if (!this.legacyFile.renameTo(target)) {
            LOG.warning("Could not archive legacy skills file "
                    + this.legacyFile.getAbsolutePath());
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void persist(Skill skill) {
        File dir = new File(this.skillsDir, skill.getName());
        if (!dir.isDirectory() && !dir.mkdirs()) {
            LOG.warning("Could not create skill directory " + dir.getAbsolutePath());
            return;
        }
        writeFile(new File(dir, SkillMarkdown.FILE_NAME), SkillMarkdown.write(skill));
        writeFile(new File(dir, SkillMarkdown.DEFAULT_CODE_FILE), skill.getCode());
    }

    private static void writeFile(File target, String content) {
        File temp = new File(target.getAbsolutePath() + ".tmp");
        try (Writer writer = new FileWriter(temp)) {
            writer.write(content != null ? content : "");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not write " + target.getAbsolutePath(), e);
            return;
        }
        if (target.exists() && !target.delete()) {
            LOG.warning("Could not replace " + target.getAbsolutePath());
            return;
        }
        if (!temp.renameTo(target)) {
            LOG.warning("Could not move " + temp.getAbsolutePath() + " into place");
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) LOG.warning("Could not delete " + file.getAbsolutePath());
    }

    // ------------------------------------------------------------------
    // Resources
    // ------------------------------------------------------------------

    private static String readResource(String path) {
        InputStream stream = SkillLibrary.class.getResourceAsStream(path);
        if (stream == null) return null;
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) >= 0) out.write(chunk, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readFile(File file) {
        try (Reader reader = new FileReader(file)) {
            StringBuilder out = new StringBuilder((int) Math.min(64_000L, file.length() + 32L));
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) out.append(buffer, 0, read);
            return out.toString();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read " + file.getAbsolutePath(), e);
            return null;
        }
    }

    private static boolean safeFileName(String name) {
        return name != null && !name.isEmpty()
                && !name.startsWith("/") && !name.startsWith("\\")
                && !name.contains("..") && !name.contains("\\");
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

    private static String cap(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, max) + "...(+" + (value.length() - max) + " chars)";
    }

    public static String normalize(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(Locale.ROOT).replace(' ', '_')
                .replaceAll("[^a-z0-9_]+", "_");
    }
}
