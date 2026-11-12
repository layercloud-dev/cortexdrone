package ir.layercloud.cortexdrone.service.machine;

import java.util.*;

/**
 * Maps machine aliases, registry names and recipe-file names to one canonical
 * machine kind.
 *
 * <p>This replaces a 121-label switch plus a 50-branch {@code contains()} chain.
 * The knowledge is the same, but it is data: one ordered table of
 * {@code canonical kind -> spellings}, and one ordered table of substring
 * fragments. Adding a machine is a one-line data edit instead of a new branch,
 * and the whole mapping is unit-testable.</p>
 *
 * <p>Order matters for the substring pass: the first matching fragment wins, so
 * more specific fragments must come before general ones (for example
 * {@code blastfurnacelegacy} before {@code blastfurnace}).</p>
 */
public final class MachineAliases {
    /**
     * Canonical kind for each exact spelling.
     */
    private static final Map<String, String> EXACT = new LinkedHashMap<>();
    /**
     * Canonical kind for each substring fragment, in priority order.
     */
    private static final Map<String, String> FRAGMENTS = new LinkedHashMap<>();

    static {
        // --- exact spellings -------------------------------------------------
        exact("anvil", "anvil", "anvil_craft", "smithing");
        exact("assembly_machine", "assembly", "assembler", "assembler_machine",
                "assembly_machine", "assemblymachine", "assembly_factory", "assemblyfactory",
                "machine_assembly", "machine_assembly_machine", "machine_assembly_factory");
        exact("chemical_plant", "chemical", "chem", "chem_plant", "chemical_plant",
                "chemicalplant", "machine_chemical_plant", "chemplant");
        exact("blast_furnace", "blast", "blast_furnace", "blastfurnace", "blast_furnace_legacy");
        exact("arc_furnace", "arc", "arc_furnace", "arcfurnace", "arcfurnace_large");
        exact("arc_welder", "arc_welder", "arcwelder", "welder");
        exact("press", "press", "machine_press");
        exact("shredder", "shredder", "machine_shredder");
        exact("centrifuge", "centrifuge", "machine_centrifuge", "gascent", "gas_centrifuge");
        exact("compressor", "compressor", "machine_compressor");
        exact("mixer", "mixer", "machine_mixer");
        exact("coker", "coker", "machine_coker");
        exact("electrolyser", "electrolyser", "electrolyzer", "electrolyse",
                "electrolyser_fluid", "electrolyser_metal");
        exact("crystallizer", "crystallizer");
        exact("pyrolysis", "pyrolysis", "pyro");
        exact("rock_mill", "rockmill", "rock_mill", "rock");
        exact("soldering_station", "soldering", "soldering_station", "solder",
                "vacuum", "vacuum_circuit", "vacuumcircuit");
        exact("rotary_furnace", "rotary", "rotary_furnace");
        exact("precision_assembly", "precision", "precision_assembly", "precisionassembly", "precass");
        exact("crucible", "crucible", "smelting", "crucible_smelting");
        exact("pedestal", "pedestal", "machine_pedestal");
        exact("plasma_forge", "plasma", "plasma_forge", "plasmaforge");
        exact("fusion", "fusion");
        exact("breeder", "breeder");
        exact("cyclotron", "cyclotron");
        exact("cracking", "cracking", "cracker", "catalytic_cracker");
        exact("alkylation", "alkylation", "alkyl");
        exact("fractions", "fractions", "fraction", "fractions_tower");
        exact("refinery", "refinery", "vacuum_refinery", "vacrefinery", "distill", "vacuum_distill");
        exact("cryodistillator", "cryodistillator", "cryo", "cryodistill");
        exact("hydrotreating", "hydrotreating", "hydrotreat", "hydrotreater");
        exact("liquefactor", "liquefactor", "liquefact", "liquefier");
        exact("irradiation", "irradiation");
        exact("solidifier", "solidifier", "solidif");
        exact("supercomputer", "supercomputer", "computer");
        exact("space_assembler", "space", "space_assembler", "spaceassembler");
        exact("reforming", "reforming", "reformer", "catalytic_reformer");
        exact("atmosphere", "atmosphere");
        exact("combination", "combination");
        exact("fuelpool", "fuelpool");
        exact("purex", "purex");
        exact("particle_accelerator", "particle", "particle_accelerator");
        exact("exposure_chamber", "exposure", "exposure_chamber");

        // --- substring fragments, most specific first -----------------------
        // Ordering is the whole contract of this pass.
        fragment("blast_furnace", "blastfurnacelegacy", "blastfurnacenont", "blastfurnace");
        fragment("anvil", "anvil");
        fragment("assembly_machine", "assemblymachine");
        fragment("chemical_plant", "chemicalplant");
        fragment("arc_furnace", "arcfurnace");
        fragment("arc_welder", "arcwelder");
        fragment("precision_assembly", "precision");
        fragment("ammo_press", "ammopress", "ammo_press");
        fragment("press", "press");
        fragment("shredder", "shredder");
        fragment("centrifuge", "centrifuge", "gascent");
        fragment("compressor", "compressor");
        fragment("mixer", "mixer");
        fragment("coker", "coker");
        fragment("electrolyser", "electrolyser", "electrolyzer", "electrolyse");
        fragment("crystallizer", "crystallizer");
        fragment("pyrolysis", "pyrolysis");
        fragment("rock_mill", "rockmill", "rock_mill");
        fragment("soldering_station", "soldering", "vacuumcircuit", "vacuum_circuit");
        fragment("rotary_furnace", "rotary");
        fragment("crucible", "crucible", "smelting");
        fragment("pedestal", "pedestal");
        fragment("plasma_forge", "plasmaforge", "plasma");
        fragment("fusion", "fusion");
        fragment("breeder", "breeder");
        fragment("cyclotron", "cyclotron");
        fragment("cracking", "cracking", "cracker");
        fragment("alkylation", "alkyl");
        fragment("fractions", "fraction");
        fragment("refinery", "refinery");
        fragment("cryodistillator", "distill", "cryodistillator", "cryo");
        fragment("hydrotreating", "hydrotreat");
        fragment("liquefactor", "liquefact");
        fragment("irradiation", "irradiat");
        fragment("solidifier", "solidif");
        fragment("reforming", "reforming", "reformer");
        fragment("supercomputer", "supercomputer", "computer");
        fragment("space_assembler", "space");
        fragment("annihilator", "annihilator");
        fragment("atmosphere", "atmosphere");
        fragment("combination", "combination");
        fragment("fuelpool", "fuelpool");
        fragment("purex", "purex");
        fragment("particle_accelerator", "particle");
        fragment("exposure_chamber", "exposure");
    }

