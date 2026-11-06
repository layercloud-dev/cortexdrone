package ir.layercloud.cortexdrone.context.action.movement.impl;

import ir.layercloud.cortexdrone.action.movement.MovementResult;
import ir.layercloud.cortexdrone.context.ActionContextBase;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Mutable state for one point-to-point navigation action.
 *
 * <p>The action is retained when the navigator accepts the route and is
 * released when the drone arrives, leaves {@link Mode#GOTO}, despawns or a
 * newer navigation generation replaces it.</p>
 */
@Getter
@Accessors(fluent = true)
public final class MovementContext extends ActionContextBase {
    @Setter
    private MovementResult result;

    private int generation = -1;
    private String route;
    private double goalX;
    private double goalY;
    private double goalZ;
    private int ticks;
    private double lastX;
    private double lastY;
    private double lastZ;
    private double distanceToGoal;
    private boolean arrived;

    public MovementContext(DroneController controller, EntityPlayerMP player, String operation) {
        super(controller, player, operation);
    }

    /** Records the accepted route and keeps the action alive across ticks. */
    public void begin(MovementResult result) {
        if (result == null || !result.isOk() || result.hasBreadcrumbs()) return;
        this.route = result.getRoute();
        this.goalX = result.getGoalX();
        this.goalY = result.getGoalY();
        this.goalZ = result.getGoalZ();
        this.generation = controller() != null ? controller().navigationGeneration : -1;
        retain();
    }

    public void recordProgress(double x, double y, double z, double distance) {
        this.ticks++;
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
        this.distanceToGoal = distance;
    }

    public void finish(boolean arrived) {
        this.arrived = arrived;
        complete();
    }
}
