package ir.layercloud.cortexdrone.action.interact;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.Map;

/**
 * Immutable result of one interaction action.
 */
@Value
@Builder
public class InteractionResult {
    boolean ok;
    String error;

    @Singular("field")
    Map<String, Object> fields;

    double[] standoff;

    public static InteractionResult success(Object... fields) {
        return build(true, null, null, fields);
    }

    public static InteractionResult failure(String error, Object... fields) {
        return build(false, error, null, fields);
    }

    public static InteractionResult movement(double[] standoff, Object... fields) {
        return build(true, null, standoff, fields);
    }

    private static InteractionResult build(boolean ok, String error, double[] standoff,
                                           Object... fields) {
        InteractionResultBuilder builder = builder().ok(ok).error(error).standoff(standoff);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            builder.field(String.valueOf(fields[i]), fields[i + 1]);
        }
        return builder.build();
    }
}
