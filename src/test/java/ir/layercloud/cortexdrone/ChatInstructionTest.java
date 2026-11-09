package ir.layercloud.cortexdrone;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Chat prefix parsing for drone instructions.
 *
 * <p>Regression risk: a prefix match without a word boundary made
 * {@code @Miner2} trigger the drone named {@code Mine}, and a bare {@code @drone}
 * produced an empty instruction the agent loop could not act on.</p>
 */
public class ChatInstructionTest {
    @Test
    public void theGlobalPrefixRoutesToTheTaskAgent() {
        assertEquals("mine 16 iron", ChatInstruction.parse("@drone mine 16 iron", null));
        assertEquals("mine 16 iron", ChatInstruction.parse("  @drone   mine 16 iron  ", null));
    }

    @Test
    public void aBareGlobalPrefixAsksForStatus() {
        assertEquals(ChatInstruction.DEFAULT_MESSAGE, ChatInstruction.parse("@drone", null));
        assertEquals(ChatInstruction.DEFAULT_MESSAGE, ChatInstruction.parse("@drone   ", null));
    }

    @Test
    public void aNamedPrefixTargetsThatDrone() {
        assertEquals("come here", ChatInstruction.parse("@Miner come here", "Miner"));
        assertEquals("come here", ChatInstruction.parse("@miner come here", "Miner"));
    }

    @Test
    public void aNamedPrefixMustEndAtAWordBoundary() {
        assertNull("@Miner2 must not trigger the drone named Miner",
                ChatInstruction.parse("@Miner2 come here", "Miner"));
        assertEquals("come here", ChatInstruction.parse("@Miner2 come here", "Miner2"));
    }

    @Test
    public void unrelatedChatIsIgnored() {
        assertNull(ChatInstruction.parse("hello everyone", "Miner"));
        assertNull(ChatInstruction.parse("@other hi", "Miner"));
        assertNull(ChatInstruction.parse(null, "Miner"));
        assertNull(ChatInstruction.parse("   ", "Miner"));
    }

    @Test
    public void aPlayerWithoutADroneGetsNoNamedMatch() {
        assertNull(ChatInstruction.parse("@Miner come here", null));
        assertNull(ChatInstruction.parse("@Miner come here", ""));
    }
}
