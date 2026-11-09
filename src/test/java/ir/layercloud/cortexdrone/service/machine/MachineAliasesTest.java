package ir.layercloud.cortexdrone.service.machine;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Machine alias resolution is data-driven and must keep every spelling that
 * previously resolved through the 121-case switch.
 */
public class MachineAliasesTest {
    @Test
    public void commonSpellingsResolveToTheCanonicalKind() {
        assertEquals("assembly_machine", MachineAliases.normalize("assembler"));
        assertEquals("assembly_machine", MachineAliases.normalize("assembly_machine"));
        assertEquals("assembly_machine", MachineAliases.normalize("machine_assembly_factory"));
        assertEquals("chemical_plant", MachineAliases.normalize("chemplant"));
        assertEquals("blast_furnace", MachineAliases.normalize("blast_furnace_legacy"));
        assertEquals("arc_furnace", MachineAliases.normalize("arcfurnace"));
        assertEquals("arc_welder", MachineAliases.normalize("welder"));
        assertEquals("electrolyser", MachineAliases.normalize("electrolyzer"));
        assertEquals("soldering_station", MachineAliases.normalize("vacuum_circuit"));
        assertEquals("rock_mill", MachineAliases.normalize("rockmill"));
        assertEquals("pyrolysis", MachineAliases.normalize("pyro"));
    }

    @Test
    public void caseSpacesAndDashesAreNormalised() {
        assertEquals("blast_furnace", MachineAliases.normalize("  Blast Furnace  "));
        assertEquals("arc_furnace", MachineAliases.normalize("ARC-FURNACE"));
        assertEquals("chemical_plant", MachineAliases.normalize("Chemical Plant"));
    }

    @Test
    public void decorativeAffixesAreStripped() {
        assertEquals("press", MachineAliases.normalize("machine_press"));
        assertEquals("anvil", MachineAliases.normalize("anvil_hbm"));
        assertEquals("shredder", MachineAliases.normalize("shredder_machine"));
    }

    @Test
    public void recipeFileNamesMapToTheSameKind() {
        assertEquals("blast_furnace", MachineAliases.fromFileName("blast_furnace_hbm.json"));
        assertEquals("anvil", MachineAliases.fromFileName("anvil.json"));
        assertEquals("assembly_machine", MachineAliases.fromFileName("assembly_machine.json"));
        assertEquals("chemical_plant", MachineAliases.fromFileName("chemical_plant.json"));
        assertEquals("arc_furnace", MachineAliases.fromFileName("arc_furnace.json"));
    }

    @Test
    public void unknownMachineFallsBackToItsOwnName() {
        assertEquals("totally_new_machine", MachineAliases.fromFileName("totally_new_machine_hbm.json"));
        // An unknown alias normalises to an empty kind rather than a wrong one.
        assertEquals("", MachineAliases.normalize("totally_new_machine"));
    }

    @Test
    public void nullAndEmptyAreSafe() {
        assertEquals("", MachineAliases.normalize(null));
        assertEquals("", MachineAliases.normalize(""));
        assertEquals("", MachineAliases.fromFileName(null));
    }

    @Test
    public void theTableIsNonTrivialAndUnique() {
        List<String> kinds = MachineAliases.canonicalKinds();
        assertTrue("the alias table must cover many machines", kinds.size() > 30);
        assertTrue(kinds.contains("anvil"));
        assertTrue(kinds.contains("fusion"));
        assertTrue(kinds.contains("particle_accelerator"));
        List<String> spellings = MachineAliases.allSpellings();
        assertTrue(spellings.size() > 90);
    }

    @Test
    public void everyExactSpellingResolvesToAKind() {
        for (String spelling : MachineAliases.allSpellings()) {
            assertNotEquals("spelling '" + spelling + "' must resolve",
                    "", MachineAliases.normalize(spelling));
        }
    }

    @Test
    public void specificFragmentsWinOverGeneralOnes() {
        // "blastfurnacelegacy" must not be swallowed by a shorter fragment.
        assertEquals("blast_furnace", MachineAliases.fromFileName("blast_furnace_legacy.json"));
        // "vacuumcircuit" is soldering, not a refinery.
        assertEquals("soldering_station", MachineAliases.fromFileName("vacuum_circuit.json"));
        assertEquals("refinery", MachineAliases.fromFileName("vacuum_refinery.json"));
    }
}
