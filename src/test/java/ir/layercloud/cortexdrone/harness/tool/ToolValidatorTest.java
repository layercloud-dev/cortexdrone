package ir.layercloud.cortexdrone.harness.tool;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ToolValidatorTest {
    private static final ToolSpec SPEC = ToolSpec.builder("probe")
            .description("probe")
            .parameters(ToolSchema.object()
                    .requiredInt("x", "X coordinate.")
                    .optionalBoolean("enabled", "Flag.", true)
                    .optionalEnum("mode", "Mode.", "fast", "fast", "safe"))
            .affinity(ToolAffinity.ASYNC)
            .build();

    @Test
    public void missingRequiredFieldReturnsActionableError() {
        JsonObject result = ToolValidator.validate(SPEC, new JsonObject());
        assertNotNull(result);
        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("invalid_arguments", result.get("code").getAsString());
        assertTrue(result.get("error").getAsString().contains("'x'"));
        assertTrue(result.has("hint"));
    }

    @Test
    public void numericStringsAndBooleanAliasesAreCoerced() {
        JsonObject args = new JsonObject();
        args.addProperty("x", "12");
        args.addProperty("enabled", "off");
        assertNull(ToolValidator.validate(SPEC, args));
        assertEquals(12, args.get("x").getAsInt());
        assertFalse(args.get("enabled").getAsBoolean());
    }

    @Test
    public void positionalArraysAreAcceptedForObjectArguments() {
        ToolSpec spec = ToolSpec.builder("probe_object")
                .description("probe")
                .parameters(ToolSchema.object()
                        .optionalObject("args", "Positional arguments."))
                .affinity(ToolAffinity.ASYNC)
                .build();
        JsonObject args = new JsonObject();
        com.google.gson.JsonArray values = new com.google.gson.JsonArray();
        values.add(new com.google.gson.JsonPrimitive(2));
        args.add("args", values);
        assertNull("an array is a valid positional argument table", ToolValidator.validate(spec, args));
        assertEquals(2, args.getAsJsonObject("args").get("1").getAsInt());
    }

    @Test
    public void invalidEnumIsRejectedWithAllowedValues() {
        JsonObject args = new JsonObject();
        args.addProperty("x", 1);
        args.addProperty("mode", "turbo");
        JsonObject result = ToolValidator.validate(SPEC, args);
        assertNotNull(result);
        assertTrue(result.get("error").getAsString().contains("turbo"));
        assertTrue(result.get("error").getAsString().contains("fast"));
    }
}