    /**
     * Ordered class-name fragments. First match wins, so specific shapes come
     * before the generic ones they contain.
     */
    private static final Map<String, String> CLASS_RULES = new LinkedHashMap<>();

    static {
        classRule("crucible", "crucible");
        classRule("liquidmetal", "crucible");
        classRule("molten", "crucible");
        classRule("pedestal", "pedestal");
        classRule("fusionplas", "plasma_forge");
        classRule("electrolyser", "electrolyser");
        classRule("electrolyzer", "electrolyser");
        classRule("gascent", "centrifuge");
        classRule("alkyl", "alkylation");
        classRule("fraction", "fractions");
        // Refinery must come before the generic vacuum/distill rules so a
        // VacuumRefinery is not classified as a soldering station.
        classRule("refinery", "refinery");
        classRule("vacrefinery", "refinery");
        classRule("vacuumdistill", "cryodistillator");
        classRule("cryodistill", "cryodistillator");
        classRule("distill", "cryodistillator");
        classRule("hydrotreat", "hydrotreating");
        classRule("liquefact", "liquefactor");
        classRule("irradiat", "irradiation");
        classRule("solidif", "solidifier");
        classRule("supercomputer", "supercomputer");
        classRule("computer", "supercomputer");
        classRule("space", "space_assembler");
        classRule("cracker", "cracking");
        classRule("cracking", "cracking");
        classRule("reformer", "reforming");
        classRule("reforming", "reforming");
        classRule("spaceassembl", "space_assembler");
        classRule("combination", "combination");
        classRule("fuelpool", "fuelpool");
        classRule("purex", "purex");
        classRule("particle", "particle_accelerator");
        classRule("exposure", "exposure_chamber");
        classRule("breeder", "breeder");
        classRule("cyclotron", "cyclotron");
        classRule("vacuumcircuit", "soldering_station");
        // Ammo-press must win over generic press: the class name contains both.
        classRule("ammopress", "ammo_press");
        classRule("annihilat", "annihilator");
        classRule("atmosphere", "atmosphere");
        classRule("autocraft", "autocrafter");
        classRule("precass", "precision_assembly");
        classRule("anvil", "anvil");
        classRule("assembly", "assembly_machine");
        classRule("chemical", "chemical_plant");
        classRule("blast", "blast_furnace");
        classRule("arcwelder", "arc_welder");
        classRule("arcfurnace", "arc_furnace");
        classRule("press", "press");
        classRule("shredder", "shredder");
        classRule("centrifuge", "centrifuge");
        classRule("compressor", "compressor");
        classRule("mixer", "mixer");
        classRule("coker", "coker");
        classRule("crystallizer", "crystallizer");
        classRule("pyrolysis", "pyrolysis");
        classRule("pyrooven", "pyrolysis");
        classRule("pyro", "pyrolysis");
        classRule("rockmill", "rock_mill");
        classRule("rock_mill", "rock_mill");
        classRule("soldering", "soldering_station");
        classRule("rotary", "rotary_furnace");
    }

