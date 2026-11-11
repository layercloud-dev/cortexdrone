package ir.layercloud.cortexdrone.service.navigation.impl;

import lombok.experimental.UtilityClass;

/**
 * Pure flight-speed model behind {@link DronePhysicsService}.
 *
 * <p>Extracted so the integrator can be simulated without a live
 * {@code World}/{@code Drone}. The physics step needs real blocks for collision,
 * which makes it impossible to unit-test; the cruise profile, braking and
 * acceleration are pure maths, and a bug there silently caps the top speed (a
 * live 5x flight measured only ~20 b/s because the sub-leg stride never let it
 * leave the braking zone).</p>
 *
 * <p>{@link State} is a plain value object with the same fields the entity
 * carries, so the simulation and the real tick run identical arithmetic.</p>
 */
@UtilityClass
public final class FlightSpeedModel {
    /**
     * Hard velocity cap while the drone is in a liquid.
     *
     * <p>Water must slow the drone, never boost it: an earlier buoyancy term
     * pushed it upward and made water faster than air.</p>
     */
    public static final double WATER_SPEED_CAP = 0.34;

    /**
     * Mutable per-tick kinematic state; mirrors the entity motion fields.
     */
    public static final class State {
        public double x;
        public double y;
        public double z;
        public double motionX;
        public double motionY;
        public double motionZ;

        public State(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public double speed() {
            return Math.sqrt(this.motionX * this.motionX
                    + this.motionY * this.motionY + this.motionZ * this.motionZ);
        }
    }

    /**
     * Advances one tick toward a target.
     *
     * @param state      kinematics, updated in place
     * @param tx,ty,tz   flight target
     * @param cap        velocity cap for this tick (see {@code DroneSpeed})
     * @param inLiquid   true when the drone is in water/lava
     * @param drag       entity drag, applied to the stored motion like vanilla
     * @param accelFloor the entity's own acceleration attribute
     * @return distance to the target after the move
     */
    public static double step(State state, double tx, double ty, double tz, double cap,
                              boolean inLiquid, double drag, float accelFloor) {
        double dx = tx - state.x;
        double dy = ty - state.y;
        double dz = tz - state.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double vx = state.motionX;
        double vy = state.motionY;
        double vz = state.motionZ;
        double speedSq = vx * vx + vy * vy + vz * vz;

        if (distance > 0.0 && (distance > 0.005 || speedSq > 0.000025)) {
            double effectiveCap = cap > 0.0 ? cap : 1.15;
            if (inLiquid) effectiveCap = Math.min(effectiveCap, WATER_SPEED_CAP);
            double brakeRadius = brakingRadius(effectiveCap);
            double cruiseFrac = Math.min(1.0, distance / brakeRadius);
            // The ease-in floor must never exceed the requested cap, otherwise a
            // deliberately slow multiplier still flies at 0.28 blocks/tick.
            double floor = Math.min(0.28, effectiveCap);
            double vMax = Math.max(floor, effectiveCap * cruiseFrac);

            double closing = distance > 0.001 ? ((vx * dx + vy * dy + vz * dz) / distance) : 0.0;
            double stopDist = (closing * closing) / (2.0 * DronePhysicsService.MAX_SUBSTEP) + 0.4;
            if (stopDist > distance && closing > 0.0) {
                double brake = Math.min(closing, (distance + 0.4) * 0.45 + 0.05);
                double scale = closing > 0.001 ? (brake / closing) : 1.0;
                vx *= (0.55 + 0.45 * scale);
                vy *= (0.55 + 0.45 * scale);
                vz *= (0.55 + 0.45 * scale);
            }

            // The ramp must beat drag, or cruise settles below the cap. Drag
            // removes `cap * (1 - drag)` every tick at equilibrium, so the
            // acceleration floor is derived from it instead of guessed. The old
            // sqrt ramp left a 5x drone at 3.17 of a 3.75 cap.
            double accelBase = cap > 0.5 ? 0.14 : (double) Math.max(0.08f, accelFloor);
            double dragLoss = effectiveCap * Math.max(0.0, 1.0 - drag);
            accelBase = Math.max(accelBase, dragLoss * 1.15);
            if (inLiquid) accelBase *= 1.1;
            double accel = Math.min(accelBase, distance) / distance;
            vx += dx * accel;
            vy += dy * accel;
            vz += dz * accel;
            vx = Math.max(-vMax, Math.min(vMax, vx));
            vy = Math.max(-vMax, Math.min(vMax, vy));
            vz = Math.max(-vMax, Math.min(vMax, vz));
        } else {
            vx = 0.0;
            vy = 0.0;
            vz = 0.0;
        }

        state.x += vx;
        state.y += vy;
        state.z += vz;
        state.motionX = vx * drag;
        state.motionY = vy * drag;
        state.motionZ = vz * drag;
        double rx = tx - state.x;
        double ry = ty - state.y;
        double rz = tz - state.z;
        return Math.sqrt(rx * rx + ry * ry + rz * rz);
    }

    /**
     * Ease-in radius multiplier: the drone starts braking this many caps out.
     */
    public static final double BRAKING_FACTOR = 6.0D;
    /**
     * Caps below one are treated as one when computing the braking radius.
     */
    public static final double BRAKING_MIN_CAP = 0.75D;

    /**
     * Braking radius for a cap; the single definition both policies use.
     */
    public static double brakingRadius(double cap) {
        return BRAKING_FACTOR * Math.max(1.0D, cap / BRAKING_MIN_CAP);
    }
}
