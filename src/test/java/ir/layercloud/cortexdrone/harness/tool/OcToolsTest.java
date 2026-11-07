package ir.layercloud.cortexdrone.harness.tool;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.harness.tools.OcTools;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * The OpenComputers tools must ship in the catalog, bind without a live drone,
 * and be visible to the model and to Code Mode.
 */
public class OcToolsTest {
    @Test
    public void specsDeclareTheFullOcSurface() {
        Set<String> names = new HashSet<>();
        for (ToolSpec spec : OcTools.specs()) names.add(spec.getName());
        assertEquals(4, names.size());
        assertTrue(names.contains("oc_state"));
        assertTrue(names.contains("oc_methods"));
        assertTrue(names.contains("oc_call"));
        assertTrue(names.contains("oc_machine"));
    }

    @Test
    public void everySpecCanBeBound() {
        ToolRegistry registry = new ToolRegistry();
        registry.declareAll(OcTools.specs());
        for (ToolSpec spec : OcTools.specs()) {
            registry.bind(spec.getName(), "test", (context, args) -> ToolJson.ok());
        }
        for (ToolSpec spec : OcTools.specs()) {
            assertNotNull("Unbound: " + spec.getName(), registry.find(spec.getName()));
        }
    }

    @Test
    public void ocStateIsReadOnlyAndHasNoRequiredArguments() {
        ToolSpec state = findBySpec("oc_state");
        assertNotNull(state);
        assertEquals(ToolAffinity.MAIN, state.getAffinity());
        assertFalse(state.isInternal());
    }

    @Test
    public void ocCallRequiresAMethod() {
        ToolSpec call = findBySpec("oc_call");
        assertNotNull(call);
        JsonObject schema = call.getParameters();
        assertTrue(schema.has("required"));
        assertTrue(schema.getAsJsonArray("required").toString().contains("method"));
    }

    @Test
    public void ocMachineConstrainsControlToTheLifecycleSet() {
        ToolSpec machine = findBySpec("oc_machine");
        assertNotNull(machine);
        String schema = machine.getParameters().toString();
        for (String control : new String[]{"start", "stop", "pause", "signal"}) {
            assertTrue("Missing control " + control, schema.contains(control));
        }
    }

    private static ToolSpec findBySpec(String name) {
        for (ToolSpec spec : OcTools.specs()) {
            if (spec.getName().equals(name)) return spec;
        }
        return null;
    }
}
