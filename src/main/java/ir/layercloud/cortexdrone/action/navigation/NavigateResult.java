package ir.layercloud.cortexdrone.action.navigation;

import lombok.Builder;
import lombok.Value;

/**
 * Result of one executable navigation step.
 */
@Value
@Builder
public class NavigateResult {
    boolean moved;
    boolean legDone;
    boolean blocked;
    boolean partial;
    String error;

    public static NavigateResult moved(boolean partial) {
        return builder().moved(true).partial(partial).build();
    }

    public static NavigateResult blocked(boolean partial) {
        return builder().blocked(true).partial(partial).build();
    }

    public static NavigateResult done() {
        return builder().legDone(true).build();
    }

    public static NavigateResult failure(String error) {
        return builder().error(error).build();
    }
}
