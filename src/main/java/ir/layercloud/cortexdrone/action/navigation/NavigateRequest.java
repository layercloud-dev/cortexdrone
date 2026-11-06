package ir.layercloud.cortexdrone.action.navigation;

import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.navigation.Breadcrumb;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import net.minecraft.entity.player.EntityPlayerMP;

/** Immutable request data for one executable navigation leg. */
@Value
@Builder
public class NavigateRequest {
    DroneController controller;
    EntityPlayerMP player;
    NavigationMode mode;
    double targetX;
    double targetY;
    double targetZ;
    List<Breadcrumb> trail;
    List<int[]> digPath;
    String label;
}
