package ir.layercloud.cortexdrone.config;

import ir.layercloud.cortexdrone.SourceTree;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertFalse;

/**
 * World state has one home and no migration path.
 *
 * <p>Memory and protected zones live in the save. The old config-root files and
 * the one-time migration that copied them are deliberately gone: every server
 * that ran this mod is on the new layout, and a leftover migration is code that
 * has to be maintained forever. This guard keeps it deleted.</p>
 */
public class LegacyMigrationRemovalTest {
    @Test
    public void theMigrationClassesAreGone() {
        assertFalse("WorldStateMigration must stay deleted",
                new File(SourceTree.MAIN + "config/WorldStateMigration.java").exists());
        assertFalse("LegacyConfigZones must stay deleted",
                new File(SourceTree.MAIN + "config/LegacyConfigZones.java").exists());
    }

    @Test
    public void noSourceStillReadsTheLegacyLayout() {
        String source = SourceTree.readPackage(SourceTree.MAIN);
        assertFalse("no legacy migration hook may survive",
                source.contains("WorldStateMigration"));
        assertFalse("no legacy config-root zone merge may survive",
                source.contains("loadSupplemental"));
        assertFalse("no legacy config-root memory path may survive",
                source.contains("config/cortexdrone/memory.json"));
    }
}
