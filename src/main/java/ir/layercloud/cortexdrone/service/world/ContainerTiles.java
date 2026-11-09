package ir.layercloud.cortexdrone.service.world;

import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.world.World;
import net.minecraftforge.fluids.IFluidHandler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Is this tile a furnace / a general-purpose storage container?"
 *
 * <p>Extracted from {@code RegistryIntrospector}. The storage test is the subtle
 * one: a machine also has slots, so the rule is <em>explicit container words in
 * the class or inventory name</em>, or a behavioural probe showing every slot
 * accepts a plain building block. Machines implementing
 * {@link ISidedInventory} or {@link IFluidHandler} are excluded outright.</p>
 */
public final class ContainerTiles {
    /** Slots a container must have before the behavioural probe is trusted. */
    private static final int MIN_CONTAINER_SLOTS = 5;
    /** Slots that imply storage even without a telling name. */
    private static final int NAME_FREE_STORAGE_SLOTS = 9;
    /** Slots probed with a building block before calling something storage. */
    private static final int PROBE_SLOTS = 6;
    /** Class-name words that mark real storage. */
    private static final String[] CONTAINER_WORDS = {
            "chest", "crate", "barrel", "locker", "cabinet", "safe", "backpack"
    };

    private static final Map<Class<?>, Boolean> STORAGE_CACHE = new ConcurrentHashMap<>();

    private ContainerTiles() {
    }

    /** True when the tile is a furnace (vanilla or a modded equivalent). */
    public static boolean isFurnace(World world, TileEntity tile) {
        if (tile == null) return false;
        if (tile instanceof TileEntityFurnace) return true;
        if (!(tile instanceof IInventory) || world == null) return false;
        Block block = world.getBlock(tile.xCoord, tile.yCoord, tile.zCoord);
        if (block == Blocks.furnace || block == Blocks.lit_furnace) return true;
        String registryName = block != null ? Block.blockRegistry.getNameForObject(block) : "";
        List<String> tokens = LexicalItemMatcher.tokenize(registryName);
        return tokens.contains("furnace");
    }

    /** True when the tile is a general-purpose storage container. */
    public static boolean isStorage(World world, TileEntity tile) {
        if (!(tile instanceof IInventory) || tile instanceof TileEntityEnderChest) return false;
        if (tile instanceof TileEntityChest) return true;
        // A furnace has slots but is not storage.
        if (isFurnace(world, tile)) return false;

        IInventory inventory = (IInventory) tile;
        int totalSlots = inventory.getSizeInventory();
        if (totalSlots < MIN_CONTAINER_SLOTS) return false;
        if (world != null && world.getBlock(tile.xCoord, tile.yCoord, tile.zCoord) instanceof BlockChest) {
            return true;
        }
        Class<?> type = tile.getClass();
        Boolean cached = STORAGE_CACHE.get(type);
        if (cached != null) return cached;

        boolean storage = classify(inventory, tile, type, totalSlots);
        STORAGE_CACHE.put(type, storage);
        return storage;
    }

    private static boolean classify(IInventory inventory, TileEntity tile,
                                    Class<?> type, int totalSlots) {
        String className = type.getName().toLowerCase();
        // OpenComputers components/cases/racks are not general storage.
        if (className.contains("li.cil.oc") || className.contains("opencomputers")) return false;

        String inventoryName = "";
        try {
            inventoryName = inventory.getInventoryName() != null
                    ? inventory.getInventoryName().toLowerCase() : "";
        } catch (Throwable ignored) {
            // An unreadable name just means we fall back to the probe below.
        }
        String combined = className + " " + inventoryName;
        for (String word : CONTAINER_WORDS) {
            if (combined.contains(word)) return true;
        }
        // A machine exposes sided slots or fluid ports; a chest does not.
        if (tile instanceof ISidedInventory || tile instanceof IFluidHandler) return false;
        // Behavioural probe: a container accepts a plain building block everywhere.
        try {
            ItemStack probe = new ItemStack(Blocks.cobblestone, 1, 0);
            int checkSlots = Math.min(totalSlots, PROBE_SLOTS);
            for (int slot = 0; slot < checkSlots; slot++) {
                if (!inventory.isItemValidForSlot(slot, probe)) return false;
            }
        } catch (Throwable ignored) {
            // A tile that throws on the probe is assumed not to be storage.
        }
        return totalSlots >= NAME_FREE_STORAGE_SLOTS;
    }
}
