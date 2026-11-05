package ir.layercloud.cortexdrone.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.experimental.UtilityClass;
import net.minecraft.block.Block;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Discovers HBM/modified processing machines by scanning loaded TileEntities
 * plus block-registry name tokens. No hardcoded item lists; discovery is driven
 * by class names, block registry identifiers, IInventory/IPower capability flags,
 * and power/fluid interfaces (all discovered via reflection/class hierarchy).
 */
@UtilityClass
public final class MachineTileInspector {

    public static final String[] MACHINE_KEYWORDS = new String[]{
        "anvil", "assembly", "chemical", "blast", "arc", "press", "shredder",
        "centrifuge", "compressor", "mixer", "coker", "pyrolysis", "electrolyser",
        "electrolyzer", "crystallizer", "semiconductor", "soldering", "rockmill",
        "rock_mill", "rotary", "combination", "assembler", "chemplant", "plant",
        "fabricator", "constructor", "crucible", "electrolyse", "pedestal",
        "plasma", "fusion", "breeder", "cyclotron", "pyro", "cracking", "alkyl",
        "fraction", "refinery", "vacuum", "distill", "hydrotreat", "liquefact",
        "irradiat", "solidif", "supercomputer", "space", "combination", "ferment",
        "ammo", "autocraft", "annihilat", "atmosphere", "custom", "particle",
        "gascent", "pyrooven", "purex", "exposure"
    };

    public static boolean isMachineTile(World world, TileEntity te) {
        if (te == null) return false;
        if (!(te instanceof IInventory)) return false;
        String cls = te.getClass().getName().toLowerCase();
        if (cls.contains("tileentitymachine") || cls.contains("tileentitycrucible")
            || cls.contains("tileentityelectrolyser") || cls.contains("tileentitypedestal")
            || cls.contains("tileentityfusionplas") || cls.contains("anvil")
            || cls.contains("assemblymachine") || cls.contains("chemicalplant")
            || cls.contains("blastfurnace") || cls.contains("arcwelder")
            || cls.contains("arcfurnace") || cls.contains("press") || cls.contains("shredder")
            || cls.contains("centrifuge") || cls.contains("compressor") || cls.contains("mixer")
            || cls.contains("coker") || cls.contains("electrolyser") || cls.contains("electrolyzer")
            || cls.contains("crystallizer") || cls.contains("pyrolysis") || cls.contains("rockmill")
            || cls.contains("soldering") || cls.contains("rotary") || cls.contains("vacuum")
            || cls.contains("distill") || cls.contains("refinery") || cls.contains("fraction")
            || cls.contains("cracker") || cls.contains("reformer") || cls.contains("alkyl")
            || cls.contains("hydrotreat") || cls.contains("liquefact") || cls.contains("irradiat")
            || cls.contains("solidif") || cls.contains("breeder") || cls.contains("cyclotron")
            || cls.contains("gascent") || cls.contains("solidifier") || cls.contains("pyrooven")) {
            return true;
        }
        if (world != null) {
            Block b = world.func_147439_a(te.field_145851_c, te.field_145848_d, te.field_145849_e);
            String reg = b != null ? Block.field_149771_c.func_148750_c(b) : "";
            if (reg != null) {
                String r = reg.toLowerCase();
                for (String kw : MACHINE_KEYWORDS) {
                    if (r.contains(kw)) return true;
                }
                if (r.contains("machine_") || r.contains(":machine")) return true;
            }
        }
        String invName = "";
        try {
            if (te instanceof IInventory) {
                invName = ((IInventory) te).func_145825_b() != null ? ((IInventory) te).func_145825_b().toLowerCase() : "";
            }
        } catch (Throwable ignored) {
        }
        for (String kw : MACHINE_KEYWORDS) {
            if (invName.contains(kw)) return true;
        }
        return false;
    }

