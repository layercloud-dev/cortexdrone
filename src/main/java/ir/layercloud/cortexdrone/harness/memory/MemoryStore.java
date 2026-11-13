package ir.layercloud.cortexdrone.harness.memory;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.ModPaths;
import ir.layercloud.cortexdrone.harness.markdown.AtomicFiles;
import ir.layercloud.cortexdrone.service.search.RankedSearch;
import ir.layercloud.cortexdrone.service.world.LexicalItemMatcher;

import java.io.File;
import java.io.Reader;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent player/agent memory.
 *
 * <p>Storage is {@code <world>/cortexdrone/memory.json}: memory names places
 * that exist inside one save, so it travels with that save and is dropped with
 * it. The store keeps facts, preferences and named locations. It never stores
 * transient task progress; the model decides what is worth remembering and the
 * player can always forget an entry.</p>
 */
public final class MemoryStore {
    private static final Logger LOG = Logger.getLogger("CortexDrone");
    private static final int MAX_ENTRIES = 500;

    private final File storageFile;
    private final Map<String, MemoryEntry> entries = new LinkedHashMap<>();
    private long sequence;

    public MemoryStore(File worldRoot) {
        this.storageFile = ModPaths.memoryFile(worldRoot);
        load();
    }

    public synchronized MemoryEntry remember(String kind, String name, String text,
                                             boolean hasPosition, double x, double y, double z,
                                             int dimension, List<String> tags, boolean pinned,
                                             String source) {
        String normalizedKind = normalizeKind(kind, text, hasPosition);
        String normalizedName = normalizeName(name);
        String normalizedText = text != null ? text.trim() : "";
        if (normalizedName.isEmpty()) normalizedName = normalizeName(normalizedText);
        if (normalizedText.isEmpty() && !normalizedName.isEmpty()) normalizedText = normalizedName;
        if (normalizedName.isEmpty() && normalizedText.isEmpty()) return null;

        MemoryEntry existing = findByKindAndName(normalizedKind, normalizedName);
        MemoryEntry entry = existing != null ? existing : new MemoryEntry();
        if (existing == null) {
            entry.setId("mem_" + System.currentTimeMillis() + "_" + (++this.sequence));
            entry.setCreatedAt(System.currentTimeMillis());
            entry.setVersion(1);
        } else {
            entry.setVersion(entry.getVersion() + 1);
        }
        entry.setKind(normalizedKind);
        entry.setName(normalizedName);
        entry.setText(normalizedText);
        entry.setHasPosition(hasPosition);
        if (hasPosition) {
            entry.setX(x);
            entry.setY(y);
            entry.setZ(z);
            entry.setDimension(dimension);
        }
        if (tags != null && !tags.isEmpty()) entry.setTags(limitTags(tags));
        entry.setPinned(pinned || entry.isPinned());
        entry.setSource(source != null ? source : "player");
        entry.touch();
        this.entries.put(entry.getId(), entry);
        enforceLimit();
        save();
        return entry;
    }

    /**
     * Captures a player's "remember ..." message. Explicit coordinates win over
     * the player's current position; a location without coordinates is still
     * stored as a named location so it can be filled in later.
     */
    public synchronized MemoryEntry captureFromChat(String playerName, int dimension,
                                                    double x, double y, double z, String message) {
        if (message == null || message.trim().isEmpty()) return null;
        boolean location = MemoryCapture.isLocation(message);
        int[] explicit = MemoryCapture.explicitCoordinates(message);
        Integer explicitDimension = MemoryCapture.explicitDimension(message);
        int usedDimension = explicitDimension != null ? explicitDimension : dimension;
        boolean hasPosition = explicit != null || location;
        double px = explicit != null ? explicit[0] : x;
        double py = explicit != null ? explicit[1] : y;
        double pz = explicit != null ? explicit[2] : z;
        String kind = location ? "location" : "fact";
        String name = MemoryCapture.label(message);
        String text = MemoryCapture.canonicalText(message);
        if (text.isEmpty()) text = name;
        String source = playerName != null && !playerName.isEmpty() ? "player:" + playerName : "player";
        return remember(kind, name, text, hasPosition, px, py, pz, usedDimension,
                MemoryCapture.tags(message), false, source);
    }

    public synchronized List<MemoryEntry> search(String query, String kind, int limit) {
        return RankedSearch.search(filtered(kind), query, limit, MemoryEntry::isPinned,
                MemoryEntry::getUpdatedAt, this::score);
    }

    public synchronized List<MemoryEntry> list(String kind, int limit) {
        return search("", kind, limit);
    }

    /**
     * Memories injected into the task context: relevant matches first, then
     * pinned entries (usually bases/locations), capped.
     */
    public synchronized List<MemoryEntry> forContext(String query, int limit) {
        int max = RankedSearch.clampContext(limit);
        List<MemoryEntry> out = new ArrayList<>(search(query, null, max));
        if (out.size() >= max) return out;
        for (MemoryEntry entry : this.entries.values()) {
            if (out.size() >= max) break;
            if (!entry.isPinned() || out.contains(entry)) continue;
            out.add(entry);
        }
        return out;
    }

    public synchronized boolean forget(String idOrName) {
        if (idOrName == null || idOrName.trim().isEmpty()) return false;
        String key = idOrName.trim();
        MemoryEntry direct = this.entries.get(key);
        if (direct != null) {
            this.entries.remove(key);
            save();
            return true;
        }
        String name = normalizeName(key);
        List<String> remove = new ArrayList<>();
        for (MemoryEntry entry : this.entries.values()) {
            if (entry.getName().equals(name)) remove.add(entry.getId());
        }
        for (String id : remove) this.entries.remove(id);
        if (!remove.isEmpty()) save();
        return !remove.isEmpty();
    }

