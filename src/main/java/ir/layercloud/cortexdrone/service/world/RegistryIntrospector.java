package ir.layercloud.cortexdrone.service.world;

import lombok.experimental.UtilityClass;
import net.minecraft.block.*;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.*;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.common.IShearable;
import net.minecraftforge.fluids.*;
import net.minecraftforge.oredict.OreDictionary;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic runtime introspection over Forge's OreDictionary, FluidRegistry, BiomeDictionary,
 * EntityList, and Block/Item/TileEntity hierarchies. Eliminates hardcoded item/ore/mob/machine lists.
 */
@UtilityClass
public final class RegistryIntrospector {
    private static final Random PROBE_RANDOM = new Random(42L);
    private static final Map<Integer, Boolean> ORE_BLOCK_CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, Boolean> LOG_BLOCK_CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, Boolean> LEAF_BLOCK_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> STORAGE_TILE_CLASS_CACHE = new ConcurrentHashMap<>();

    private static volatile Set<String> cachedUndergroundTokens = Collections.emptySet();
    private static volatile Set<String> cachedCropTokens = Collections.emptySet();
    private static volatile int lastOreDictCount = -1;

    private static Method blockIsWoodMethod = null;
    private static Method blockIsLeavesMethod = null;
    private static Method blockGetHarvestToolMethod = null;
    private static Method entityIsCreatureTypeMethod = null;

    static {
        try {
            blockIsWoodMethod = Block.class.getMethod("isWood", IBlockAccess.class, int.class, int.class, int.class);
        } catch (Throwable ignored) {
        }
        try {
            blockIsLeavesMethod = Block.class.getMethod("isLeaves", IBlockAccess.class, int.class, int.class, int.class);
        } catch (Throwable ignored) {
        }
        try {
            blockGetHarvestToolMethod = Block.class.getMethod("getHarvestTool", int.class);
        } catch (Throwable ignored) {
        }
        try {
            entityIsCreatureTypeMethod = Entity.class.getMethod("isCreatureType", EnumCreatureType.class, boolean.class);
        } catch (Throwable ignored) {
        }
    }

    public static int blockCacheKey(Block b, int meta) {
        return (Block.getIdFromBlock(b) << 4) | (meta & 15);
    }

    /**
     * Normalizes block metadata to its canonical item/sub-block damage value using Block.damageDropped(meta).
     * Automatically strips orientation bits on rotated logs, pillars, quartz, and modded directional blocks.
     */
    public static int normalizeBlockMeta(Block b, int meta) {
        if (b == null || b == Blocks.air) return 0;
        try {
            int droppedMeta = b.damageDropped(meta);
            if (droppedMeta >= 0 && droppedMeta < 16) {
                return droppedMeta;
            }
        } catch (Throwable ignored) {
        }
        return meta & 15;
    }