    public static String machineKind(TileEntity te) {
        String cls = te.getClass().getSimpleName().toLowerCase();
        if (cls.contains("crucible") && !cls.contains("class")) return "crucible";
        if (cls.contains("liquidmetal") || cls.contains("molten")) return "crucible";
        if (cls.contains("pedestal")) return "pedestal";
        if (cls.contains("fusionplas")) return "plasma_forge";
        if (cls.contains("electrolyser") || cls.contains("electrolyzer")) return "electrolyser";
        if (cls.contains("gascent")) return "centrifuge";
        if (cls.contains("alkyl")) return "alkylation";
        if (cls.contains("fraction")) return "fractions";
        if (cls.contains("cracker") || cls.contains("cracking")) return "cracking";
        if (cls.contains("reformer") || cls.contains("reforming")) return "reforming";
        if (cls.contains("space") && cls.contains("assembl")) return "space_assembler";
        if (cls.contains("combination")) return "combination";
        if (cls.contains("fuelpool")) return "fuelpool";
        if (cls.contains("purex")) return "purex";
        if (cls.contains("particle")) return "particle_accelerator";
        if (cls.contains("exposure")) return "exposure_chamber";
        if (cls.contains("breeder")) return "breeder";
        if (cls.contains("cyclotron")) return "cyclotron";
        if (cls.contains("vacuumcircuit")) return "soldering_station";
        // Ammo-press must win over generic press: TileEntityMachineAmmoPress contains
        // both 'press' and 'ammo'; the generic press branch below would shadow it.
        if (cls.contains("ammo") && cls.contains("press")) return "ammo_press";
        if (cls.contains("annihilat")) return "annihilator";
        if (cls.contains("atmosphere")) return "atmosphere";
        if (cls.contains("autocraft")) return "autocrafter";
        if (cls.contains("precass")) return "precision_assembly";
        if (cls.contains("anvil")) return "anvil";
        if (cls.contains("assembly")) return "assembly_machine";
        if (cls.contains("chemical")) return "chemical_plant";
        if (cls.contains("blast")) return "blast_furnace";
        if (cls.contains("arcwelder")) return "arc_welder";
        if (cls.contains("arcfurnace")) return "arc_furnace";
        if (cls.contains("press")) return "press";
        if (cls.contains("shredder")) return "shredder";
        if (cls.contains("centrifuge")) return "centrifuge";
        if (cls.contains("compressor")) return "compressor";
        if (cls.contains("mixer")) return "mixer";
        if (cls.contains("coker")) return "coker";
        if (cls.contains("crystallizer")) return "crystallizer";
        if (cls.contains("pyrolysis") || cls.contains("pyrooven") || cls.contains("pyro")) return "pyrolysis";
        if (cls.contains("rockmill") || cls.contains("rock_mill")) return "rock_mill";
        if (cls.contains("soldering")) return "soldering_station";
        if (cls.contains("rotary")) return "rotary_furnace";
        return "machine";
    }

    public static String normalizeMachineAlias(String raw) {
        if (raw == null) return "";
        String m = raw.trim().toLowerCase().replace('-', '_').replace(' ', '_');
        String mapped = mapAlias(m);
        if (mapped != null) return mapped;
        String noSuffix = m.replace("_hbm", "").replace("hbm_", "").replace("machine_", "").replace("_machine", "");
        String mapped2 = mapAlias(noSuffix);
        if (mapped2 != null) return mapped2;
        // Fully dynamic fallback: token-substring match so any new machine
        // kind (e.g. custom-machines variants) still resolves without a
        // hardcoded entry, mirroring recipeFileToMachine's contains() logic.
        String dynamic = recipeFileToMachine(noSuffix);
        if (dynamic != null && !dynamic.isEmpty() && !dynamic.equals(noSuffix)) return dynamic;
        return dynamic != null ? dynamic : noSuffix;
    }

