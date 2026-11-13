package ir.layercloud.cortexdrone.harness.markdown;

import ir.layercloud.cortexdrone.harness.lesson.LessonStore;
import ir.layercloud.cortexdrone.harness.memory.MemoryStore;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A store that cannot write its file must not return the entry.
 *
 * <p>The refusal is simulated without permissions: the config/world root is a
 * regular file, so no store directory can be created beneath it.</p>
 */
public class StorePersistenceFailureTest {
    private static File unwritableRoot(String prefix) throws Exception {
        File dir = Files.createTempDirectory(prefix).toFile();
        File root = new File(dir, "root-is-a-file");
        assertTrue(root.createNewFile());
        return root;
    }

    @Test
    public void aMemoryThatCannotBeWrittenIsNotReportedAsSaved() throws Exception {
        File root = unwritableRoot("cortexdrone-memory-refused");
        MemoryStore store = new MemoryStore(root);

        WriteStatus.clear();
        assertNull("a failed write must not return an entry",
                store.remember("fact", "wood", "likes oak", false, 0, 0, 0, 0,
                        null, false, "test"));
        assertNotNull("the failure reason must reach the tool layer",
                WriteStatus.lastFailure());
        assertEquals("the in-memory store must not keep it", 0, store.count());
    }

    @Test
    public void aLessonThatCannotBeWrittenIsNotReportedAsSaved() throws Exception {
        File root = unwritableRoot("cortexdrone-lesson-refused");
        LessonStore store = new LessonStore(root);

        WriteStatus.clear();
        assertNull(store.add("mining", "keep a floor", "rule", null, "failure", false, "test"));
        assertNotNull(WriteStatus.lastFailure());
        assertEquals(0, store.count());
    }

    @Test
    public void aSkillThatCannotBeWrittenIsNotReportedAsSaved() throws Exception {
        File root = unwritableRoot("cortexdrone-skill-refused");
        SkillLibrary library = new SkillLibrary(root);

        WriteStatus.clear();
        assertNull(library.save("refused_skill", "cannot write", "test", null,
                "return 1;", "saved"));
        assertNotNull(WriteStatus.lastFailure());
        assertNull("a refused skill must not be in the library",
                library.get("refused_skill"));
    }
}
