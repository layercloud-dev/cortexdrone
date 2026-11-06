package ir.layercloud.cortexdrone.service.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import lombok.experimental.UtilityClass;
import net.minecraft.block.Block;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.*;

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

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
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
            Block b = world.getBlock(te.xCoord, te.yCoord, te.zCoord);
            String reg = b != null ? Block.blockRegistry.getNameForObject(b) : "";
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
            invName = ((IInventory) te).getInventoryName() != null
                    ? ((IInventory) te).getInventoryName().toLowerCase() : "";
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
        return recipeFileToMachine(noSuffix);
    }

    private static String mapAlias(String m) {
        switch (m) {
            case "anvil":
            case "anvil_craft":
            case "smithing":
                return "anvil";
            case "assembly":
            case "assembler":
            case "assembler_machine":
            case "assembly_machine":
            case "assemblymachine":
            case "assembly_factory":
            case "assemblyfactory":
            case "machine_assembly":
            case "machine_assembly_machine":
            case "machine_assembly_factory":
                return "assembly_machine";
            case "chemical":
            case "chem":
            case "chem_plant":
            case "chemical_plant":
            case "chemicalplant":
            case "machine_chemical_plant":
            case "chemplant":
                return "chemical_plant";
            case "blast":
            case "blast_furnace":
            case "blastfurnace":
            case "blast_furnace_legacy":
                return "blast_furnace";
            case "arc":
            case "arc_furnace":
            case "arcfurnace":
            case "arcfurnace_large":
                return "arc_furnace";
            case "arc_welder":
            case "arcwelder":
            case "welder":
                return "arc_welder";
            case "press":
            case "machine_press":
                return "press";
            case "shredder":
            case "machine_shredder":
                return "shredder";
            case "centrifuge":
            case "machine_centrifuge":
                return "centrifuge";
            case "compressor":
            case "machine_compressor":
                return "compressor";
            case "mixer":
            case "machine_mixer":
                return "mixer";
            case "coker":
            case "machine_coker":
                return "coker";
            case "electrolyser":
            case "electrolyzer":
                return "electrolyser";
            case "crystallizer":
                return "crystallizer";
            case "pyrolysis":
            case "pyro":
                return "pyrolysis";
            case "rockmill":
            case "rock_mill":
            case "rock":
                return "rock_mill";
            case "soldering":
            case "soldering_station":
            case "solder":
                return "soldering_station";
            case "rotary":
            case "rotary_furnace":
                return "rotary_furnace";
            case "precision":
            case "precision_assembly":
            case "precisionassembly":
            case "precass":
                return "precision_assembly";
            case "crucible":
            case "smelting":
            case "crucible_smelting":
                return "crucible";
        }
        switch (m) {
            case "electrolyse":
            case "electrolyser_fluid":
            case "electrolyser_metal":
                return "electrolyser";
            default:
                break;
        }
        switch (m) {
            case "pedestal":
            case "machine_pedestal":
                return "pedestal";
            case "plasma":
            case "plasma_forge":
            case "plasmaforge":
                return "plasma_forge";
            case "fusion":
                return "fusion";
            case "breeder":
                return "breeder";
            case "cyclotron":
                return "cyclotron";
            case "cracking":
            case "cracker":
            case "catalytic_cracker":
                return "cracking";
            case "alkylation":
            case "alkyl":
                return "alkylation";
            case "fractions":
            case "fraction":
            case "fractions_tower":
                return "fractions";
            case "refinery":
            case "vacuum_refinery":
            case "vacrefinery":
            case "distill":
            case "vacuum_distill":
            case "vacuumdistill":
                return "refinery";
            case "cryodistillator":
            case "cryo":
            case "cryodistill":
                return "cryodistillator";
            case "hydrotreating":
            case "hydrotreat":
            case "hydrotreater":
                return "hydrotreating";
            case "liquefactor":
            case "liquefact":
            case "liquefier":
                return "liquefactor";
            case "irradiation":
                return "irradiation";
            case "solidifier":
            case "solidif":
                return "solidifier";
            case "supercomputer":
            case "computer":
                return "supercomputer";
            case "space":
            case "space_assembler":
            case "spaceassembler":
                return "space_assembler";
            case "reforming":
            case "reformer":
            case "catalytic_reformer":
                return "reforming";
            case "atmosphere":
                return "atmosphere";
            case "combination":
                return "combination";
            case "fuelpool":
                return "fuelpool";
            case "purex":
                return "purex";
            case "particle":
            case "particle_accelerator":
                return "particle_accelerator";
            case "exposure":
            case "exposure_chamber":
                return "exposure_chamber";
            case "vacuum":
            case "vacuum_circuit":
            case "vacuumcircuit":
                return "soldering_station";
            case "gascent":
            case "gas_centrifuge":
                return "centrifuge";
            default:
                return null;
        }
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
        if (base.contains("soldering") || base.contains("vacuumcircuit") || base.contains("vacuum_circuit"))
            return "soldering_station";
        if (base.contains("rotary")) return "rotary_furnace";
        if (base.contains("crucible") || base.contains("smelting")) return "crucible";
        if (base.contains("electrolyser") || base.contains("electrolyzer") || base.contains("electrolyse") && !base.contains("electrolyser_fluid") && !base.contains("electrolyser_metal"))
            return "electrolyser";
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
        if (base.contains("distill") || base.contains("cryodistillator") || base.contains("cryo"))
            return "cryodistillator";
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
        List<TileEntity> out = new ArrayList<>();
        if (world == null) return out;
        double r2 = (double) radius * radius;
        String want = machineFilter == null ? "" : normalizeMachineAlias(machineFilter);
        if (world.loadedTileEntityList != null) {
            for (Object o : world.loadedTileEntityList) {
                if (!(o instanceof TileEntity)) continue;
                TileEntity te = (TileEntity) o;
                int bx = te.xCoord;
                int by = te.yCoord;
                int bz = te.zCoord;
                double dx = bx + 0.5 - x;
                double dy = by + 0.5 - y;
                double dz = bz + 0.5 - z;
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                if (!isMachineTile(world, te)) continue;
                if (!want.isEmpty()) {
                    String kind = machineKind(te);
                    String blockReg = "";
                    try {
                        Block b = world.getBlock(bx, by, bz);
                        blockReg = b != null ? Block.blockRegistry.getNameForObject(b) : "";
                    } catch (Throwable ignored) {
                    }
                    String invName = "";
                    try {
                        invName = ((IInventory) te).getInventoryName() != null ? ((IInventory) te).getInventoryName().toLowerCase() : "";
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
        if (out.isEmpty() && world.loadedTileEntityList == null) {
            int cx = MathHelper.floor_double(x);
            int cy = MathHelper.floor_double(y);
            int cz = MathHelper.floor_double(z);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int bx = cx + dx;
                        int by = cy + dy;
                        int bz = cz + dz;
                        if (by < 1 || by > 254) continue;
                        TileEntity te = world.getTileEntity(bx, by, bz);
                        if (!isMachineTile(world, te)) continue;
                        if (!want.isEmpty() && !want.equals(machineKind(te))) continue;
                        out.add(te);
                    }
                }
            }
        }
        out.sort((a, b) -> {
            double da = (a.xCoord + 0.5 - x) * (a.xCoord + 0.5 - x)
                    + (a.yCoord + 0.5 - y) * (a.yCoord + 0.5 - y)
                    + (a.zCoord + 0.5 - z) * (a.zCoord + 0.5 - z);
            double db = (b.xCoord + 0.5 - x) * (b.xCoord + 0.5 - x)
                    + (b.yCoord + 0.5 - y) * (b.yCoord + 0.5 - y)
                    + (b.zCoord + 0.5 - z) * (b.zCoord + 0.5 - z);
            return Double.compare(da, db);
        });
        return out;
    }

    public static JsonObject describeMachineTile(World world, TileEntity te) {
        JsonObject o = new JsonObject();
        o.addProperty("x", te.xCoord);
        o.addProperty("y", te.yCoord);
        o.addProperty("z", te.zCoord);
        o.addProperty("kind", machineKind(te));
        o.addProperty("class", te.getClass().getName());
        Block b = world != null ? world.getBlock(te.xCoord, te.yCoord, te.zCoord) : null;
        int meta = world != null ? world.getBlockMetadata(te.xCoord, te.yCoord, te.zCoord) : 0;
        String reg = b != null ? Block.blockRegistry.getNameForObject(b) : "";
        o.addProperty("registryName", reg != null ? reg : "");
        o.addProperty("blockName", b != null ? ItemClassifier.getBlockDisplayName(b, meta) : "Unknown");
        o.addProperty("hasPower", hasPower(te));
        o.addProperty("hasFluid", hasFluid(te));
        if (te instanceof IInventory) {
            IInventory inv = (IInventory) te;
            o.addProperty("slotCount", inv.getSizeInventory());
            int used = 0;
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                net.minecraft.item.ItemStack st = inv.getStackInSlot(i);
                if (st != null && st.stackSize > 0) used++;
            }
            o.addProperty("usedSlots", used);
            JsonArray items = new JsonArray();
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                net.minecraft.item.ItemStack st = inv.getStackInSlot(i);
                if (st != null && st.stackSize > 0) items.add(ItemClassifier.itemStackToJson(i, st));
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
        List<Class<?>> out = new ArrayList<>();
        while (c != null && c != Object.class) {
            out.add(c);
            c = c.getSuperclass();
        }
        return out;
    }
}
