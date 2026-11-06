package ir.layercloud.cortexdrone.action.effect;

import java.util.Map;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/** Immutable result of one effect action. */
@Value
@Builder
public class EffectResult {
    boolean ok;
    String error;

    @Singular("field")
    Map<String, Object> fields;

    public static EffectResult success(Object... fields) {
        return build(true, null, fields);
    }

    public static EffectResult failure(String error, Object... fields) {
        return build(false, error, fields);
    }

    private static EffectResult build(boolean ok, String error, Object... fields) {
        EffectResultBuilder builder = builder().ok(ok).error(error);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            builder.field(String.valueOf(fields[i]), fields[i + 1]);
        }
        return builder.build();
    }
}
