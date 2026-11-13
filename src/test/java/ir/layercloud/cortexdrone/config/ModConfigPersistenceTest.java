package ir.layercloud.cortexdrone.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Config persistence must not lose the whole file on a crash, and a
 * hand-edited file must not crash the reconnector.
 */
public class ModConfigPersistenceTest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Test
    public void saveWritesReadableJsonAndLeavesNoTempFile() throws Exception {
        File dir = Files.createTempDirectory("cortexdrone-config-save").toFile();
        File file = new File(dir, "config.json");

        ModConfig config = new ModConfig();
        config.setApiUrl("http://127.0.0.1:7002/v1/chat/completions");
        config.setModel("test/model");
        config.setApiKey("secret");
        config.setMaxTurns(17);
        config.save(file, GSON, null, null);

        assertTrue("config.json must exist", file.isFile());
        assertFalse("the atomic temp file must not survive",
                new File(file.getAbsolutePath() + ".tmp").exists());

        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue("written file must be valid JSON",
                new JsonParser().parse(text).isJsonObject());

        ModConfig loaded = ModConfig.load(file, GSON);
        assertEquals("test/model", loaded.getModel());
        assertEquals(17, loaded.getMaxTurns());
        assertEquals("secret", loaded.getApiKey());
    }

    @Test
    public void savingTwiceReplacesThePreviousFile() throws Exception {
        File dir = Files.createTempDirectory("cortexdrone-config-replace").toFile();
        File file = new File(dir, "config.json");

        ModConfig first = new ModConfig();
        first.setMaxTurns(12);
        first.save(file, GSON, null, null);

        ModConfig second = new ModConfig();
        second.setMaxTurns(33);
        second.save(file, GSON, null, null);

        assertEquals(33, ModConfig.load(file, GSON).getMaxTurns());
        File[] leftovers = dir.listFiles((parent, name) -> name.endsWith(".tmp"));
        assertNotNull(leftovers);
        assertEquals("no temp files may be left behind", 0, leftovers.length);
    }

    @Test
    public void nanTemperatureFallsBackToTheDefault() {
        ModConfig config = new ModConfig();
        config.setTemperature(Double.NaN);
        assertEquals(ModConfig.DEFAULT_TEMPERATURE, config.sanitized().getTemperature(), 0.0001);
    }

    @Test
    public void nullSavedDroneEntriesAreDropped() {
        ModConfig config = new ModConfig();
        config.getSavedDrones().add(null);
        ModConfig.SavedDroneInfo good = new ModConfig.SavedDroneInfo();
        good.setUuid("11111111-1111-1111-1111-111111111111");
        good.setOwner("steve");
        config.getSavedDrones().add(good);
        ModConfig.SavedDroneInfo noUuid = new ModConfig.SavedDroneInfo();
        noUuid.setOwner("alex");
        config.getSavedDrones().add(noUuid);

        ModConfig fixed = config.sanitized();
        assertEquals("only the binding with a uuid survives", 1, fixed.getSavedDrones().size());
        assertEquals(good.getUuid(), fixed.getSavedDrones().get(0).getUuid());
    }

    @Test
    public void corruptFileFallsBackToDefaultsWithoutThrowing() throws Exception {
        File dir = Files.createTempDirectory("cortexdrone-config-corrupt").toFile();
        File file = new File(dir, "config.json");
        Files.write(file.toPath(), "{ this is not json".getBytes(StandardCharsets.UTF_8));

        ModConfig loaded = ModConfig.load(file, GSON);
        assertNotNull(loaded);
        assertEquals(ModConfig.DEFAULT_MAX_TURNS, loaded.getMaxTurns());
        assertFalse(loaded.getModel().trim().isEmpty());
        assertTrue("the corrupt file is left for the operator to inspect", file.isFile());
    }
}
