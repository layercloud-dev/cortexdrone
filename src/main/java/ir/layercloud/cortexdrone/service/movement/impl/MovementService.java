package ir.layercloud.cortexdrone.service.movement.impl;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.movement.impl.BreadcrumbsAction;
import ir.layercloud.cortexdrone.action.movement.impl.DigDownAction;
import ir.layercloud.cortexdrone.action.movement.impl.DigUpAction;
import ir.layercloud.cortexdrone.action.movement.MovementPlan;
import ir.layercloud.cortexdrone.action.movement.MovementRequest;
import ir.layercloud.cortexdrone.action.movement.MovementResult;
import ir.layercloud.cortexdrone.action.movement.MovementRoute;
import ir.layercloud.cortexdrone.action.movement.impl.StopAction;
import ir.layercloud.cortexdrone.action.movement.MovementTarget;
import ir.layercloud.cortexdrone.action.movement.impl.NavigateAction;
import ir.layercloud.cortexdrone.action.movement.impl.TunnelAction;
import ir.layercloud.cortexdrone.context.movement.impl.MovementContext;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.NavContext;
import ir.layercloud.cortexdrone.navigation.service.FlightPathfinder;
import ir.layercloud.cortexdrone.service.ActionService;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;

/**
 * Long-lived movement manager. It decides which single-use movement action to
 * run, applies the common navigation plan and manages the retained
 * action/context pair. JSON stays in MovementRpcTranslator.
 */
public final class MovementService extends ActionService<MovementContext, MovementRequest, MovementResult> {
    private final AIDroneManager manager;

    public MovementService(AIDroneManager manager) {
        super("movement", "navigate", "tunnel", "digup", "digdown", "get_breadcrumbs");
        this.manager = manager;
    }

    @Override
    protected MovementContext createContext(DroneController controller, EntityPlayerMP player,
                                            String operation) {
        return new MovementContext(controller, player, operation);
    }

    @Override
    protected Action<MovementContext> createAction(MovementContext context, String operation,
                                                   MovementRequest request) {
        if ("get_breadcrumbs".equals(operation)) return new BreadcrumbsAction(request);
        if ("tunnel".equals(operation)) return new TunnelAction(this, request);
        if ("digup".equals(operation)) return new DigUpAction(this, request);
        if ("digdown".equals(operation)) return new DigDownAction(this, request);
        if ("navigate".equals(operation)) {
            switch (request.getRoute()) {
                case TUNNEL:
                    return new TunnelAction(this, request);
                case UP:
                    return new DigUpAction(this, request);
                case DOWN:
                    return new DigDownAction(this, request);
                default:
                    return new NavigateAction(this, request);
            }
        }
        throw new IllegalArgumentException("Unsupported movement action: " + operation);
    }

    /** Shared target resolution: explicit coordinates or a safe standoff. */
    public MovementTarget resolveTarget(MovementRequest request) {
        DroneController controller = request.getController();
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return null;
        }
        if (request.isStandoff()) {
            double[] standoff = FlightPathfinder.findMiningStandoff(controller.drone.worldObj,
                controller.drone, request.getStandoffX(), request.getStandoffY(), request.getStandoffZ());
            if (standoff == null) {
                int[] open = FlightPathfinder.findNearestPassableVoxel(controller.drone.worldObj,
                    request.getStandoffX(), request.getStandoffY() + 1, request.getStandoffZ(), 3);
                if (open == null) return null;
                standoff = new double[]{open[0] + 0.5, open[1] + 0.35, open[2] + 0.5};
            }
            return new MovementTarget(standoff[0], standoff[1], standoff[2]);
        }
        if (request.isCoordinates()) {
            return new MovementTarget(request.getX(), request.getY(), request.getZ());
        }
        return null;
    }

    public MovementTarget surfaceTarget(DroneController controller) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return null;
        }
        int x = MathHelper.floor_double(controller.drone.posX);
        int z = MathHelper.floor_double(controller.drone.posZ);
        int top = controller.drone.worldObj.getHeightValue(x, z);
        return new MovementTarget(x + 0.5, Math.min(250, top + 2.5), z + 0.5);
    }

    public MovementTarget depthTarget(DroneController controller, int depth) {
        if (controller == null || controller.drone == null) return null;
        int x = MathHelper.floor_double(controller.drone.posX);
        int y = Math.max(1, MathHelper.floor_double(controller.drone.posY) - Math.max(1, depth));
        int z = MathHelper.floor_double(controller.drone.posZ);
        return new MovementTarget(x + 0.5, y, z + 0.5);
    }

    public boolean inOpenSky(DroneController controller) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return false;
        }
        int x = MathHelper.floor_double(controller.drone.posX);
        int y = MathHelper.floor_double(controller.drone.posY);
        int z = MathHelper.floor_double(controller.drone.posZ);
        return controller.drone.worldObj.canBlockSeeTheSky(x, y, z)
            || y >= controller.drone.worldObj.getHeightValue(x, z) - 1;
    }

    /** Core navigation start shared by every movement action. */
    public MovementResult startNavigation(MovementRequest request, MovementPlan plan, String label) {
        return startNavigation(request.getController(), plan.getTarget(), plan.getRoute(),
            plan.getFinalTarget(), plan.isViaSurface(), label);
    }

    public MovementResult startNavigation(DroneController controller, MovementTarget target,
                                          MovementRoute route, MovementTarget finalTarget,
                                          boolean viaSurface, String label) {
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return MovementResult.failure("No active drone");
        }

        controller.navigationGeneration++;
        controller.navigationRoute = route.getId();
        controller.navViaSurface = viaSurface;
        controller.navFinalX = finalTarget != null ? finalTarget.getX() : Double.NaN;
        controller.navFinalY = finalTarget != null ? finalTarget.getY() : Double.NaN;
        controller.navFinalZ = finalTarget != null ? finalTarget.getZ() : Double.NaN;

        NavContext navigation = NavContext.of(controller,
            target.getX(), target.getY(), target.getZ(), route.getId());
        navigation.setGoal(target.getX(), target.getY(), target.getZ());
        this.manager.setDroneMode(controller, Mode.GOTO,
            label + " " + route.getId()
                + String.format(" (%.1f,%.1f,%.1f)", target.getX(), target.getY(), target.getZ()));
        return MovementResult.started(route.getId(),
            target.getX(), target.getY(), target.getZ(), viaSurface);
    }

    /** Core stop behavior shared by stay/stop actions and command cancellation. */
    public void stop(DroneController controller, String label) {
        if (controller == null || controller.drone == null) return;
        controller.navigationGeneration++;
        controller.navigationRoute = MovementRoute.AUTO.getId();
        controller.navViaSurface = false;
        controller.navFinalX = Double.NaN;
        controller.navFinalY = Double.NaN;
        controller.navFinalZ = Double.NaN;
        controller.goalX = controller.drone.posX;
        controller.goalY = controller.drone.posY;
        controller.goalZ = controller.drone.posZ;
        this.manager.setDroneMode(controller, Mode.STAY, label);
    }

    public void stop(DroneController controller, EntityPlayerMP player, String label) {
        MovementContext context = new MovementContext(controller, player, "stop");
        run(controller, player, "stop", context, new StopAction(this, label));
    }

    @Override
    protected MovementResult result(MovementContext context) {
        return context.result();
    }
}
