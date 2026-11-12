package ir.layercloud.cortexdrone.harness.js;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.util.RegistryNamespaced;
import net.minecraft.util.RegistrySimple;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The production server is reobfuscated, so MCP method names from scripts must
 * resolve to their SRG names. These are exactly the failures visible in the
 * live log (CraftingManager.getInstance, FurnaceRecipes.smelting,
 * Block.getBlockFromName, the block registry field).
 */
public class McpAliasesTest {
    @Test
    public void craftingManagerGettersMapToSrgNames() {
        assertEquals("func_77594_a", McpAliases.method(CraftingManager.class, "getInstance"));
        assertEquals("func_77592_b", McpAliases.method(CraftingManager.class, "getRecipeList"));
    }

    @Test
    public void furnaceAndBlockLookupsMapToSrgNames() {
        assertEquals("func_77602_a", McpAliases.method(FurnaceRecipes.class, "smelting"));
        assertEquals("func_77599_b", McpAliases.method(FurnaceRecipes.class, "getSmeltingList"));
        assertEquals("func_149684_b", McpAliases.method(Block.class, "getBlockFromName"));
        assertEquals("func_149732_F", McpAliases.method(Block.class, "getLocalizedName"));
        assertEquals("func_77973_b", McpAliases.method(ItemStack.class, "getItem"));
        assertEquals("func_82833_r", McpAliases.method(ItemStack.class, "getDisplayName"));
    }

    @Test
    public void registryMethodsAndFieldsMapToSrgNames() {
        assertEquals("func_148742_b", McpAliases.method(RegistrySimple.class, "getKeys"));
        assertEquals("func_82594_a", McpAliases.method(RegistrySimple.class, "getObject"));
        assertEquals("func_82594_a", McpAliases.method(RegistryNamespaced.class, "getObject"));
        assertEquals("field_149771_c", McpAliases.field(Block.class, "blockRegistry"));
        assertEquals("field_150901_e", McpAliases.field(Item.class, "itemRegistry"));
    }

    @Test
    public void subclassesInheritTheMappingAndUnknownNamesStayNull() {
        // A concrete registry is a RegistrySimple subclass in 1.7.10; the
        // lookup walks the superclass chain.
        assertEquals("func_148742_b", McpAliases.method(RegistryNamespaced.class, "getKeys"));
        assertNull("an unmapped name must not guess a func_ alias",
                McpAliases.method(CraftingManager.class, "definitelyNotAMethod"));
        assertNull(McpAliases.method(null, "getInstance"));
        assertNull(McpAliases.field(Block.class, "definitelyNotAField"));
    }
}
