package ir.layercloud.cortexdrone.service.machine;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Class-name classification must agree with the alias table.
 *
 * <p>Regression risk: the tile inspector had its own 45-branch {@code contains}
 * chain, so {@code scan_machines} and recipe lookup could disagree about the
 * same machine. Both now read one ordered rule table.</p>
 */
public class MachineAliasesClassNameTest {
    @Test
    public void hbmMachineClassesResolveToCanonicalKinds() {
        assertEquals("blast_furnace",
                MachineAliases.fromClassName("com.hbm.tileentity.machine.TileEntityMachineBlastFurnace"));
        assertEquals("arc_furnace",
                MachineAliases.fromClassName("TileEntityMachineArcFurnace"));
        assertEquals("chemical_plant",
                MachineAliases.fromClassName("TileEntityMachineChemicalPlant"));
        assertEquals("assembly_machine",
                MachineAliases.fromClassName("TileEntityMachineAssemblyMachine"));
        assertEquals("electrolyser",
                MachineAliases.fromClassName("TileEntityElectrolyser"));
        assertEquals("crucible",
                MachineAliases.fromClassName("TileEntityCrucible"));
        assertEquals("centrifuge",
                MachineAliases.fromClassName("TileEntityMachineGasCent"));
        assertEquals("soldering_station",
                MachineAliases.fromClassName("TileEntityMachineVacuumCircuit"));
    }

    @Test
    public void ammoPressWinsOverGenericPress() {
        assertEquals("ammo_press",
                MachineAliases.fromClassName("TileEntityMachineAmmoPress"));
        assertEquals("press", MachineAliases.fromClassName("TileEntityMachinePress"));
    }

    @Test
    public void specificFuelShapesWinOverTheirParents() {
        assertEquals("alkylation", MachineAliases.fromClassName("TileEntityMachineAlkylation"));
        assertEquals("fractions", MachineAliases.fromClassName("TileEntityMachineFractionsTower"));
        assertEquals("cracking", MachineAliases.fromClassName("TileEntityMachineCatalyticCracker"));
        assertEquals("reforming", MachineAliases.fromClassName("TileEntityMachineCatalyticReformer"));
        assertEquals("refinery", MachineAliases.fromClassName("TileEntityMachineRefinery"));
    }

    @Test
    public void unknownMachineClassesAreStillMachines() {
        assertEquals("machine", MachineAliases.fromClassName("TileEntityUnknownThing"));
        assertTrue(MachineAliases.isMachineClass("TileEntityMachineSomething"));
    }

    @Test
    public void nullAndEmptyAreSafe() {
        assertEquals("machine", MachineAliases.fromClassName(null));
        assertEquals("machine", MachineAliases.fromClassName(""));
        assertFalse(MachineAliases.isMachineClass(null));
    }

    @Test
    public void registryTokensAreDerivedAndNonTrivial() {
        String[] tokens = MachineAliases.registryTokens();
        assertTrue("the token list must cover the alias table", tokens.length > 40);
        boolean hasBlast = false;
        for (String token : tokens) {
            if ("blast_furnace".equals(token) || "blastfurnace".equals(token)) {
                hasBlast = true;
                break;
            }
        }
        assertTrue("a blast furnace token must exist", hasBlast);
    }
}
