package ir.layercloud.cortexdrone.harness.js;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP-name to SRG-name aliases for scripts that use {@code Java.type(...)}.
 *
 * <p>A production 1.7.10 server runs reobfuscated classes, so a script that
 * writes the developer (MCP) name
 * {@code Java.type("net.minecraft.item.crafting.CraftingManager").getInstance()}
 * fails with "no public instance field or method named getInstance". The live
 * log has 4 {@code CraftingManager.getInstance()} failures and several
 * {@code FurnaceRecipes.smelting()} / {@code Block.getBlockFromName()} ones.
 * These are not player errors: the same script works in a dev environment and
 * fails on the server.</p>
 *
 * <p>The table is intentionally small and explicit. A generic "find any func_
 * method with the same arity" heuristic can invoke the wrong overload (some
 * methods have side effects), so only the names observed in the live log (and
 * their immediate neighbours) are mapped.</p>
 */
final class McpAliases {
    private static final Map<String, String> METHODS = new HashMap<>();
    private static final Map<String, String> FIELDS = new HashMap<>();

    static {
        // net.minecraft.item.crafting
        method("net.minecraft.item.crafting.CraftingManager",
                "getInstance", "func_77594_a",
                "getRecipeList", "func_77592_b",
                "findMatchingRecipe", "func_82787_a");
        method("net.minecraft.item.crafting.FurnaceRecipes",
                "smelting", "func_77602_a",
                "getSmeltingList", "func_77599_b",
                "getSmeltingResult", "func_151395_a");

        // Block / Item / ItemStack lookup used by registry introspection scripts
        method("net.minecraft.block.Block",
                "getBlockFromName", "func_149684_b",
                "getLocalizedName", "func_149732_F");
        method("net.minecraft.item.Item",
                "getIdFromItem", "func_150891_b",
                "getItemById", "func_150899_d",
                "getUnlocalizedName", "func_77658_a");
        method("net.minecraft.item.ItemStack",
                "getItem", "func_77973_b",
                "getDisplayName", "func_82833_r",
                "getItemDamage", "func_77960_j");
        method("net.minecraft.item.crafting.IRecipe", "getRecipeOutput", "func_77571_b");

        // Server singleton. The live log shows the opposite direction failing:
        // a script wrote `MinecraftServer.func_71276_C()` on an MCP-named
        // deployment and got "Cannot find function func_71276_C". Resolution is
        // bidirectional, so this one pair makes both spellings work.
        method("net.minecraft.server.MinecraftServer", "getServer", "func_71276_C");
        method("cpw.mods.fml.common.Loader", "activeModList", "getActiveModList");

        // net.minecraft.util registries (live scripts iterate the block registry)
        method("net.minecraft.util.RegistrySimple",
                "getKeys", "func_148742_b",
                "getObject", "func_82594_a");
        method("net.minecraft.util.RegistryNamespaced",
                "getObject", "func_82594_a",
                "getObjectById", "func_148754_a",
                "getIDForObject", "func_148757_b");

        // Public registries are fields, not methods.
        field("net.minecraft.block.Block", "blockRegistry", "field_149771_c");
        field("net.minecraft.item.Item", "itemRegistry", "field_150901_e");
    }

    private McpAliases() {
    }

    private static void method(String owner, String... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            // Both directions. A production server is SRG-named, but a dev
            // world (and some forks) are MCP-named; the live log shows a script
            // calling func_71276_C() on an MCP deployment. One table now serves
            // both without guessing an overload.
            METHODS.put(owner + "#" + pairs[i], pairs[i + 1]);
            METHODS.putIfAbsent(owner + "#" + pairs[i + 1], pairs[i]);
        }
    }

    private static void field(String owner, String... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            FIELDS.put(owner + "#" + pairs[i], pairs[i + 1]);
            FIELDS.putIfAbsent(owner + "#" + pairs[i + 1], pairs[i]);
        }
    }

    /**
     * SRG method name for an MCP name, or {@code null} when unknown. Walks the
     * superclass chain so a subclass of a mapped class also resolves.
     */
    static String method(Class<?> owner, String name) {
        return lookup(METHODS, owner, name);
    }

    static String field(Class<?> owner, String name) {
        return lookup(FIELDS, owner, name);
    }

    private static String lookup(Map<String, String> table, Class<?> owner, String name) {
        if (owner == null || name == null) return null;
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            String mapped = table.get(type.getName() + "#" + name);
            if (mapped != null) return mapped;
        }
        // Interfaces (IRecipe) are not in the superclass chain of the concrete
        // recipe object, so check the interfaces as well.
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            for (Class<?> iface : type.getInterfaces()) {
                String mapped = table.get(iface.getName() + "#" + name);
                if (mapped != null) return mapped;
            }
        }
        return null;
    }
}
