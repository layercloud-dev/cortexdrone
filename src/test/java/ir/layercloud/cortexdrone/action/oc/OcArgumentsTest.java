package ir.layercloud.cortexdrone.action.oc;

import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * JSON-ish values must flatten to the shapes OpenComputers expects.
 */
public class OcArgumentsTest {
    @Test
    public void proxyIsNullWhenOpenComputersIsAbsent() {
        assertNull(OcArguments.of(Collections.<Object>emptyList()).proxy());
    }

    @Test
    public void nullArgumentsAreTolerated() {
        assertNotNull(OcArguments.of(null));
        assertNull(OcArguments.of(null).proxy());
    }

    @Test
    public void resultKeepsTheWholeTuple() {
        List<Object> values = Arrays.<Object>asList(true, "block");
        OcResult result = OcResult.success("detect", true, values, "declared_by", "Fake");
        assertTrue(result.isOk());
        assertEquals("detect", result.getOperation());
        assertEquals(true, result.getValue());
        assertEquals(2, result.getValues().size());
        assertEquals("Fake", result.getFields().get("declared_by"));
    }

    @Test
    public void failureCarriesErrorAndFields() {
        OcResult result = OcResult.failure("no callback", "method", "detect");
        assertFalse(result.isOk());
        assertEquals("no callback", result.getError());
        assertEquals("detect", result.getFields().get("method"));
        assertNull(result.getValue());
    }

    @Test
    public void requestBuilderKeepsPositionalArguments() {
        OcRequest request = OcRequest.builder()
                .method("move")
                .arguments(Arrays.<Object>asList(10.0, 1.0, 0.0))
                .build();
        assertEquals("move", request.getMethod());
        assertEquals(3, request.getArguments().size());
        assertNull(request.getControl());
    }

    @Test
    public void machineRequestCarriesControlAndPayload() {
        OcRequest request = OcRequest.builder()
                .control("signal")
                .value("drone_event")
                .payload(Collections.<Object>singletonList("payload"))
                .build();
        assertEquals("signal", request.getControl());
        assertEquals("drone_event", request.getValue());
        assertEquals(1, request.getPayload().size());
    }

    @Test
    public void sideNamesMapToForgeDirectionOrdinals() {
        assertEquals(Integer.valueOf(0), OcArguments.sideOrdinal("down"));
        assertEquals(Integer.valueOf(1), OcArguments.sideOrdinal("up"));
        assertEquals(Integer.valueOf(2), OcArguments.sideOrdinal("north"));
        assertEquals(Integer.valueOf(3), OcArguments.sideOrdinal("south"));
        assertEquals(Integer.valueOf(4), OcArguments.sideOrdinal("west"));
        assertEquals(Integer.valueOf(5), OcArguments.sideOrdinal("east"));
        assertNull(OcArguments.sideOrdinal("sideways"));
        assertNull(OcArguments.sideOrdinal(2));
    }

    @Test
    public void nestedMapsSurviveConstruction() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("1", "a");
        nested.put("2", 2);
        OcArguments arguments = OcArguments.of(Collections.<Object>singletonList(nested));
        assertNotNull(arguments);
    }
}
