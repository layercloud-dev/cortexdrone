package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.action.ActionContextBase;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Lives for one point-to-point navigation request. It is retained when the
 * navigator accepts the request and is discarded when the drone arrives,
 * changes mode, despawns or a newer navigation generation replaces it.
 */
public final class MovementActionContext extends ActionContextBase {
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

    public MovementActionContext(DroneController controller, EntityPlayerMP player, String operation) {
        super(controller, player, operation);
    }

    /** Records the accepted route and keeps this context alive across ticks. */
    public void markStarted(MovementResult result) {
        if (result == null || !result.isOk() || result.hasBreadcrumbs()) return;
        this.route = result.route();
        this.goalX = result.goalX();
        this.goalY = result.goalY();
        this.goalZ = result.goalZ();
        this.generation = controller() != null ? controller().navigationGeneration : -1;
        retain();
    }

    @Override
    protected void onTick() {
        DroneController ctrl = controller();
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70128_L) {
            complete();
            return;
        }
        this.ticks++;
        this.lastX = ctrl.drone.field_70165_t;
        this.lastY = ctrl.drone.field_70163_u;
        this.lastZ = ctrl.drone.field_70161_v;
        double dx = this.lastX - this.goalX;
        double dy = this.lastY - this.goalY;
        double dz = this.lastZ - this.goalZ;
        this.distanceToGoal = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (ctrl.mode != Mode.GOTO || ctrl.navigationGeneration != this.generation) {
            this.arrived = ctrl.mode != Mode.GOTO && this.distanceToGoal <= 1.25;
            complete();
        }
    }

    public String route() { return this.route; }
    public double goalX() { return this.goalX; }
    public double goalY() { return this.goalY; }
    public double goalZ() { return this.goalZ; }
    public int generation() { return this.generation; }
    public int ticks() { return this.ticks; }
    public double lastX() { return this.lastX; }
    public double lastY() { return this.lastY; }
    public double lastZ() { return this.lastZ; }
    public double distanceToGoal() { return this.distanceToGoal; }
    public boolean arrived() { return this.arrived; }
}