    public static List<String> getOreDictNames(ItemStack stack) {
        List<String> names = new ArrayList<>();
        if (stack == null || stack.getItem() == null) return names;
        try {
            int[] ids = OreDictionary.getOreIDs(stack);
            if (ids != null) {
                for (int id : ids) {
                    String name = OreDictionary.getOreName(id);
                    if (name != null && !name.isEmpty() && !"Unknown".equalsIgnoreCase(name)) {
                        names.add(name);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    public static List<String> getOreDictNames(Block block, int meta) {
        if (block == null || block == Blocks.air) return new ArrayList<>();
        try {
            Item item = Item.getItemFromBlock(block);
            if (item != null) {
                int dmg = normalizeBlockMeta(block, meta);
                return getOreDictNames(new ItemStack(item, 1, dmg));
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<>();
    }

    public static boolean hasOreDictPrefix(ItemStack stack, String... prefixes) {
        for (String oreName : getOreDictNames(stack)) {
            String lower = oreName.toLowerCase();
            for (String p : prefixes) {
                if (lower.startsWith(p.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean hasOreDictPrefix(Block block, int meta, String... prefixes) {
        for (String oreName : getOreDictNames(block, meta)) {
            String lower = oreName.toLowerCase();
            for (String p : prefixes) {
                if (lower.startsWith(p.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== Dynamic Fluid Detection ====================

    public static boolean isFluidBlock(Block b) {
        if (b == null || b == Blocks.air) return false;
        if (b instanceof BlockLiquid || b instanceof IFluidBlock) return true;
        Material m = b.getMaterial();
        if (m != null && (m.isLiquid() || m == Material.water || m == Material.lava)) {
            return true;
        }
        try {
            return FluidRegistry.lookupFluidForBlock(b) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Fluid lookupFluidForBlock(Block b) {
        if (b == null || b == Blocks.air) return null;
        try {
            if (b instanceof IFluidBlock) {
                Fluid f = ((IFluidBlock) b).getFluid();
                if (f != null) return f;
            }
            Fluid f = FluidRegistry.lookupFluidForBlock(b);
            if (f != null) return f;
        } catch (Throwable ignored) {
        }
        Material m = b.getMaterial();
        if (m == Material.lava) return FluidRegistry.LAVA;
        if (m == Material.water) return FluidRegistry.WATER;
        return null;
    }

    public static boolean isEmptyFluidContainer(ItemStack st) {
        if (st == null || st.stackSize <= 0 || st.getItem() == null) return false;
        try {
            if (FluidContainerRegistry.isEmptyContainer(st)) return true;
        } catch (Throwable ignored) {
        }
        String reg = Item.itemRegistry.getNameForObject(st.getItem());
        return "minecraft:bucket".equalsIgnoreCase(reg);
    }

    // ==================== Dynamic Wood, Leaf, Crop & Ore Detection ====================

    public static boolean isLogBlock(Block b) {
        if (b == null || b == Blocks.air) return false;
        int key = Block.getIdFromBlock(b);
        Boolean cached = LOG_BLOCK_CACHE.get(key);
        if (cached != null) return cached;

        boolean result = (b instanceof BlockLog);
        if (!result && blockIsWoodMethod != null) {
            try {
                Object r = blockIsWoodMethod.invoke(b, null, 0, 0, 0);
                if (r instanceof Boolean && (Boolean) r) {
                    result = true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!result && hasOreDictPrefix(b, 0, "log")) {
            result = true;
        }
        if (!result && (b instanceof BlockRotatedPillar) && b.getMaterial() == Material.wood) {
            String reg = Block.blockRegistry.getNameForObject(b);
            String unloc = b.getUnlocalizedName();
            List<String> tokens = LexicalItemMatcher.tokenize((reg != null ? reg : "") + " " + (unloc != null ? unloc : ""));
            result = tokens.contains("log") || tokens.contains("bark") || tokens.contains("wood") || tokens.contains("stem") || tokens.contains("trunk");
        }
        LOG_BLOCK_CACHE.put(key, Boolean.valueOf(result));
        return result;
    }

    public static boolean isLeafBlock(Block b) {
        if (b == null || b == Blocks.air) return false;
        int key = Block.getIdFromBlock(b);
        Boolean cached = LEAF_BLOCK_CACHE.get(key);
        if (cached != null) return cached;

        boolean result = (b instanceof BlockLeaves) || (b.getMaterial() == Material.leaves)
                || ((b instanceof IShearable) && b.getMaterial() == Material.plants);
        if (!result && blockIsLeavesMethod != null) {
            try {
                Object r = blockIsLeavesMethod.invoke(b, null, 0, 0, 0);
                if (r instanceof Boolean && (Boolean) r) {
                    result = true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!result && hasOreDictPrefix(b, 0, "treeLeaves", "leaves")) {
            result = true;
        }
        LEAF_BLOCK_CACHE.put(key, Boolean.valueOf(result));
        return result;
    }

    public static boolean isCropOrPlantBlock(Block b) {
        if (b == null || b == Blocks.air) return false;
        if (b instanceof BlockCrops || b instanceof BlockReed || b instanceof IPlantable) return true;
        return hasOreDictPrefix(b, 0, "crop", "reed", "seed", "listAllveggie", "listAllgrain");
    }

    public static boolean isOreBlock(Block b, int meta) {
        if (b == null || b == Blocks.air) return false;
        int key = blockCacheKey(b, meta);
        Boolean cached = ORE_BLOCK_CACHE.get(key);
        if (cached != null) return cached;

        boolean res = (b instanceof BlockOre)
                || b == Blocks.redstone_ore
                || hasOreDictPrefix(b, meta, "ore", "denseore", "poorore", "cluster");

        if (!res) {
            // Check what item the block drops when mined: if a rock/mineral block drops an ore/gem/crystal/cluster/raw/dust/coal item, it's an ore!
            Material mat = b.getMaterial();
            if (mat == Material.rock || mat == Material.iron) {
                try {
                    Item droppedItem = b.getItemDropped(meta, PROBE_RANDOM, 0);
                    int droppedMeta = b.damageDropped(meta);
                    if (droppedItem != null) {
                        ItemStack dropStack = new ItemStack(droppedItem, 1, droppedMeta);
                        if (hasOreDictPrefix(dropStack, "ore", "gem", "crystal", "cluster", "raw", "crushed", "dust", "coal", "lignite", "nugget", "billet")) {
                            res = true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        if (!res) {
            String reg = Block.blockRegistry.getNameForObject(b);
            String unloc = b.getUnlocalizedName();
            String disp = ItemClassifier.getBlockDisplayName(b, meta);
            List<String> tokens = LexicalItemMatcher.tokenize(
                    (reg != null ? reg : "") + " " + (unloc != null ? unloc : "") + " " + (disp != null ? disp : "")
            );
            if (tokens.contains("ore") || tokens.contains("cluster") || tokens.contains("resource")) {
                res = true;
            }
        }

        ORE_BLOCK_CACHE.put(key, Boolean.valueOf(res));
        return res;
    }

    /**
     * Dynamically builds and caches the set of all underground/mineral tokens registered in Forge's OreDictionary
     * and Block registry, avoiding hardcoded ore lists.
     */
    public static Set<String> getUndergroundResourceTokens() {
        try {
            String[] oreNames = OreDictionary.getOreNames();
            int len = oreNames != null ? oreNames.length : 0;
            if (len != lastOreDictCount || cachedUndergroundTokens.isEmpty()) {
                Set<String> tokens = new HashSet<>();
                tokens.add("ore");
                tokens.add("ores");
                tokens.add("vein");
                tokens.add("veins");
                tokens.add("cluster");
                tokens.add("mineral");
                tokens.add("obsidian");
                tokens.add("bedrock");
                tokens.add("deepslate");
                tokens.add("lava");
                tokens.add("oil");
                String[] prefixes = new String[]{"ore", "denseore", "poorore", "cluster", "gem", "crystal", "ingot", "dust", "nugget", "billet", "raw"};
                if (oreNames != null) {
                    for (String name : oreNames) {
                        if (name == null) continue;
                        for (String p : prefixes) {
                            if (name.startsWith(p) && name.length() > p.length() && Character.isUpperCase(name.charAt(p.length()))) {
                                String stem = name.substring(p.length());
                                List<String> words = LexicalItemMatcher.tokenize(stem);
                                for (String w : words) {
                                    if (w.length() >= 3 && !w.equals("wood") && !w.equals("glass") && !w.equals("brick") && !w.equals("stone")) {
                                        tokens.add(w);
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
                cachedUndergroundTokens = tokens;
                lastOreDictCount = len;
            }
        } catch (Throwable ignored) {
        }
        return cachedUndergroundTokens;
    }

    public static boolean isLikelyUndergroundQuery(String filter) {
        if (filter == null) return false;
        List<String> queryTokens = LexicalItemMatcher.tokenize(filter);
        if (queryTokens.isEmpty()) return false;
        Set<String> undergroundTokens = getUndergroundResourceTokens();
        for (String tok : queryTokens) {
            if (undergroundTokens.contains(tok)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dynamically builds and caches the set of all crop/plant/seed tokens registered in
     * Forge's OreDictionary and Block registry (IPlantable / BlockCrops / BlockReed).
     */
    public static Set<String> getCropAndPlantTokens() {
        if (!cachedCropTokens.isEmpty()) return cachedCropTokens;
        Set<String> tokens = new HashSet<>();
        tokens.add("crop");
        tokens.add("crops");
        tokens.add("farm");
        tokens.add("reed");
        tokens.add("reeds");
        tokens.add("cane");
        tokens.add("canes");
        tokens.add("sugarcane");
        try {
            for (Object bObj : Block.blockRegistry) {
                if (!(bObj instanceof Block)) continue;
                Block b = (Block) bObj;
                if (b instanceof BlockCrops || b instanceof BlockReed) {
                    String reg = Block.blockRegistry.getNameForObject(b);
                    String disp = ItemClassifier.getBlockDisplayName(b, 0);
                    for (String w : LexicalItemMatcher.tokenize((reg != null ? reg : "") + " " + (disp != null ? disp : ""))) {
                        if (w.length() >= 3 && !w.equals("minecraft") && !w.equals("block")) {
                            tokens.add(w);
                        }
                    }
                }
            }
            String[] oreNames = OreDictionary.getOreNames();
            if (oreNames != null) {
                for (String name : oreNames) {
                    if (name != null && (name.startsWith("crop") || name.startsWith("seed") || name.startsWith("reed"))) {
                        for (String w : LexicalItemMatcher.tokenize(name)) {
                            if (w.length() >= 3 && !w.equals("crop") && !w.equals("seed")) {
                                tokens.add(w);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        cachedCropTokens = tokens;
        return cachedCropTokens;
    }

    // ==================== Dynamic Storage Container vs. Processing Machine Detection ====================

    public static boolean isFurnaceTile(World world, TileEntity te) {
        if (te == null) return false;
        if (te instanceof TileEntityFurnace) return true;
        if (te instanceof IInventory && world != null) {
            Block b = world.getBlock(te.xCoord, te.yCoord, te.zCoord);
            if (b == Blocks.furnace || b == Blocks.lit_furnace) return true;
            String reg = b != null ? Block.blockRegistry.getNameForObject(b) : "";
            List<String> tokens = LexicalItemMatcher.tokenize(reg);
            return tokens.contains("furnace");
        }
        return false;
    }

    public static boolean isStorageContainerTile(World world, TileEntity te) {
        if (!(te instanceof IInventory) || te instanceof TileEntityEnderChest) return false;
        if (te instanceof TileEntityChest) return true;
        if (isFurnaceTile(world, te)) return false;

        IInventory inv = (IInventory) te;
        int totalSlots = inv.getSizeInventory();
        if (totalSlots < 5) return false;

        if (world != null) {
            Block b = world.getBlock(te.xCoord, te.yCoord, te.zCoord);
            if (b instanceof BlockChest) return true;
        }

        Class<?> cls = te.getClass();
        Boolean cached = STORAGE_TILE_CLASS_CACHE.get(cls);
        if (cached != null) return cached;

        String clsName = cls.getName().toLowerCase();
        // OpenComputers internal components/cases/racks/assemblers are not general storage chests
        if (clsName.contains("li.cil.oc") || clsName.contains("opencomputers")) {
            STORAGE_TILE_CLASS_CACHE.put(cls, Boolean.FALSE);
            return false;
        }

        // True storage containers (IronChests, Crates, Barrels, Lockers, Drawers) have "chest", "crate", "barrel", "locker", "cabinet", "storage", "safe" in class or inventory name
        String invName = "";
        try {
            invName = inv.getInventoryName() != null ? inv.getInventoryName().toLowerCase() : "";
        } catch (Throwable ignored) {
        }
        String combined = clsName + " " + invName;
        boolean explicitContainer = combined.contains("chest") || combined.contains("crate") || combined.contains("barrel")
                || combined.contains("locker") || combined.contains("cabinet") || combined.contains("safe") || combined.contains("backpack");

        if (!explicitContainer) {
            // Processing machines implement ISidedInventory (for separate input/fuel/output sides) or IFluidHandler
            if (te instanceof ISidedInventory || te instanceof IFluidHandler) {
                STORAGE_TILE_CLASS_CACHE.put(cls, Boolean.FALSE);
                return false;
            }
            // Behavioral check: in a general storage container, all slots uniformly accept a standard building block
            try {
                ItemStack probe = new ItemStack(Blocks.cobblestone, 1, 0);
                int checkSlots = Math.min(totalSlots, 6);
                for (int i = 0; i < checkSlots; i++) {
                    if (!inv.isItemValidForSlot(i, probe)) {
                        STORAGE_TILE_CLASS_CACHE.put(cls, Boolean.FALSE);
                        return false;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        boolean result = explicitContainer || totalSlots >= 9;
        STORAGE_TILE_CLASS_CACHE.put(cls, Boolean.valueOf(result));
        return result;
    }

    // ==================== Delegated Entity & Biome Classification ====================

    public static boolean isCreatureOfType(EntityLivingBase el, EnumCreatureType type) {
        return EntityAndBiomeClassifier.isCreatureOfType(el, type);
    }

    public static boolean isHostileEntity(EntityLivingBase el) {
        return EntityAndBiomeClassifier.isHostileEntity(el);
    }

    public static boolean isPassiveEntity(EntityLivingBase el) {
        return EntityAndBiomeClassifier.isPassiveEntity(el);
    }

    public static String getEntityIdentifier(EntityLivingBase el) {
        return EntityAndBiomeClassifier.getEntityIdentifier(el);
    }

    public static boolean matchesBiome(BiomeGenBase biome, String query) {
        return EntityAndBiomeClassifier.matchesBiome(biome, query);
    }

    public static Set<String> getRegisteredBiomeTokens() {
        return EntityAndBiomeClassifier.getRegisteredBiomeTokens();
    }

    public static boolean hasBiomeAffinity(String query) {
        return EntityAndBiomeClassifier.hasBiomeAffinity(query);
    }

    // ==================== Dynamic Item Category Classification ====================

    public static int getItemCategoryOrder(ItemStack st) {
        if (st == null || st.getItem() == null) return 9;
        Item item = st.getItem();
        if (item instanceof ItemFood || item instanceof IPlantable || hasOreDictPrefix(st, "crop", "seed", "listAllfood", "listAllmeat")) {
            return 3; // Food, Crops & Seeds
        }
        if (hasOreDictPrefix(st, "ore", "raw", "cluster", "gem", "crystal", "dust", "crushed", "coal", "lignite")) {
            return 0; // Ores, Gems & Raw Materials
        }
        if (hasOreDictPrefix(st, "ingot", "plate", "wire", "nugget", "billet", "block", "gear", "rod")) {
            return 1; // Ingots, Plates, Wires & Metal Blocks
        }
        if (item instanceof ItemTool || item instanceof ItemSword || item instanceof ItemArmor
                || item instanceof ItemBow || item instanceof ItemHoe || item instanceof ItemShears
                || !item.isDamageable() && st.getMaxStackSize() == 1) {
            return 2; // Tools, Weapons, Armor & Non-Stackable Equipment
        }
        if (item instanceof ItemBlock) {
            Block b = Block.getBlockFromItem(item);
            if (isOreBlock(b, st.getItemDamage())) {
                return 0;
            }
            return 4; // Building Blocks
        }
        return 5; // Misc Materials & Components
    }
}