    private static String mapAlias(String m) {
        if (m.equals("anvil") || m.equals("anvil_craft") || m.equals("smithing")) return "anvil";
        if ((m.equals("assembly") || m.equals("assembler") || m.equals("assembler_machine") || m.equals("assembly_machine")
            || m.equals("assemblymachine") || m.equals("assembly_factory") || m.equals("assemblyfactory")
            || m.equals("machine_assembly") || m.equals("machine_assembly_machine") || m.equals("machine_assembly_factory"))) {
            return "assembly_machine";
        }
        if (m.equals("chemical") || m.equals("chem") || m.equals("chem_plant") || m.equals("chemical_plant")
            || m.equals("chemicalplant") || m.equals("machine_chemical_plant") || m.equals("chemplant")) {
            return "chemical_plant";
        }
        if (m.equals("blast") || m.equals("blast_furnace") || m.equals("blastfurnace") || m.equals("blast_furnace_legacy")) {
            return "blast_furnace";
        }
        if (m.equals("arc") || m.equals("arc_furnace") || m.equals("arcfurnace") || m.equals("arcfurnace_large")) {
            return "arc_furnace";
        }
        if (m.equals("arc_welder") || m.equals("arcwelder") || m.equals("welder")) return "arc_welder";
        if (m.equals("press") || m.equals("machine_press")) return "press";
        if (m.equals("shredder") || m.equals("machine_shredder")) return "shredder";
        if (m.equals("centrifuge") || m.equals("machine_centrifuge")) return "centrifuge";
        if (m.equals("compressor") || m.equals("machine_compressor")) return "compressor";
        if (m.equals("mixer") || m.equals("machine_mixer")) return "mixer";
        if (m.equals("coker") || m.equals("machine_coker")) return "coker";
        if (m.equals("electrolyser") || m.equals("electrolyzer")) return "electrolyser";
        if (m.equals("crystallizer")) return "crystallizer";
        if (m.equals("pyrolysis") || m.equals("pyro")) return "pyrolysis";
        if (m.equals("rockmill") || m.equals("rock_mill") || m.equals("rock")) return "rock_mill";
        if (m.equals("soldering") || m.equals("soldering_station") || m.equals("solder")) return "soldering_station";
        if (m.equals("rotary") || m.equals("rotary_furnace")) return "rotary_furnace";
        if (m.equals("precision") || m.equals("precision_assembly") || m.equals("precisionassembly") || m.equals("precass")) {
            return "precision_assembly";
        }
        if (m.equals("crucible") || m.equals("smelting") || m.equals("crucible_smelting")) return "crucible";
        if (m.equals("electrolyser") || m.equals("electrolyzer") || m.equals("electrolyse")
            || m.equals("electrolyser_fluid") || m.equals("electrolyser_metal")) return "electrolyser";
        if (m.equals("pedestal") || m.equals("machine_pedestal")) return "pedestal";
        if (m.equals("plasma") || m.equals("plasma_forge") || m.equals("plasmaforge")) return "plasma_forge";
        if (m.equals("fusion")) return "fusion";
        if (m.equals("breeder")) return "breeder";
        if (m.equals("cyclotron")) return "cyclotron";
        if (m.equals("cracking") || m.equals("cracker") || m.equals("catalytic_cracker")) return "cracking";
        if (m.equals("alkylation") || m.equals("alkyl")) return "alkylation";
        if (m.equals("fractions") || m.equals("fraction") || m.equals("fractions_tower")) return "fractions";
        if (m.equals("refinery") || m.equals("vacuum_refinery") || m.equals("vacrefinery") || m.equals("distill")
            || m.equals("vacuum_distill") || m.equals("vacuumdistill")) return "refinery";
        if (m.equals("cryodistillator") || m.equals("cryo") || m.equals("cryodistill")) return "cryodistillator";
        if (m.equals("hydrotreating") || m.equals("hydrotreat") || m.equals("hydrotreater")) return "hydrotreating";
        if (m.equals("liquefactor") || m.equals("liquefact") || m.equals("liquefier")) return "liquefactor";
        if (m.equals("irradiation")) return "irradiation";
        if (m.equals("solidifier") || m.equals("solidif")) return "solidifier";
        if (m.equals("supercomputer") || m.equals("computer")) return "supercomputer";
        if (m.equals("space") || m.equals("space_assembler") || m.equals("spaceassembler")) return "space_assembler";
        if (m.equals("reforming") || m.equals("reformer") || m.equals("catalytic_reformer")) return "reforming";
        if (m.equals("atmosphere")) return "atmosphere";
        if (m.equals("combination")) return "combination";
        if (m.equals("fuelpool")) return "fuelpool";
        if (m.equals("purex")) return "purex";
        if (m.equals("particle") || m.equals("particle_accelerator")) return "particle_accelerator";
        if (m.equals("exposure") || m.equals("exposure_chamber")) return "exposure_chamber";
        if (m.equals("vacuum") || m.equals("vacuum_circuit") || m.equals("vacuumcircuit")) return "soldering_station";
        if (m.equals("gascent") || m.equals("gas_centrifuge")) return "centrifuge";
        return null;
    }

