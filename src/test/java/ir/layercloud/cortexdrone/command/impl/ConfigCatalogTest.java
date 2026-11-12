package ir.layercloud.cortexdrone.command.impl;

import ir.layercloud.cortexdrone.SourceTree;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.service.navigation.DroneSpeed;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code /drone config} must be able to edit every field in {@code config.json}.
 *
 * <p>The catalog is the command's only key table. The reflection test fails the
 * build when {@link ModConfig} grows a property that the command cannot set,
 * which is exactly how the command fell behind the file before.</p>
 */
public class ConfigCatalogTest {
    @Test
    public void everyConfigFieldIsEditable() {
        Set<String> covered = new HashSet<>();
        for (ConfigCatalog.Field field : ConfigCatalog.all()) {
            covered.add(field.key());
        }
        for (Field field : ModConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (field.isSynthetic()) continue;
            assertTrue("config.json field '" + field.getName()
                            + "' has no /drone config key",
                    covered.contains(field.getName()));
        }
    }

    @Test
    public void aliasesResolveToTheSameField() {
        assertSame(ConfigCatalog.find("apiUrl"), ConfigCatalog.find("url"));
        assertSame(ConfigCatalog.find("apiUrl"), ConfigCatalog.find("ENDPOINT"));
        assertSame(ConfigCatalog.find("maxTurns"), ConfigCatalog.find("turns"));
        assertSame(ConfigCatalog.find("droneSpeedMultiplier"), ConfigCatalog.find("speed"));
        assertSame(ConfigCatalog.find("savedDrones"), ConfigCatalog.find("drones"));
        assertSame(ConfigCatalog.find("apiKey"), ConfigCatalog.find("key"));
    }

    @Test
    public void integersAreParsedClampedAndRejectFractions() {
        ModConfig config = new ModConfig();
        ConfigCatalog.Field turns = ConfigCatalog.find("turns");
        assertEquals("42", turns.apply(config, "42"));
        assertEquals(42, config.getMaxTurns());

        assertEquals(String.valueOf(ModConfig.MAX_MAX_TURNS), turns.apply(config, "90000"));
        assertEquals(ModConfig.MAX_MAX_TURNS, config.getMaxTurns());
        assertEquals(String.valueOf(ModConfig.MIN_MAX_TURNS), turns.apply(config, "0"));
        assertEquals(ModConfig.MIN_MAX_TURNS, config.getMaxTurns());

        assertInvalid(turns, config, "2.5");
        assertInvalid(turns, config, "sixty");
        assertInvalid(turns, config, "");
    }

    @Test
    public void doublesAreClampedIntoTheirRange() {
        ModConfig config = new ModConfig();
        ConfigCatalog.Field temperature = ConfigCatalog.find("temperature");
        assertEquals("2", temperature.apply(config, "9"));
        assertEquals(ModConfig.MAX_TEMPERATURE, config.getTemperature(), 0.0001);

        ConfigCatalog.Field speed = ConfigCatalog.find("speed");
        assertEquals(String.valueOf((long) DroneSpeed.MAX_MULTIPLIER), speed.apply(config, "1000"));
        assertEquals(DroneSpeed.MAX_MULTIPLIER, config.getDroneSpeedMultiplier(), 0.0001);
        assertInvalid(speed, config, "fast");
    }

    @Test
    public void booleansAcceptChatSpellings() {
        ModConfig config = new ModConfig();
        ConfigCatalog.Field reflection = ConfigCatalog.find("reflection");
        assertEquals("false", reflection.apply(config, "off"));
        assertFalse(config.isAllowJavaReflection());
        assertEquals("true", reflection.apply(config, "yes"));
        assertTrue(config.isAllowJavaReflection());
        assertInvalid(reflection, config, "maybe");
    }

    @Test
    public void secretsAreClearedNotShown() {
        ModConfig config = new ModConfig();
        ConfigCatalog.Field key = ConfigCatalog.find("key");
        assertEquals("sk-a...op", key.apply(config, "sk-abcdefghijklmnop"));
        assertEquals("sk-abcdefghijklmnop", config.getApiKey());
        assertEquals("<not set>", key.apply(config, "clear"));
        assertEquals("", config.getApiKey());
    }

    @Test
    public void resetUsesTheDeclaredDefaultOfEachField() {
        ModConfig config = new ModConfig();
        config.setMaxTurns(7);
        config.setTemperature(1.5D);
        config.setWebSearchEnabled(false);

        for (String key : new String[]{"maxTurns", "temperature", "webSearchEnabled"}) {
            ConfigCatalog.Field field = ConfigCatalog.find(key);
            field.apply(config, field.defaultValue());
        }
        assertEquals(ModConfig.DEFAULT_MAX_TURNS, config.getMaxTurns());
        assertEquals(ModConfig.DEFAULT_TEMPERATURE, config.getTemperature(), 0.0001);
        assertTrue(config.isWebSearchEnabled());
    }

    @Test
    public void droneListOnlySupportsClear() {
        ModConfig config = new ModConfig();
        ModConfig.SavedDroneInfo info = new ModConfig.SavedDroneInfo();
        info.setUuid("u");
        config.getSavedDrones().add(info);

        ConfigCatalog.Field drones = ConfigCatalog.find("drones");
        assertNotNull(drones);
        assertEquals("cleared", drones.apply(config, "clear"));
        assertTrue(config.getSavedDrones().isEmpty());
        assertInvalid(drones, config, "add");
    }

    @Test
    public void keysListMentionsEveryField() {
        String keys = ConfigCatalog.keys();
        for (ConfigCatalog.Field field : ConfigCatalog.all()) {
            assertTrue("keys() must list " + field.key(), keys.contains(field.key()));
        }
    }

    @Test
    public void chatKeysCoverEveryFieldAndFeedTabCompletion() {
        java.util.List<String> chat = ConfigCatalog.chatKeys();
        assertEquals("one chat key per field", ConfigCatalog.all().size(), chat.size());
        for (ConfigCatalog.Field field : ConfigCatalog.all()) {
            String chatKey = field.chatKey();
            assertTrue("chat key '" + chatKey + "' must resolve to its field",
                    ConfigCatalog.find(chatKey) == field);
        }
        String command = SourceTree.read(SourceTree.MAIN + "command/impl/DroneCommand.java");
        assertTrue("tab completion must come from the catalog",
                command.contains("ConfigCatalog.chatKeys()"));
        assertTrue("reload must be offered", command.contains("\"reload\""));
        assertTrue("save must be offered", command.contains("\"save\""));
    }

    @Test
    public void emptyTextIsRejectedButClearUnsetsSecrets() {
        ModConfig config = new ModConfig();
        ConfigCatalog.Field url = ConfigCatalog.find("url");
        assertInvalid(url, config, " ");
        assertEquals(ModConfig.DEFAULT_API_URL, config.getApiUrl());
    }

    private static void assertInvalid(ConfigCatalog.Field field, ModConfig config, String value) {
        try {
            field.apply(config, value);
            fail(field.key() + " accepted '" + value + "'");
        } catch (IllegalArgumentException expected) {
            // expected: chat-ready reason
            assertFalse(expected.getMessage().isEmpty());
        }
    }
}
