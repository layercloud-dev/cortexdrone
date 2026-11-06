package ir.layercloud.cortexdrone.action.movement;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Navigation route selected for one movement action. */
@Getter
@RequiredArgsConstructor
public enum MovementRoute {
    AUTO("auto"),
    DIRECT("direct"),
    TUNNEL("tunnel"),
    UP("up"),
    DOWN("down");

    private final String id;

    public static MovementRoute parse(String raw, MovementRoute fallback) {
        if (raw == null) return fallback;
        String normalized = raw.trim().toLowerCase();
        for (MovementRoute route : values()) {
            if (route.id.equals(normalized)) return route;
        }
        return fallback;
    }
}