    public static String recipeFileToMachine(String fileName) {
        String base = fileName.toLowerCase().replace("_hbm", "").replace(".json", "").replace("-", "_");
        String flat = base.replace("_", "");
        if (flat.contains("blastfurnacelegacy") || flat.contains("blastfurnacenont")) return "blast_furnace";
        if (base.contains("anvil")) return "anvil";
        if (base.contains("assemblymachine") || base.equals("assembly")) return "assembly_machine";
        if (base.contains("chemicalplant") || base.equals("chemical")) return "chemical_plant";
        if (base.contains("blastfurnace")) return "blast_furnace";
        if (base.contains("arcfurnace")) return "arc_furnace";
        if (base.contains("arcwelder")) return "arc_welder";
        if (base.contains("precision")) return "precision_assembly";
        if (base.contains("ammopress") || base.contains("ammo_press")) return "ammo_press";
        if (base.contains("press")) return "press";
        if (base.contains("shredder")) return "shredder";
        if (base.contains("centrifuge")) return "centrifuge";
        if (base.contains("compressor")) return "compressor";
        if (base.contains("mixer")) return "mixer";
        if (base.contains("coker")) return "coker";
        if (base.contains("electrolyser") || base.contains("electrolyzer")) return "electrolyser";
        if (base.contains("crystallizer")) return "crystallizer";
        if (base.contains("pyrolysis")) return "pyrolysis";
        if (base.contains("rockmill") || base.contains("rock_mill")) return "rock_mill";
        if (base.contains("soldering") || base.contains("vacuumcircuit") || base.contains("vacuum_circuit")) return "soldering_station";
        if (base.contains("rotary")) return "rotary_furnace";
        if (base.contains("crucible") || base.contains("smelting")) return "crucible";
        if (base.contains("electrolyser") || base.contains("electrolyzer") || base.contains("electrolyse") && !base.contains("electrolyser_fluid") && !base.contains("electrolyser_metal")) return "electrolyser";
        if (base.contains("electrolyser_fluid") || base.contains("electrolyser_metal")) return "electrolyser";
        if (base.contains("crystallizer")) return "crystallizer";
        if (base.contains("pyro")) return "pyrolysis";
        if (base.contains("rockmill") || base.contains("rock_mill")) return "rock_mill";
        if (base.contains("pedestal")) return "pedestal";
        if (base.contains("plasmaforge") || base.contains("plasma")) return "plasma_forge";
        if (base.contains("fusion")) return "fusion";
        if (base.contains("breeder")) return "breeder";
        if (base.contains("cyclotron")) return "cyclotron";
        if (base.contains("cracking") || base.contains("cracker")) return "cracking";
        if (base.contains("alkyl")) return "alkylation";
        if (base.contains("fraction")) return "fractions";
        if (base.contains("refinery")) return "refinery";
        if (base.contains("vacuum") && !base.contains("vacuumcircuit")) return "refinery";
        if (base.contains("distill") || base.contains("cryodistillator") || base.contains("cryo")) return "cryodistillator";
        if (base.contains("hydrotreat")) return "hydrotreating";
        if (base.contains("liquefact")) return "liquefactor";
        if (base.contains("irradiat")) return "irradiation";
        if (base.contains("solidif")) return "solidifier";
        if (base.contains("reforming") || base.contains("reformer")) return "reforming";
        if (base.contains("supercomputer") || base.contains("computer")) return "supercomputer";
        if (base.contains("space")) return "space_assembler";
        if (base.contains("gascent")) return "centrifuge";
        if (base.contains("annihilator")) return "annihilator";
        if (base.contains("atmosphere")) return "atmosphere";
        if (base.contains("combination")) return "combination";
        if (base.contains("fuelpool")) return "fuelpool";
        if (base.contains("purex")) return "purex";
        if (base.contains("particle")) return "particle_accelerator";
        if (base.contains("exposure")) return "exposure_chamber";
        return base;
    }

