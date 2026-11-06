package ir.layercloud.cortexdrone.context.navigation.impl;

import ir.layercloud.cortexdrone.action.navigation.NavigateResult;
import ir.layercloud.cortexdrone.action.navigation.NavigationMode;
import ir.layercloud.cortexdrone.context.ActionContextBase;
import ir.layercloud.cortexdrone.model.DroneController;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.entity.player.EntityPlayerMP;

/** Mutable state for one navigation leg. */
@Getter
@Accessors(fluent = true)
public final class NavigationContext extends ActionContextBase {
    private final NavigationMode mode;
    private final double targetX;
    private final double targetY;
    private final double targetZ;
    private final List<int[]> digPath;
    private final String label;

    @Setter
    private NavigateResult result;

    private int ticks;

    public NavigationContext(DroneController controller, EntityPlayerMP player,
                             NavigationMode mode, double targetX, double targetY, double targetZ,
                             List<int[]> digPath, String label) {
        super(controller, player, mode.name().toLowerCase());
        this.mode = mode;
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetZ = targetZ;
        this.digPath = digPath;
        this.label = label;
    }

    public int countTick() {
        return ++this.ticks;
    }
}
