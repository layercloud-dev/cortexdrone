package ir.layercloud.cortexdrone.action.oc;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Immutable result of one OpenComputers operation.
 *
 * <p>{@code value} is the callback's first return slot exactly as OC produced
 * it; {@code values} keeps the whole tuple for callbacks that return more than
 * one value.</p>
 */
@Value
@Builder
public class OcResult {
    boolean ok;
    String error;

    /** The method or control that ran, for tracing. */
    String operation;

    /** First return value from the OC callback. */
    Object value;

    /** Complete return tuple. */
    List<Object> values;

    @Singular("field")
    Map<String, Object> fields;

    public static OcResult success(String operation, Object value, List<Object> values,
                                   Object... fields) {
        return build(true, null, operation, value, values, fields);
    }

    public static OcResult failure(String error, Object... fields) {
        return build(false, error, null, null, null, fields);
    }

    private static OcResult build(boolean ok, String error, String operation, Object value,
                                  List<Object> values, Object... fields) {
        OcResultBuilder builder = builder().ok(ok).error(error)
                .operation(operation).value(value).values(values);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            builder.field(String.valueOf(fields[i]), fields[i + 1]);
        }
        return builder.build();
    }
}
