package ir.layercloud.cortexdrone.config;

import com.google.gson.Gson;
import ir.layercloud.cortexdrone.CortexDroneMod;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One-way migration from legacy world-save files to the mod config directory.
 *
 * <p>Legacy files are imported only when the new file does not exist, then
 * renamed with a {@code .migrated} suffix so the migration never runs twice and
 * the old data is not silently deleted.</p>
 */
public final class ConfigMigrator {
    private static final Logger LOG = Logger.getLogger("CortexDrone");

    private ConfigMigrator() {
    }

    public static void migrate(File worldDir, File configRoot) {
        ModPaths.ensure(configRoot);
        File newConfig = ModPaths.configFile(configRoot);
        File newSkills = ModPaths.skillsFile(configRoot);
        File world = worldDir != null ? worldDir : new File("world");

        File oldConfig = new File(world, "layercloud-ai.json");
        if (oldConfig.isFile() && !newConfig.isFile()) {
            try (Reader reader = new FileReader(oldConfig)) {
                ModConfig config = CortexDroneMod.GSON.fromJson(reader, ModConfig.class);
                if (config != null) {
                    config.sanitized();
                    try (Writer writer = new FileWriter(newConfig)) {
                        CortexDroneMod.GSON.toJson(config, writer);
                    }
                    LOG.info("Migrated legacy AI config to " + newConfig.getAbsolutePath());
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Could not migrate " + oldConfig.getAbsolutePath(), e);
            }
            renameLegacy(oldConfig);
        }

        File oldSkills = new File(world, "layercloud-drone-skills-v2.json");
        if (oldSkills.isFile() && !newSkills.isFile()) {
            try {
                copy(oldSkills, newSkills);
                LOG.info("Migrated legacy skills to " + newSkills.getAbsolutePath());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Could not migrate " + oldSkills.getAbsolutePath(), e);
            }
            renameLegacy(oldSkills);
        }
    }

    private static void renameLegacy(File file) {
        File target = new File(file.getAbsolutePath() + ".migrated");
        if (target.exists() && !target.delete()) return;
        if (!file.renameTo(target)) {
            LOG.warning("Could not rename legacy file " + file.getAbsolutePath());
        }
    }

    private static void copy(File source, File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create " + parent.getAbsolutePath());
        }
        try (Reader reader = new FileReader(source); Writer writer = new FileWriter(target)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                writer.write(buffer, 0, read);
            }
        }
    }

    /** Kept for callers that already hold a Gson instance and want a typed import. */
    public static ModConfig importLegacy(File legacyFile, Gson gson) {
        if (legacyFile == null || !legacyFile.isFile()) return null;
        try (Reader reader = new FileReader(legacyFile)) {
            ModConfig config = gson.fromJson(reader, ModConfig.class);
            return config != null ? config.sanitized() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
