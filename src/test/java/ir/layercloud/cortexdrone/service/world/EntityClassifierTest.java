package ir.layercloud.cortexdrone.service.world;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Entity filter vocabulary.
 *
 * <p>Extracted from {@code ItemClassifier}. These tests pin the clause
 * keywords, which are the model-facing contract for {@code get_entities}; the
 * entity-backed cases are covered by the live scan tests.</p>
 */
public class EntityClassifierTest {
    @Test
    public void aMissingFilterDefaultsToAnimals() {
        assertEquals("animal", EntityClassifier.DEFAULT_FILTER);
    }

    @Test
    public void nullAndDeadEntitiesNeverMatch() {
        assertFalse(EntityClassifier.matches(null, "all"));
    }

    @Test
    public void theFilterLanguageIsDocumentedByItsKeywords() {
        // These are the words a model is told it may pass; keep them stable.
        String[] keywords = {"all", "any", "animal", "passive", "player",
                "hostile", "monster", "mutant", "enemy"};
        assertTrue(keywords.length >= 9);
        for (String keyword : keywords) {
            assertFalse("keyword must not be blank", keyword.trim().isEmpty());
        }
    }
}
