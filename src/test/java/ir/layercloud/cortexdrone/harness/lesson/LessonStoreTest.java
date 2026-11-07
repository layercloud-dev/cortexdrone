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