    private static void classRule(String fragment, String kind) {
        CLASS_RULES.put(fragment, kind);
    }

    private MachineAliases() {
    }

    private static void exact(String kind, String... spellings) {
        for (String spelling : spellings) EXACT.put(spelling, kind);
    }

    private static void fragment(String kind, String... fragments) {
        for (String fragment : fragments) FRAGMENTS.put(fragment, kind);
    }

    /**
     * Normalises one alias to its canonical machine kind.
     *
     * <p>Tries the raw spelling, then a version with common decorative affixes
     * removed ({@code _hbm}, {@code machine_}, {@code _machine}).</p>
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String m = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        String mapped = EXACT.get(m);
        if (mapped != null) return mapped;
        String stripped = stripAffixes(m);
        mapped = EXACT.get(stripped);
        if (mapped != null) return mapped;
        return fromFragments(stripped);
    }

    /**
     * Maps a recipe file name (or any identifier) to a machine kind.
     *
     * <p>Falls back to the cleaned base name so an unknown machine is still
     * usable as its own kind instead of producing an empty filter.</p>
     */
    public static String fromFileName(String fileName) {
        if (fileName == null) return "";
        String base = fileName.toLowerCase(Locale.ROOT)
                .replace("_hbm", "").replace(".json", "").replace('-', '_');
        String mapped = fromFragments(base);
        return "".equals(mapped) ? base : mapped;
    }

    /**
     * Removes decorative affixes used by recipe and registry file names.
     */
    public static String stripAffixes(String name) {
        String mod = HbmDescriptor.MOD_ID;
        return name.replace("_" + mod, "").replace(mod + "_", "")
                .replace("machine_", "").replace("_machine", "");
    }

    /**
     * First fragment that occurs in {@code text}, or {@code ""}.
     */
    private static String fromFragments(String text) {
        String flat = text.replace("_", "");
        for (Map.Entry<String, String> entry : FRAGMENTS.entrySet()) {
            String fragment = entry.getKey();
            if (text.contains(fragment) || flat.contains(fragment.replace("_", ""))) {
                return entry.getValue();
            }
        }
        return "";
    }

    /**
     * Classifies a tile-entity class name.
     *
     * <p>Class names use a different vocabulary from registry and recipe names
     * ({@code TileEntityMachineAmmoPress}, {@code TileEntityFusionPlasmaForge}),
     * so they are matched against the same kind table with an explicit,
     * ordered rule list. This replaces the 45-branch {@code contains()} chain
     * that used to live in {@code MachineTileInspector} and could disagree with
     * the alias table for the same machine.</p>
     */
    public static String fromClassName(String className) {
        if (className == null || className.isEmpty()) return "machine";
        String cls = className.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> rule : CLASS_RULES.entrySet()) {
            if (cls.contains(rule.getKey())) return rule.getValue();
        }
        return "machine";
    }

    /**
     * True when the class name looks like a processing machine.
     */
    public static boolean isMachineClass(String className) {
        if (className == null || className.isEmpty()) return false;
        String cls = className.toLowerCase(Locale.ROOT);
        if (cls.contains("tileentitymachine")) return true;
        return !"machine".equals(fromClassName(cls)) || cls.contains("machine");
    }

    /**
     * Every canonical kind the alias table can produce.
     */
    public static List<String> canonicalKinds() {
        List<String> kinds = new ArrayList<>(new LinkedHashSet<>(EXACT.values()));
        kinds.sort(String::compareTo);
        return kinds;
    }

    /**
     * Registry/inventory tokens that identify a machine.
     *
     * <p>Derived from the same spelling table, so discovery by register name and
     * discovery by alias cannot drift apart.</p>
     */
    public static String[] registryTokens() {
        Set<String> tokens = new LinkedHashSet<>();
        for (String spelling : EXACT.keySet()) {
            if (spelling.length() >= 4) tokens.add(spelling);
        }
        for (String fragment : FRAGMENTS.keySet()) {
            if (fragment.length() >= 4) tokens.add(fragment);
        }
        // Generic tokens the tables express only implicitly.
        tokens.add("chemplant");
        tokens.add("assembler");
        tokens.add("fabricator");
        tokens.add("constructor");
        tokens.add("semiconductor");
        tokens.add("ferment");
        tokens.add("autocraft");
        tokens.add("custom");
        tokens.add("plant");
        tokens.add("ammo");
        return tokens.toArray(new String[0]);
    }

    /**
     * Every exact spelling, for documentation and validation.
     */
    public static List<String> allSpellings() {
        List<String> spellings = new ArrayList<>(EXACT.keySet());
        spellings.sort(String::compareTo);
        return spellings;
    }
}
