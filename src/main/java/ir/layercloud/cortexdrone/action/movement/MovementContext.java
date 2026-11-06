package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.action.ActionContextBase;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Mutable state for one point-to-point navigation action.
 *
 * <p>The context is retained when the navigator accepts the route and is
 * discarded when the drone arrives, leaves {@link Mode#GOTO}, despawns or a
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

    /** Records the accepted route and keeps this context alive across ticks. */
    public void begin(MovementResult result) {
        if (result == null || !result.isOk() || result.hasBreadcrumbs()) return;
        this.route = result.getRoute();
        this.goalX = result.getGoalX();
        this.goalY = result.getGoalY();
        this.goalZ = result.getGoalZ();
        this.generation = controller() != null ? controller().navigationGeneration : -1;
        retain();
    }

    @Override
    protected void onTick() {
        DroneController controller = controller();
        if (controller == null || controller.drone == null || controller.drone.isDead) {
            complete();
            return;
        }

        this.ticks++;
        this.lastX = controller.drone.posX;
        this.lastY = controller.drone.posY;
        this.lastZ = controller.drone.posZ;
        double dx = this.lastX - this.goalX;
        double dy = this.lastY - this.goalY;
        double dz = this.lastZ - this.goalZ;
        this.distanceToGoal = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (controller.mode != Mode.GOTO || controller.navigationGeneration != this.generation) {
            this.arrived = controller.mode != Mode.GOTO && this.distanceToGoal <= 1.25;
            complete();
        }
    }
}
