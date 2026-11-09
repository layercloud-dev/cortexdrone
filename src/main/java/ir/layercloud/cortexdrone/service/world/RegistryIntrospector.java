package ir.layercloud.cortexdrone.service.world;

import lombok.experimental.UtilityClass;
import net.minecraft.block.*;
import net.minecraft.block.material.Material;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.item.*;
import net.minecraft.tileentity.TileEntity;
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

    private static volatile Set<String> cachedUndergroundTokens = Collections.emptySet();
    /**
     * Cache key for the derived token set: the tag count it was built from.
     *
     * <p>Not a second registry watcher — {@link OreDictIndex#tagCount()} is the
     * authority. This merely records when the derived set is stale.</p>
     */
    private static volatile int undergroundTokensBuiltFrom = -1;

    private static Method blockIsWoodMethod = null;
    private static Method blockIsLeavesMethod = null;

    static {
        try {
            blockIsWoodMethod = Block.class.getMethod("isWood", IBlockAccess.class, int.class, int.class, int.class);
        } catch (Throwable ignored) {
        }
        try {
            blockIsLeavesMethod = Block.class.getMethod("isLeaves", IBlockAccess.class, int.class, int.class, int.class);
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
            for (int id : ids) {
                String name = OreDictionary.getOreName(id);
                if (name != null && !name.isEmpty() && !"Unknown".equalsIgnoreCase(name)) {
                    names.add(name);
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
        LOG_BLOCK_CACHE.put(key, result);
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
        LEAF_BLOCK_CACHE.put(key, result);
        return result;
    }

    public static boolean isCropOrPlantBlock(Block b) {
        if (b == null || b == Blocks.air) return false;
        if (b instanceof IPlantable) return true;
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
                    (reg != null ? reg : "") + " " + (unloc != null ? unloc : "") + " " + disp
            );
            if (tokens.contains("ore") || tokens.contains("cluster") || tokens.contains("resource")) {
                res = true;
            }
        }

        ORE_BLOCK_CACHE.put(key, res);
        return res;
    }

    /**
     * Dynamically builds and caches the set of all underground/mineral tokens registered in Forge's OreDictionary
     * and Block registry, avoiding hardcoded ore lists.
     */
    public static Set<String> getUndergroundResourceTokens() {
        try {
            // The tag-count watcher lives in OreDictIndex; there is one counter.
            OreDictIndex.refresh();
            int len = OreDictIndex.tagCount();
            if (len < 0) len = 0;
            if (len != undergroundTokensBuiltFrom || cachedUndergroundTokens.isEmpty()) {
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
                for (String name : OreDictIndex.tagNames()) {
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
                cachedUndergroundTokens = Collections.unmodifiableSet(tokens);
                undergroundTokensBuiltFrom = len;
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

    // ==================== Dynamic Storage Container vs. Processing Machine Detection ====================

    /** @see ContainerTiles#isFurnace(World, TileEntity) */
    public static boolean isFurnaceTile(World world, TileEntity te) {
        return ContainerTiles.isFurnace(world, te);
    }

    /** @see ContainerTiles#isStorage(World, TileEntity) */
    public static boolean isStorageContainerTile(World world, TileEntity te) {
        return ContainerTiles.isStorage(world, te);
    }

    // ==================== Delegated Entity & Biome Classification ====================

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

    public static boolean hasBiomeAffinity(String query) {
        return EntityAndBiomeClassifier.hasBiomeAffinity(query);
    }
}
