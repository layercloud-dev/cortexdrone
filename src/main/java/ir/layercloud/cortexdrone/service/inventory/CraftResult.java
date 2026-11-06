package ir.layercloud.cortexdrone.service.inventory;

import lombok.Builder;
import lombok.Value;

/** Typed result of a crafting action. */
@Value
@Builder
public class CraftResult {
    boolean ok;
    String error;
    int crafted;
    String item;

    public static CraftResult failure(String error) {
        return builder().ok(false).error(error).crafted(0).build();
    }

    public static CraftResult success(int crafted, String item) {
        return builder().ok(true).crafted(crafted).item(item).build();
    }
}
