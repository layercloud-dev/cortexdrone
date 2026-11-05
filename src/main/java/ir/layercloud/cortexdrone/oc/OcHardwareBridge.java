package ir.layercloud.cortexdrone.oc;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import li.cil.oc.api.network.Connector;
import li.cil.oc.common.entity.Drone;
import scala.Enumeration;
import scala.collection.mutable.Stack;

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
            this.remainingPauseField = li.cil.oc.server.machine.Machine.class.getDeclaredField("remainingPause");
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean ensureMachineRunningState(Drone drone) {
        if (drone == null) return false;
        try {
            li.cil.oc.api.network.Node node = drone.node();
            if (node != null) {
                if (node.network() == null) {
                    li.cil.oc.api.Network.joinNewNetwork(node);
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
            if (machineObj instanceof li.cil.oc.server.machine.Machine) {
                li.cil.oc.server.machine.Machine m = (li.cil.oc.server.machine.Machine) machineObj;
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
                        li.cil.oc.api.Network.joinNewNetwork(m.node());
                    }
                    m.node().setLocalBufferSize(100000.0);
                    m.node().changeBuffer(100000.0);
                }
                Stack<Enumeration.Value> st = m.state();
                if (st != null) {
                    synchronized (st) {
                        Enumeration.Value pausedVal = li.cil.oc.server.machine.Machine$State$.MODULE$.Paused();
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

            if (drone.control() == null && !drone.field_70170_p.field_72995_K
                && this.componentNodeField != null && this.droneControlField != null) {
                Object compProxy = Proxy.newProxyInstance(
                    li.cil.oc.api.network.Component.class.getClassLoader(),
                    new Class<?>[]{li.cil.oc.api.network.Component.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("changeBuffer".equals(name)) return Double.valueOf(0.0);
                            if ("tryChangeBuffer".equals(name)) return Boolean.TRUE;
                            if ("address".equals(name)) return "00000000-0000-0000-0000-000000000000";
                            if ("canBeSeenFrom".equals(name) || "canConnect".equals(name)) return Boolean.TRUE;
                            return null;
                        }
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