    public static List<TileEntity> findMachineTiles(World world, double x, double y, double z, int radius, String machineFilter) {
        List<TileEntity> out = new ArrayList<TileEntity>();
        if (world == null) return out;
        double r2 = (double) radius * radius;
        String want = machineFilter == null ? "" : normalizeMachineAlias(machineFilter);
        if (world.field_147482_g != null) {
            for (Object o : world.field_147482_g) {
                if (!(o instanceof TileEntity)) continue;
                TileEntity te = (TileEntity) o;
                int bx = te.field_145851_c;
                int by = te.field_145848_d;
                int bz = te.field_145849_e;
                double dx = bx + 0.5 - x;
                double dy = by + 0.5 - y;
                double dz = bz + 0.5 - z;
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                if (!isMachineTile(world, te)) continue;
                if (!want.isEmpty()) {
                    String kind = machineKind(te);
                    String blockReg = "";
                    try {
                        Block b = world.func_147439_a(bx, by, bz);
                        blockReg = b != null ? Block.field_149771_c.func_148750_c(b) : "";
                    } catch (Throwable ignored) {
                    }
                    String invName = "";
                    try {
                        invName = ((IInventory) te).func_145825_b() != null ? ((IInventory) te).func_145825_b().toLowerCase() : "";
                    } catch (Throwable ignored) {
                    }
                    boolean match = want.equals(kind) || normalizeMachineAlias(blockReg != null ? blockReg : "").equals(want)
                        || invName.contains(want) || te.getClass().getName().toLowerCase().contains(want.replace("_", ""));
                    if (!match) {
                        boolean partial = false;
                        for (String kw : MACHINE_KEYWORDS) {
                            if (want.contains(kw) || kw.contains(want)) {
                                partial = true;
                                break;
                            }
                        }
                        if (!partial) continue;
                    }
                }
                out.add(te);
            }
        }
        if (out.isEmpty() && world.field_147482_g == null) {
            int cx = MathHelper.func_76128_c(x);
            int cy = MathHelper.func_76128_c(y);
            int cz = MathHelper.func_76128_c(z);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int bx = cx + dx;
                        int by = cy + dy;
                        int bz = cz + dz;
                        if (by < 1 || by > 254) continue;
                        TileEntity te = world.func_147438_o(bx, by, bz);
                        if (te == null || !isMachineTile(world, te)) continue;
                        if (!want.isEmpty() && !want.equals(machineKind(te))) continue;
                        out.add(te);
                    }
                }
            }
        }
        double fx = x;
        double fy = y;
        double fz = z;
        java.util.Collections.sort(out, new Comparator<TileEntity>() {
            @Override
            public int compare(TileEntity a, TileEntity b) {
                double da = (a.field_145851_c + 0.5 - fx) * (a.field_145851_c + 0.5 - fx)
                    + (a.field_145848_d + 0.5 - fy) * (a.field_145848_d + 0.5 - fy)
                    + (a.field_145849_e + 0.5 - fz) * (a.field_145849_e + 0.5 - fz);
                double db = (b.field_145851_c + 0.5 - fx) * (b.field_145851_c + 0.5 - fx)
                    + (b.field_145848_d + 0.5 - fy) * (b.field_145848_d + 0.5 - fy)
                    + (b.field_145849_e + 0.5 - fz) * (b.field_145849_e + 0.5 - fz);
                return Double.compare(da, db);
            }
        });
        return out;
    }

    public static JsonObject describeMachineTile(World world, TileEntity te) {
        JsonObject o = new JsonObject();
        o.addProperty("x", te.field_145851_c);
        o.addProperty("y", te.field_145848_d);
        o.addProperty("z", te.field_145849_e);
        o.addProperty("kind", machineKind(te));
        o.addProperty("class", te.getClass().getName());
        Block b = world != null ? world.func_147439_a(te.field_145851_c, te.field_145848_d, te.field_145849_e) : null;
        int meta = world != null ? world.func_72805_g(te.field_145851_c, te.field_145848_d, te.field_145849_e) : 0;
        String reg = b != null ? Block.field_149771_c.func_148750_c(b) : "";
        o.addProperty("registryName", reg != null ? reg : "");
        o.addProperty("blockName", b != null ? ItemClassifier.getBlockDisplayName(b, meta) : "Unknown");
        o.addProperty("hasPower", hasPower(te));
        o.addProperty("hasFluid", hasFluid(te));
        if (te instanceof IInventory) {
            IInventory inv = (IInventory) te;
            o.addProperty("slotCount", inv.func_70302_i_());
            int used = 0;
            for (int i = 0; i < inv.func_70302_i_(); i++) {
                net.minecraft.item.ItemStack st = inv.func_70301_a(i);
                if (st != null && st.field_77994_a > 0) used++;
            }
            o.addProperty("usedSlots", used);
            JsonArray items = new JsonArray();
            for (int i = 0; i < inv.func_70302_i_(); i++) {
                net.minecraft.item.ItemStack st = inv.func_70301_a(i);
                if (st != null && st.field_77994_a > 0) items.add(ItemClassifier.itemStackToJson(i, st));
            }
            o.add("items", items);
        }
        return o;
    }

    public static boolean hasPower(TileEntity te) {
        return implementsInterface(te, "IEnergyReceiver") || implementsInterface(te, "IEnergyHandler")
            || implementsInterface(te, "IEnergyReceiverMK2") || hasMethod(te, "getPower") || hasMethod(te, "getMaxPower");
    }

    public static boolean hasFluid(TileEntity te) {
        return implementsInterface(te, "IFluidStandardTransceiver") || implementsInterface(te, "IFluidStandardTransceiverMK2")
            || implementsInterface(te, "IFluidHandler") || hasMethod(te, "getReceivingTanks") || hasMethod(te, "getSendingTanks");
    }

    private static boolean implementsInterface(Object o, String suffix) {
        for (Class<?> c : allClasses(o.getClass())) {
            for (Class<?> i : c.getInterfaces()) {
                if (i.getName().contains(suffix)) return true;
            }
        }
        return false;
    }

    private static boolean hasMethod(Object o, String name) {
        for (Class<?> c : allClasses(o.getClass())) {
            try {
                c.getMethod(name);
                return true;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return false;
    }

    private static List<Class<?>> allClasses(Class<?> c) {
        List<Class<?>> out = new ArrayList<Class<?>>();
        while (c != null && c != Object.class) {
            out.add(c);
            c = c.getSuperclass();
        }
        return out;
    }

    static Set<String> allMachineTypes() {
        return new HashSet<String>(Arrays.asList(MACHINE_KEYWORDS));
    }
}
