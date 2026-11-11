package ir.layercloud.cortexdrone.action.oc;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Immutable request for one OpenComputers operation.
 *
 * <p>One request covers both callback invocation and the few machine controls
 * OC exposes, because they share the same target (the drone's OC component) and
 * the same result shape.</p>
 */
@Value
@Builder
public class OcRequest {
    /**
     * OC callback name, e.g. {@code move}, {@code detect}, {@code inventorySize}.
     */
    String method;

    /**
     * Positional arguments passed to the callback, already JSON-decoded.
     */
    List<Object> arguments;

    /**
     * Machine control operation for {@code oc_machine}: start, stop, pause, signal.
     */
    String control;

    /**
     * Seconds for {@code pause}, signal name for {@code signal}.
     */
    String value;

    /**
     * Signal payload for {@code signal}.
     */
    List<Object> payload;
}
