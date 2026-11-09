package ir.layercloud.cortexdrone.command.impl;

import org.junit.Test;

import static org.junit.Assert.*;

/** The admin subcommands must only reveal a masked secret. */
public class AdminCommandsTest {
    @Test
    public void secretsAreMaskedWithPrefixAndSuffixOnly() {
        assertEquals("sk-a...op", AdminCommands.mask("sk-abcdefghijklmnop"));
        assertFalse("the middle of the key must not appear",
                AdminCommands.mask("sk-abcdefghijklmnop").contains("efghij"));
    }

    @Test
    public void shortSecretsAreFullyMasked() {
        assertEquals("***", AdminCommands.mask("abc"));
        assertEquals("***", AdminCommands.mask("abcdef"));
    }

    @Test
    public void missingSecretsAreLabelled() {
        assertEquals("<not set>", AdminCommands.mask(null));
        assertEquals("<not set>", AdminCommands.mask(""));
    }

    @Test
    public void consoleSendersCountAsOperators() {
        assertTrue("console has no player and must be allowed",
                AdminCommands.isOperator(null, null));
    }
}
