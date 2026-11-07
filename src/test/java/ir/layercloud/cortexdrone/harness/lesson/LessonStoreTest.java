package ir.layercloud.cortexdrone.harness.lesson;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

public class LessonStoreTest {
    @Test
    public void addSearchAndPersist() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-lessons").toFile();
        LessonStore store = new LessonStore(root);

        Lesson lesson = store.add("mining", "keep floor when digging down",
                "Drone fell while mining down -> breaking the block below removes support -> keep a floor block below the drone.",
                java.util.Collections.singletonList("fall"), "failure", false, "test");
        assertNotNull(lesson);
        assertEquals("mining", lesson.getScope());
        assertEquals("failure", lesson.getOutcome());
        assertEquals(1, store.count());

        Lesson reloaded = new LessonStore(root).search("digging down", null, 5).get(0);
        assertEquals("keep floor when digging down", reloaded.getTitle());
        assertTrue(reloaded.getBody().contains("floor block"));
    }

    @Test
    public void sameScopeAndTitleUpdatesInPlace() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-lessons-update").toFile();
        LessonStore store = new LessonStore(root);
        Lesson first = store.add("navigation", "use standoff", "first", null, "note", false, "test");
        Lesson second = store.add("navigation", "use standoff", "second", null, "success", false, "test");

        assertEquals(1, store.count());
        assertEquals(first.getId(), second.getId());
        assertEquals(2, second.getVersion());
        assertEquals("second", second.getBody());
        assertEquals("success", second.getOutcome());
    }

    @Test
    public void differentScopesStaySeparate() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-lessons-scope").toFile();
        LessonStore store = new LessonStore(root);
        store.add("mining", "check inventory", "mining variant", null, "note", false, "test");
        store.add("building", "check inventory", "building variant", null, "note", false, "test");
        assertEquals(2, store.count());
    }

    @Test
    public void pinnedLessonsAppearInContextEvenWhenQueryMisses() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-lessons-context").toFile();
        LessonStore store = new LessonStore(root);
        store.add("survival", "never dig under yourself", "safety rule", null, "failure", true, "test");
        store.add("inventory", "withdraw buckets first", "lava", null, "note", false, "test");

        List<Lesson> context = store.forContext("build a wall", 6);
        boolean hasPinned = false;
        for (Lesson lesson : context) hasPinned |= lesson.getTitle().equals("never dig under yourself");
        assertTrue(hasPinned);
    }

    @Test
    public void lessonsAreStoredAsLessonMarkdownDirectories() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-lessons-dir").toFile();
        LessonStore store = new LessonStore(root);
        Lesson lesson = store.add("mining", "keep the floor",
                "Symptom -> cause -> rule.", java.util.Collections.singletonList("fall"),
                "failure", true, "test");
        assertNotNull(lesson);
        assertNotNull(lesson.getPath());

        File dir = new File(lesson.getPath());
        File markdown = new File(dir, LessonMarkdown.FILE_NAME);
        assertTrue("LESSON.md must exist", markdown.isFile());
        assertFalse("no single lessons.json is written", new File(root, "lessons.json").isFile());
        String text = new String(Files.readAllBytes(markdown.toPath()), "UTF-8");
        assertTrue(text.startsWith("---"));
        assertTrue(text.contains("id: \"" + lesson.getId() + "\""));
        assertTrue(text.contains("scope: \"mining\""));
        assertTrue(text.contains("pinned: true"));

        Lesson reloaded = new LessonStore(root).search("keep the floor", null, 5).get(0);
        assertEquals(lesson.getId(), reloaded.getId());
        assertTrue(reloaded.isPinned());
        assertEquals("failure", reloaded.getOutcome());
    }

    @Test
    public void legacyLessonsJsonIsImportedAndArchived() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-lessons-import").toFile();
        File legacy = new File(root, "lessons.json");
        String json = "[{\"id\":\"les_1\",\"scope\":\"navigation\","
                + "\"title\":\"use a standoff\",\"body\":\"keep distance\","
                + "\"outcome\":\"failure\",\"tags\":[\"path\"],\"version\":2}]";
        Files.write(legacy.toPath(), json.getBytes("UTF-8"));

        LessonStore store = new LessonStore(root);
        assertEquals(1, store.count());
        assertFalse(legacy.isFile());
        assertTrue(new File(root, "lessons.json.migrated").isFile());
        Lesson lesson = store.search("standoff", null, 5).get(0);
        assertEquals("les_1", lesson.getId());
        assertEquals(2, lesson.getVersion());
        assertTrue(new File(lesson.getPath(), LessonMarkdown.FILE_NAME).isFile());
    }

    @Test
    public void forgettingALessonRemovesItsDirectory() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-lessons-delete").toFile();
        LessonStore store = new LessonStore(root);
        Lesson lesson = store.add("general", "temporary rule", "delete me", null,
                "note", false, "test");
        File dir = new File(lesson.getPath());
        assertTrue(dir.isDirectory());

        assertTrue(store.forget(lesson.getId()));
        assertFalse("lesson directory must be removed", dir.exists());
        assertEquals(0, store.count());
    }

    @Test
    public void lessonMarkdownRoundTripPreservesMetadata() {
        Lesson original = new Lesson();
        original.setId("les_42");
        original.setScope("mining");
        original.setTitle("keep a floor");
        original.setBody("Symptom -> cause -> rule.\nSecond line.");
        original.setOutcome("failure");
        original.setTags(java.util.Arrays.asList("fall", "support"));
        original.setPinned(true);
        original.setUses(3);
        original.setSource("test");
        original.setVersion(2);
        original.setCreatedAt(1000L);
        original.setUpdatedAt(2000L);

        Lesson parsed = LessonMarkdown.read(LessonMarkdown.write(original));
        assertNotNull(parsed);
        assertEquals(original.getId(), parsed.getId());
        assertEquals(original.getScope(), parsed.getScope());
        assertEquals(original.getTitle(), parsed.getTitle());
        assertEquals(original.getBody(), parsed.getBody());
        assertEquals(original.getOutcome(), parsed.getOutcome());
        assertEquals(original.getTags(), parsed.getTags());
        assertEquals(original.isPinned(), parsed.isPinned());
        assertEquals(original.getUses(), parsed.getUses());
        assertEquals(original.getSource(), parsed.getSource());
        assertEquals(original.getVersion(), parsed.getVersion());
        assertEquals(original.getCreatedAt(), parsed.getCreatedAt());
        assertEquals(original.getUpdatedAt(), parsed.getUpdatedAt());
    }

    @Test
    public void forgetByIdOrTitleAndRecordUse() throws Exception {
        File root = Files.createTempDirectory("cortexdrone-lessons-forget").toFile();
        LessonStore store = new LessonStore(root);
        store.add("general", "read the block first", "inspect before breaking", null, "note", false, "test");
        store.recordUse("read the block first");
        assertEquals(1, store.search("read the block", null, 5).get(0).getUses());

        assertTrue(store.forget("read the block first"));
        assertEquals(0, store.count());
        assertEquals(0, new LessonStore(root).count());
    }
}