    public synchronized int count() {
        return this.entries.size();
    }

    public synchronized String formatForPrompt(List<MemoryEntry> memories) {
        if (memories == null || memories.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (MemoryEntry entry : memories) {
            out.append("- [").append(entry.getKind()).append("] `").append(entry.getName()).append('`');
            if (entry.isHasPosition()) out.append(' ').append(entry.positionSummary());
            if (entry.isPinned()) out.append(" (pinned)");
            out.append(" — ").append(oneLine(entry.getText(), 240)).append('\n');
        }
        return out.toString();
    }

    private List<MemoryEntry> filtered(String kind) {
        List<MemoryEntry> out = new ArrayList<>();
        String wanted = kind != null ? kind.trim().toLowerCase(Locale.ROOT) : "";
        for (MemoryEntry entry : this.entries.values()) {
            if (wanted.isEmpty() || wanted.equals("all") || wanted.equals(entry.getKind())) out.add(entry);
        }
        return out;
    }

    private double score(MemoryEntry entry, String queryText, List<String> queryTokens) {
        double score = entry.isPinned() ? 4.0D : 0.0D;
        String name = entry.getName() != null ? entry.getName() : "";
        String text = entry.getText() != null ? entry.getText() : "";
        String haystack = (name + " " + String.join(" ", entry.getTags() != null ? entry.getTags() : Collections.emptyList())
                + " " + text).toLowerCase(Locale.ROOT);
        if (name.equals(queryText)) score += 8.0D;
        if (name.contains(queryText)) score += 4.0D;
        if (haystack.contains(queryText)) score += 2.0D;
        for (String token : queryTokens) {
            if (token.length() < 2) continue;
            if (name.contains(token)) score += 3.0D;
            else if (haystack.contains(token)) score += 1.5D;
            else {
                for (String hayToken : LexicalItemMatcher.tokenize(haystack)) {
                    if (LexicalItemMatcher.fuzzyTokenMatches(hayToken, token)) {
                        score += 0.75D;
                        break;
                    }
                }
            }
        }
        return score;
    }

    private MemoryEntry findByKindAndName(String kind, String name) {
        for (MemoryEntry entry : this.entries.values()) {
            if (entry.getKind().equals(kind) && entry.getName().equals(name)) return entry;
        }
        return null;
    }

    private void enforceLimit() {
        while (this.entries.size() > MAX_ENTRIES) {
            String oldestId = null;
            long oldest = Long.MAX_VALUE;
            for (MemoryEntry entry : this.entries.values()) {
                if (entry.isPinned()) continue;
                if (entry.getUpdatedAt() < oldest) {
                    oldest = entry.getUpdatedAt();
                    oldestId = entry.getId();
                }
            }
            if (oldestId == null) break;
            this.entries.remove(oldestId);
        }
    }

    private synchronized void load() {
        if (this.storageFile == null || !this.storageFile.isFile()) return;
        try (Reader reader = new java.io.InputStreamReader(
                new java.io.FileInputStream(this.storageFile),
                java.nio.charset.StandardCharsets.UTF_8)) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed == null || !parsed.isJsonArray()) return;
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                MemoryEntry entry = CortexDroneMod.GSON.fromJson(element, MemoryEntry.class);
                if (entry == null || entry.getId() == null || entry.getId().isEmpty()) continue;
                if (entry.getTags() == null) entry.setTags(new ArrayList<>());
                if (entry.getKind() == null) entry.setKind("fact");
                if (entry.getName() == null) entry.setName("");
                if (entry.getText() == null) entry.setText("");
                if (entry.getSource() == null) entry.setSource("player");
                if (entry.getVersion() <= 0) entry.setVersion(1);
                long now = System.currentTimeMillis();
                if (entry.getCreatedAt() <= 0) entry.setCreatedAt(now);
                if (entry.getUpdatedAt() <= 0) entry.setUpdatedAt(now);
                this.entries.put(entry.getId(), entry);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read memories " + this.storageFile.getAbsolutePath(), e);
        }
    }

    private synchronized void save() {
        if (this.storageFile == null) return;
        File parent = this.storageFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            LOG.warning("Could not create memory directory " + parent.getAbsolutePath());
            return;
        }
        String json = CortexDroneMod.GSON.toJson(new ArrayList<>(this.entries.values()));
        if (!AtomicFiles.writeText(this.storageFile, json)) {
            LOG.warning("Could not write memories to " + this.storageFile.getAbsolutePath());
        }
    }

    private static String normalizeKind(String kind, String text, boolean hasPosition) {
        if (kind != null && !kind.trim().isEmpty()) {
            String value = kind.trim().toLowerCase(Locale.ROOT);
            if (value.equals("location") || value.equals("fact") || value.equals("preference")
                    || value.equals("task") || value.equals("person")) return value;
        }
        if (hasPosition || MemoryCapture.isLocation(text)) return "location";
        return "fact";
    }

    private static String normalizeName(String value) {
        if (value == null) return "";
        String name = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 _-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (name.length() > 48) name = name.substring(0, 48).trim();
        return name;
    }

    private static List<String> limitTags(List<String> tags) {
        List<String> out = new ArrayList<>();
        for (String tag : tags) {
            if (tag == null) continue;
            String value = tag.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty() && !out.contains(value)) out.add(value);
            if (out.size() >= 16) break;
        }
        return out;
    }

    private static String oneLine(String text, int max) {
        if (text == null) return "";
        String clean = text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max - 3) + "...";
    }


}
