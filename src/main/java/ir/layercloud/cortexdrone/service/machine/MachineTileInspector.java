package ir.layercloud.cortexdrone.service.machine;

import ir.layercloud.cortexdrone.service.world.LoadedTiles;
import ir.layercloud.cortexdrone.service.navigation.DroneGeometry;
import ir.layercloud.cortexdrone.service.world.WorldBounds;
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

    /**
     * Registry / inventory-name tokens that mark a machine.
     *
     * <p>Derived from {@link MachineAliases}' own table so there is one
     * vocabulary, not three.</p>
     */
    private static final String[] REGISTRY_TOKENS = MachineAliases.registryTokens();

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isMachineTile(World world, TileEntity te) {
        if (te == null || !(te instanceof IInventory)) return false;
        if (MachineAliases.isMachineClass(te.getClass().getName())) return true;
        if (world != null) {
            Block block = world.getBlock(te.xCoord, te.yCoord, te.zCoord);
            String registryName = block != null
                    ? Block.blockRegistry.getNameForObject(block) : "";
            if (registryName != null) {
                String name = registryName.toLowerCase();
                for (String token : REGISTRY_TOKENS) {
                    if (name.contains(token)) return true;
                }
                if (name.contains("machine_") || name.contains(":machine")) return true;
            }
        }
        String inventoryName = "";
        try {
            inventoryName = ((IInventory) te).getInventoryName() != null
                    ? ((IInventory) te).getInventoryName().toLowerCase() : "";
        } catch (Throwable ignored) {
            // A tile whose inventory name cannot be read is classified by its
            // class and registry names above.
        }
        for (String token : REGISTRY_TOKENS) {
            if (inventoryName.contains(token)) return true;
        }
        return false;
    }

    public static String machineKind(TileEntity te) {
        return MachineAliases.fromClassName(te.getClass().getSimpleName());
    }

    public static String normalizeMachineAlias(String raw) {
        return MachineAliases.normalize(raw);
    }

    /** @see MachineAliases#fromFileName(String) */
    public static String recipeFileToMachine(String fileName) {
        return MachineAliases.fromFileName(fileName);
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
                double dx = bx + DroneGeometry.CELL_CENTER - x;
                double dy = by + DroneGeometry.CELL_CENTER - y;
                double dz = bz + DroneGeometry.CELL_CENTER - z;
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
                        for (String kw : REGISTRY_TOKENS) {
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
                        if (!WorldBounds.inWorld(by)) continue;
                        TileEntity te = world.getTileEntity(bx, by, bz);
                        if (!isMachineTile(world, te)) continue;
                        if (!want.isEmpty() && !want.equals(machineKind(te))) continue;
                        out.add(te);
                    }
                }
            }
        }
        out.sort(java.util.Comparator.comparingDouble(
                tile -> LoadedTiles.distanceSq(tile, x, y, z)));
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
