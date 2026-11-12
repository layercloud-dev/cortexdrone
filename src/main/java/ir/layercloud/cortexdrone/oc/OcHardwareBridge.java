package ir.layercloud.cortexdrone.oc;

import ir.layercloud.cortexdrone.oc.impl.InertMachineArchitecture;
import li.cil.oc.api.Network;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Connector;
import li.cil.oc.api.network.Node;
import li.cil.oc.common.entity.Drone;
import li.cil.oc.server.machine.Machine;
import scala.Enumeration;
import scala.collection.mutable.Stack;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

/**
 * Encapsulates OpenComputers Drone machine reflection, energy buffer management,
 * Paused hover state maintenance, and virtual Component proxy injection.
 */
public class OcHardwareBridge {
    private Field remainingPauseField = null;
    private Field droneMachineField = null;
    private Field componentNodeField = null;
    private Field droneControlField = null;

    public OcHardwareBridge() {
        try {
            this.remainingPauseField = Machine.class.getDeclaredField("remainingPause");
            this.remainingPauseField.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            this.droneMachineField = Drone.class.getDeclaredField("machine");
            this.droneMachineField.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            this.componentNodeField = li.cil.oc.server.component.Drone.class.getDeclaredField("node");
            this.componentNodeField.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            this.droneControlField = Drone.class.getDeclaredField("control");
            this.droneControlField.setAccessible(true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Scala objects cannot be referenced reliably by their binary name in all
     * compilers. Resolve the Paused state through reflection instead.
     */
    private static Enumeration.Value pausedState() {
        try {
            Class<?> stateClass = Class.forName("li.cil.oc.server.machine.Machine$State$");
            Object module = stateClass.getField("MODULE$").get(null);
            return (Enumeration.Value) stateClass.getMethod("Paused").invoke(module);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public boolean ensureMachineRunningState(Drone drone) {
        if (drone == null) return false;
        try {
            Node node = drone.node();
            if (node != null) {
                if (node.network() == null) {
                    Network.joinNewNetwork(node);
                }
                if (node instanceof Connector) {
                    Connector conn = (Connector) node;
                    conn.setLocalBufferSize(100000.0);
                    conn.changeBuffer(100000.0);
                }
            }
            if (drone.globalBufferSize() < 50000) {
                drone.globalBufferSize_$eq(100000);
            }
            drone.globalBuffer_$eq(drone.globalBufferSize());

            Object machineObj = this.droneMachineField != null ? this.droneMachineField.get(drone) : null;
            if (machineObj instanceof Machine) {
                Machine m = (Machine) machineObj;
                m.maxComponents_$eq(64);
                // A paused ghost machine never boots an EEPROM, so OC's chunk-save path
                // (Machine.save -> architecture.save) would otherwise throw NPE every save.
                // Install the inert no-op architecture exactly once.
                if (m.architecture() == null) {
                    try {
                        m.architecture_$eq(InertMachineArchitecture.INSTANCE);
                    } catch (Throwable ignored) {
                    }
                }
                if (m.node() != null) {
                    if (m.node().network() == null) {
                        Network.joinNewNetwork(m.node());
                    }
                    m.node().setLocalBufferSize(100000.0);
                    m.node().changeBuffer(100000.0);
                }
                Stack<Enumeration.Value> st = m.state();
                Enumeration.Value pausedVal = pausedState();
                if (st != null && pausedVal != null) {
                    synchronized (st) {
                        if (st.isEmpty() || !pausedVal.equals(st.top())) {
                            st.clear();
                            st.push(pausedVal);
                        }
                    }
                }
                if (this.remainingPauseField != null) {
                    this.remainingPauseField.setInt(m, Integer.MAX_VALUE);
                }
            }
            drone.setRunning(true);

            if (drone.control() == null && !drone.worldObj.isRemote
                    && this.componentNodeField != null && this.droneControlField != null) {
                Object compProxy = Proxy.newProxyInstance(
                        Component.class.getClassLoader(),
                        new Class<?>[]{Component.class},
                        (proxy, method, args) -> {
                            String name = method.getName();
                            switch (name) {
                                case "equals":
                                    return proxy == args[0];
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "toString":
                                    return "CortexDroneComponentProxy";
                                case "changeBuffer":
                                    return 0.0;
                                case "tryChangeBuffer":
                                case "canBeSeenFrom":
                                case "canConnect":
                                    return true;
                                case "address":
                                    return "00000000-0000-0000-0000-000000000000";
                            }
                            return null;
                        }
                );
                li.cil.oc.server.component.Drone ctrlComp = new li.cil.oc.server.component.Drone(drone);
                this.componentNodeField.set(ctrlComp, compProxy);
                this.droneControlField.set(drone, ctrlComp);
            }
            return drone.isRunning();
        } catch (Throwable t) {
            return false;
        }
    }
}
