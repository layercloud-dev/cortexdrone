package ir.layercloud.cortexdrone.action.visuals;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.Map;

/**
 * Immutable result of one visuals action.
 */
@Value
@Builder
public class VisualsResult {
    boolean ok;
    String error;

    @Singular("field")
    Map<String, Object> fields;

    public static VisualsResult success(Object... fields) {
        return build(true, null, fields);
    }

    public static VisualsResult failure(String error, Object... fields) {
        return build(false, error, fields);
    }

    private static VisualsResult build(boolean ok, String error, Object... fields) {
        VisualsResultBuilder builder = builder().ok(ok).error(error);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            builder.field(String.valueOf(fields[i]), fields[i + 1]);
        }
        return builder.build();
    }
}
