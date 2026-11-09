package ir.layercloud.cortexdrone.harness.tool;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The tool schema declares integer arguments; a JSON number outside the Java
 * int range must be rejected instead of silently truncating to a different
 * coordinate.
 */
public class ToolValidatorRangeTest {
    private static final ToolSpec SPEC = ToolSpec.builder("probe")
            .description("probe")
            .parameters(ToolSchema.object().requiredInt("x", "X coordinate."))
            .affinity(ToolAffinity.ASYNC)
            .build();

    private static JsonObject validate(Object value) {
        JsonObject args = new JsonObject();
        if (value instanceof Number) {
            args.addProperty("x", (Number) value);
        } else {
            args.addProperty("x", String.valueOf(value));
        }
        return ToolValidator.validate(SPEC, args);
    }

    @Test
    public void valueAboveIntMaxIsRejected() {
        JsonObject result = validate(4294967296L);
        assertNotNull("4294967296 would truncate to 0", result);
        assertEquals("invalid_arguments", result.get("code").getAsString());
    }

    @Test
    public void valueBelowIntMinIsRejected() {
        JsonObject result = validate(-4294967296L);
        assertNotNull("-4294967296 would truncate", result);
        assertEquals("invalid_arguments", result.get("code").getAsString());
    }

    @Test
    public void numericStringOutsideIntRangeIsRejected() {
        JsonObject result = validate("2147483648");
        assertNotNull(result);
        assertEquals("invalid_arguments", result.get("code").getAsString());
    }

    @Test
    public void boundaryValuesAreStillAccepted() {
        assertNull(validate(Integer.MAX_VALUE));
        assertNull(validate(Integer.MIN_VALUE));
    }
}
