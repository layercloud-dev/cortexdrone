package ir.layercloud.cortexdrone.command.impl;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@code /drone js} executes arbitrary Rhino with full Java reflection, so it
 * must be operator-only.
 *
 * <p>Regression: the command reported permission level 0 and
 * {@code canCommandSenderUseCommand() == true}, so any authenticated player
 * could run {@code Java.type("java.lang.Runtime")} on the server.</p>
 */
public class CommandPermissionsTest {
    @Test
    public void arbitraryCodeExecutionRequiresOperator() {
        assertTrue("js must be op-gated",
                CommandPermissions.requiresOperator("js"));
    }

    @Test
    public void configChangesRequireOperator() {
        assertTrue(CommandPermissions.requiresOperator("config"));
    }

    @Test
    public void readOnlyAndPlayerCommandsStayAvailable() {
        for (String sub : new String[]{"spawn", "link", "despawn", "list", "status",
                "debug", "memory", "lesson", "sessions", "skills", "help"}) {
            assertFalse(sub + " must stay usable by normal players",
                    CommandPermissions.requiresOperator(sub));
        }
    }

    @Test
    public void permissionCheckIsCaseInsensitive() {
        assertTrue(CommandPermissions.requiresOperator("JS"));
        assertTrue(CommandPermissions.requiresOperator("Config"));
        assertFalse(CommandPermissions.requiresOperator(null));
    }

    @Test
    public void operatorLevelsAreRecognised() {
        assertFalse(CommandPermissions.isOperator(0));
        assertFalse(CommandPermissions.isOperator(1));
        assertTrue(CommandPermissions.isOperator(2));
        assertTrue(CommandPermissions.isOperator(4));
    }
}
