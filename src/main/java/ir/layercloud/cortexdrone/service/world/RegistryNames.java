package ir.layercloud.cortexdrone.service.world;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Human-readable names and searchable identifiers for blocks and items.
 *
 * <p>Extracted from {@code ItemClassifier}. Resolution is layered because modded
 * items disagree: the {@link ItemStack} display name is authoritative when it
 * looks like a real name, then the block's localized name, then a humanized
 * registry id. Results are cached per block/meta because every scan asks for the
 * same handful of names thousands of times.</p>
 */
public final class RegistryNames {
    private static final Map<Integer, String> BLOCK_DISPLAY_CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, String> BLOCK_IDENTIFIER_CACHE = new ConcurrentHashMap<>();

    private RegistryNames() {
    }

    /** Display name for a block, never {@code null}. */
    public static String blockDisplayName(Block block, int meta) {
        if (block == null || block == Blocks.air) return "Air";
        int key = RegistryIntrospector.blockCacheKey(block, meta);
        String cached = BLOCK_DISPLAY_CACHE.get(key);
        if (cached != null) return cached;

        String resolved = fromItemStack(block, meta);
        if (resolved == null) resolved = fromLocalizedName(block);
        if (resolved == null) {
            resolved = ItemClassifier.humanizeUnlocalizedName(
                    block.getLocalizedName(), Block.blockRegistry.getNameForObject(block));
        }
        BLOCK_DISPLAY_CACHE.put(key, resolved);
        return resolved;
    }

    /** The item form's display name, or {@code null} when it is not usable. */
    private static String fromItemStack(Block block, int meta) {
        try {
            Item item = Item.getItemFromBlock(block);
            if (item == null) return null;
            ItemStack stack = new ItemStack(item, 1, RegistryIntrospector.normalizeBlockMeta(block, meta));
            String name = stack.getDisplayName();
            return isUsableName(name) ? name : null;
        } catch (Throwable ignored) {
            // A block without an item form falls back to its own name.
            return null;
        }
    }

    private static String fromLocalizedName(Block block) {
        String localized = block.getLocalizedName();
        return isUsableName(localized) ? localized : null;
    }

    /** A name is usable when it is not a raw translation key. */
    private static boolean isUsableName(String name) {
        return name != null && !name.isEmpty()
                && !name.endsWith(".name")
                && !name.startsWith("tile.")
                && !name.startsWith("item.");
    }

    /**
     * Lower-case search blob for a block: registry name, meta form, unlocalized
     * name, localized name, display name and every ore-dictionary tag.
     */
    public static String blockIdentifier(Block block, int meta) {
        if (block == null) return "";
        int key = RegistryIntrospector.blockCacheKey(block, meta);
        String cached = BLOCK_IDENTIFIER_CACHE.get(key);
        if (cached != null) return cached;

        String registryName = Block.blockRegistry.getNameForObject(block);
        String identifier = build(registryName, meta,
                block.getUnlocalizedName(), block.getLocalizedName(),
                blockDisplayName(block, meta), ItemClassifier.getOreDictNames(block, meta));
        BLOCK_IDENTIFIER_CACHE.put(key, identifier);
        return identifier;
    }

    /** Search blob for an item stack. */
    public static String itemIdentifier(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return "";
        Item item = stack.getItem();
        return build(Item.itemRegistry.getNameForObject(item), stack.getItemDamage(),
                stack.getUnlocalizedName(), stack.getDisplayName(),
                stack.getDisplayName(), ItemClassifier.getOreDictNames(stack));
    }

    private static String build(String registryName, int meta, String unlocalized,
                                String localized, String display, List<String> oreTags) {
        StringBuilder out = new StringBuilder(96);
        out.append(registryName != null ? registryName : "").append(' ')
                .append(registryName != null ? registryName + ":" + meta : "").append(' ')
                .append(unlocalized != null ? unlocalized : "").append(' ')
                .append(localized != null ? localized : "").append(' ')
                .append(display != null ? display : "");
        for (String tag : oreTags) out.append(' ').append(tag);
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
