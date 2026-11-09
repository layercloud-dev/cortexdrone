package ir.layercloud.cortexdrone.config;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * A hand-edited or migrated config must fall back to the same defaults the
 * compiled field initialisers use.
 */
public class ModConfigDefaultsTest {
    @Test
    public void blankConfigIsRepairedToTheDeclaredDefaults() {
        ModConfig config = new ModConfig();
        config.setApiUrl("   ");
        config.setModel("");
        config.setMaxTurns(0);
        config.setTaskDeadlineSeconds(-1);
        config.setMaxTokens(0);
        config.setTemperature(9.0D);
        config.setExecuteTimeoutMs(0);
        config.setMaxToolResultChars(0);
        config.setMaxContextChars(0);

        ModConfig fixed = config.sanitized();

        assertEquals(ModConfig.DEFAULT_MAX_TURNS, fixed.getMaxTurns());
        assertEquals(ModConfig.DEFAULT_TASK_DEADLINE_SECONDS, fixed.getTaskDeadlineSeconds());
        assertEquals(ModConfig.DEFAULT_MAX_TOKENS, fixed.getMaxTokens());
        assertEquals(ModConfig.DEFAULT_TEMPERATURE, fixed.getTemperature(), 0.0001);
        assertEquals(ModConfig.DEFAULT_EXECUTE_TIMEOUT_MS, fixed.getExecuteTimeoutMs());
        assertEquals(ModConfig.DEFAULT_MAX_TOOL_RESULT_CHARS, fixed.getMaxToolResultChars());
        assertEquals(ModConfig.DEFAULT_MAX_CONTEXT_CHARS, fixed.getMaxContextChars());
        assertNotNull(fixed.getApiUrl());
        assertFalse(fixed.getApiUrl().trim().isEmpty());
        assertFalse(fixed.getModel().trim().isEmpty());
    }

    @Test
    public void fieldInitialisersMatchTheDeclaredDefaults() {
        ModConfig fresh = new ModConfig();
        assertEquals(ModConfig.DEFAULT_MAX_TURNS, fresh.getMaxTurns());
        assertEquals(ModConfig.DEFAULT_TASK_DEADLINE_SECONDS, fresh.getTaskDeadlineSeconds());
        assertEquals(ModConfig.DEFAULT_MAX_TOKENS, fresh.getMaxTokens());
        assertEquals(ModConfig.DEFAULT_EXECUTE_TIMEOUT_MS, fresh.getExecuteTimeoutMs());
        assertEquals(ModConfig.DEFAULT_MAX_TOOL_RESULT_CHARS, fresh.getMaxToolResultChars());
        assertEquals(ModConfig.DEFAULT_MAX_CONTEXT_CHARS, fresh.getMaxContextChars());
    }

    @Test
    public void speedIsClampedIntoTheSupportedRange() {
        ModConfig config = new ModConfig();
        config.setDroneSpeedMultiplier(1000.0D);
        assertTrue(config.sanitized().getDroneSpeedMultiplier()
                <= ir.layercloud.cortexdrone.service.navigation.DroneSpeed.MAX_MULTIPLIER);

        config.setDroneSpeedMultiplier(0.0001D);
        assertTrue(config.sanitized().getDroneSpeedMultiplier()
                >= ir.layercloud.cortexdrone.service.navigation.DroneSpeed.MIN_MULTIPLIER);
    }

    @Test
    public void nullCollectionsAreRepaired() {
        ModConfig config = new ModConfig();
        config.setSavedDrones(null);
        assertNotNull(config.sanitized().getSavedDrones());
    }
}
