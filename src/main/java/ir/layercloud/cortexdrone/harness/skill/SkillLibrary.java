package ir.layercloud.cortexdrone.harness.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.config.ModPaths;
import ir.layercloud.cortexdrone.harness.markdown.AtomicFiles;
import ir.layercloud.cortexdrone.harness.markdown.WriteStatus;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
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
 * normalized name. Only the directory format is supported; the retired
 * single-file {@code skills.json} is not read. The library never decides when to
 * use a skill; it only stores, searches, versions and composes them.</p>
 */
@SuppressWarnings("unused")
public final class SkillLibrary {
    private static final Logger LOG = Logger.getLogger("CortexDrone");
    private static final int MAX_SKILL_CODE_CHARS = 16_000;
    private static final int MAX_DOCS_CHARS = 4_000;

    private final File skillsDir;
    private final File disabledDir;
    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final SkillRetirement retirement;

    public SkillLibrary(File configRoot) {
        this.skillsDir = ModPaths.skillsDir(configRoot);
        this.disabledDir = ModPaths.disabledSkillsDir(configRoot);
        this.retirement = new SkillRetirement(this.skillsDir, this.disabledDir);
        loadBundled();
        loadLearned();
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
        if (!SkillCodeGuard.isClean(code)) return null;
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
        Skill previous = this.skills.put(key, skill);
        if (!persist(skill)) {
            // Do not report a skill the disk refused: restore the previous
            // in-memory version (or drop the new one) and let the caller error.
            if (previous != null) {
                this.skills.put(key, previous);
            } else {
                this.skills.remove(key);
            }
            return null;
        }
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
        // Only reload when a bundled skill with that name actually exists;
        // otherwise deleting a learned-only skill logged a misleading
        // "bundled skill has no SKILL.md" warning on every delete.
        if (hadOverride && bundledResource(key) != null) loadBundledByName(key);
        return true;
    }

    /**
     * Retires a learned skill without destroying it: its directory is moved to
     * {@code skills-disabled/} and the bundled skill (when one exists with the
     * same name) becomes active again.
     *
     * @return false when the name is a bundled skill or no learned directory exists
     */
    public synchronized boolean disable(String name) {
        String key = normalize(name);
        if (!this.retirement.retire(key)) return false;
        this.skills.remove(key);
        if (bundledResource(key) != null) loadBundledByName(key);
        return true;
    }

    /**
     * Returns a retired learned skill to the active library.
     *
     * @return false when no disabled directory exists for that name
     */
    public synchronized boolean enable(String name) {
        String key = normalize(name);
        File restored = this.retirement.restore(key);
        if (restored == null) return false;
        loadLearnedSkill(restored);
        return this.skills.containsKey(key);
    }

    /**
     * Names of retired skills, sorted. Diagnostics only.
     */
    public synchronized List<String> disabledNames() {
        return this.retirement.names();
    }

    /**
     * Names that are missing from the library but required by the given skill.
     */
    public synchronized List<String> missingRequires(String name) {        List<String> missing = new ArrayList<>();
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
                for (String trigger : skill.getTriggers()) triggers.add(new JsonPrimitive(trigger));
            }
            object.add("triggers", triggers);
            JsonArray requires = new JsonArray();
            if (skill.getRequires() != null) {
                for (String dependency : skill.getRequires())
                    requires.add(new JsonPrimitive(dependency));
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
                loadBundledByName(item.getAsString().trim());
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
        // The directory is authoritative for a learned skill: persist(), disable
        // and delete all address it by name, so a frontmatter name that differs
        // from the directory (hand-edited, or a decorated title) used to create a
        // duplicate directory on the next recordUse/refine and made disable fail.
        String fromDirectory = normalize(dir.getName());
        String declared = skill.getName() != null ? normalize(skill.getName()) : "";
        skill.setName(fromDirectory.isEmpty() ? declared : fromDirectory);
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
        this.skills.put(skill.getName(), skill);
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private boolean persist(Skill skill) {
        WriteStatus.clear();
        File dir = new File(this.skillsDir, skill.getName());
        if (!dir.isDirectory() && !dir.mkdirs()) {
            LOG.warning("Could not create skill directory " + dir.getAbsolutePath());
            WriteStatus.failed(WriteStatus.reason("skill '" + skill.getName() + "'", dir.getAbsolutePath()));
            return false;
        }
        if (!writeFile(new File(dir, SkillMarkdown.FILE_NAME), SkillMarkdown.write(skill))) {
            return false;
        }
        return writeFile(new File(dir, SkillMarkdown.DEFAULT_CODE_FILE), skill.getCode());
    }

    private static boolean writeFile(File target, String content) {
        if (AtomicFiles.writeText(target, content)) return true;
        LOG.warning("Could not write " + target.getAbsolutePath());
        WriteStatus.failed(WriteStatus.reason("skill file", target.getAbsolutePath()));
        return false;
    }

    private static boolean ensureDirectory(File directory) {
        return directory != null && (directory.isDirectory() || directory.mkdirs());
    }

    private static void deleteRecursively(File file) {
        if (!AtomicFiles.deleteRecursively(file)) {
            LOG.warning("Could not delete " + file.getAbsolutePath());
        }
    }

    // ------------------------------------------------------------------
    // Resources
    // ------------------------------------------------------------------

    private static String readResource(String path) {
        InputStream stream = SkillLibrary.class.getResourceAsStream(path);
        if (stream == null) return null;        try (InputStream in = stream) {
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
        return AtomicFiles.readText(file);
    }

    /**
     * The bundled SKILL.md resource for a normalized name, or {@code null}.
     */
    private static java.net.URL bundledResource(String key) {
        if (key == null || key.isEmpty()) return null;
        return SkillLibrary.class.getResource("/skills/" + key + "/" + SkillMarkdown.FILE_NAME);
    }

    private static boolean safeFileName(String name) {
        return name != null && !name.isEmpty()
                && !name.startsWith("/") && !name.startsWith("\\")
                && !name.contains("..") && !name.contains("\\");
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
