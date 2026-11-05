package ir.layercloud.cortexdrone.oc;

import li.cil.oc.api.machine.Architecture;
import li.cil.oc.api.machine.ExecutionResult;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Inert no-op machine architecture for OC Drones spawned by this mod.
 *
 * Spawned CortexDrone machines never boot an EEPROM (they are Java-driven, paused forever with
 * an integer-max remainingPause). Upstream OC (li.cil.oc.server.machine.Machine) then unconditionally
 * calls {@code architecture.save(nbt)} during chunk saves, dereferencing a {@code null} architecture —
 * producing one NPE stack trace per expired save timer per drone
 * ("Unexpected error saving a state of computer ... Machine.architecture() is null").
 *
 * Installing this architecture once (when machine.architecture() == null) makes the machine-save subtree
 * a zero-cost no-op: it is never initialized, never run, and never has any effect on the Paused machine
 * state or on energy/buffer bookkeeping. If a real EEPROM is later inserted and OC boots the machine,
 * OC replaces this with the real architecture on start().
 */
public final class InertMachineArchitecture implements Architecture {
    public static final InertMachineArchitecture INSTANCE = new InertMachineArchitecture();

    private InertMachineArchitecture() {
    }

    @Override
    public boolean isInitialized() {
        return true; // claim initialized so OC's save subtree never tries to re-bootstrap us
    }

    @Override
    public boolean recomputeMemory(Iterable<ItemStack> components) {
        return true; // no memory accounting for a paused ghost machine
    }

    @Override
    public boolean initialize() {
        return true; // already "initialized": nothing to boot
    }

    @Override
    public void close() {
        // inert: nothing to tear down
    }

    @Override
    public void runSynchronized() {
        // inert: paused forever, never called
    }

    @Override
    public ExecutionResult runThreaded(boolean synchronous) {
        // inert: paused forever, machine.update() never schedules this
        return new ExecutionResult() { };
    }

    @Override
    public void onSignal() {
        // inert: no signals are consumed from a paused ghost machine
    }

    @Override
    public void onConnect() {
        // inert: connections are irrelevant to the Java-driven drone
    }

    @Override
    public void load(NBTTagCompound nbt) {
        // inert: nothing to restore (the real machine state is restored by OC around us)
    }

    @Override
    public void save(NBTTagCompound nbt) {
        // inert: replaces the upstream NPE dereference with a clean save
        nbt.func_74778_a("cortexdrone_inert_architecture", "1");
    }
}
