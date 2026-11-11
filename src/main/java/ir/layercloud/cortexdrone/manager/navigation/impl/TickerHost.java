package ir.layercloud.cortexdrone.manager.navigation.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import li.cil.oc.common.entity.Drone;
import net.minecraft.world.World;

/**
 * The manager's wiring for {@link NavigationTicker}.
 */
final class TickerHost implements NavigationTicker.Host {
    private final NavigationManager manager;

    TickerHost(NavigationManager manager) {
        this.manager = manager;
    }

    @Override
    public NavigationState state(DroneController controller) {
        return this.manager.state(controller);
    }

    @Override
    public NavigationSession session(DroneController controller) {
        return this.manager.session(controller);
    }

    @Override
    public void trackStall(DroneController controller, NavigationState state,
                           Drone drone, World world, double movedSq) {
        this.manager.trackStall(controller, state, drone, world, movedSq);
    }

    @Override
    public void updateTicket(DroneController controller, NavigationState state) {
        this.manager.updateTicket(controller, state);
    }

    @Override
    public void arrive(DroneController controller, NavigationSession session) {
        this.manager.arriveFor(controller, session);
    }

    @Override
    public void fail(DroneController controller, String reason) {
        this.manager.failFor(controller, reason);
    }

    @Override
    public void tickLeg(DroneController controller, NavigationSession session) {
        this.manager.tickLegFor(controller, session);
    }

    @Override
    public void decide(DroneController controller, NavigationSession session) {
        this.manager.decideFor(controller, session);
    }
}
