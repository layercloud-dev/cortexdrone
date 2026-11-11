package ir.layercloud.cortexdrone.harness.tool;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tool capabilities are declared data, not a name list in each consumer.
 *
 * <p>Session metrics, the completion waiter and the scan router all used to
 * hard-code tool names. Declaring the capability on the spec means a new tool
 * is classified correctly the moment it is registered.</p>
 */
public class ToolCapabilityTest {
    private static ToolSpec spec(String name, ToolCapability... capabilities) {
        return ToolSpec.builder(name)
                .description("test")
                .parameters(ToolSchema.object())
                .affinity(ToolAffinity.ASYNC)
                .capabilities(capabilities)
                .build();
    }

    @Test
    public void toolsDefaultToReadOnly() {
        ToolSpec readOnly = spec("probe");
        assertEquals(java.util.EnumSet.of(ToolCapability.READ_ONLY), readOnly.getCapabilities());
        assertFalse(readOnly.mutatesAnything());
    }

    @Test
    public void worldMutationIsNotAlsoReadOnly() {
        ToolSpec breaking = spec("break_block", ToolCapability.MUTATES_WORLD);
        assertTrue(breaking.has(ToolCapability.MUTATES_WORLD));
        assertFalse("a mutating tool must not claim READ_ONLY",
                breaking.has(ToolCapability.READ_ONLY));
        assertTrue(breaking.mutatesAnything());
    }

    @Test
    public void inventoryMutationCountsAsMutation() {
        ToolSpec moving = spec("move_item", ToolCapability.MUTATES_INVENTORY);
        assertTrue(moving.mutatesAnything());
        assertFalse(moving.has(ToolCapability.MUTATES_WORLD));
    }

    @Test
    public void presentationIsNotAMutation() {
        ToolSpec led = spec("set_led", ToolCapability.PRESENTATION);
        assertFalse(led.mutatesAnything());
    }

    @Test
    public void capabilityPredicateMatchesTheDeclaration() {
        ToolSpec transfer = spec("deposit_chest", ToolCapability.MUTATES_INVENTORY,
                ToolCapability.AWAITS_COMPLETION);
        assertTrue(transfer.has(ToolCapability.AWAITS_COMPLETION));
        assertFalse(transfer.has(ToolCapability.TIME_SLICED));
        assertFalse(transfer.has(null));
    }

    @Test
    public void capabilitiesAreImmutable() {
        ToolSpec world = spec("place_block", ToolCapability.MUTATES_WORLD);
        try {
            world.getCapabilities().add(ToolCapability.READ_ONLY);
            fail("the capability set must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}
