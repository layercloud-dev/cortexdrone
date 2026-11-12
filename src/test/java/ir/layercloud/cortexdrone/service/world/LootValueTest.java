package ir.layercloud.cortexdrone.service.world;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The value heuristic that decides what a mining or hunting run picks up.
 *
 * <p>A drone has eight slots. Sucking everything after a break fills them with
 * cobblestone while an ore drop waits two blocks away, so the skills filter on
 * "valuable" instead.</p>
 */
public class LootValueTest {
    /**
     * One shared item instance: distinct {@code new Item()} objects never stack.
     */
    private static final Item ITEM = new Item();

    private static ItemStack stack() {
        return new ItemStack(ITEM, 1);
    }

    @Test
    public void oreTagsAndMetalNamesAreValuable() {
        assertTrue(LootValue.isValuable("Iron Ore", "minecraft:iron_ore", null));
        assertTrue(LootValue.isValuable("Tin Ingot", "gregtech:tin_ingot", null));
        assertTrue(LootValue.isValuable("Diamond", "minecraft:diamond", null));
        assertTrue(LootValue.isValuable("Gold Dust", "ic2:gold_dust", null));
        assertTrue(LootValue.isValuable("Raw Platinum", "mekanism:raw_platinum", null));
        assertTrue("an oreDict tag alone is enough",
                LootValue.isValuable("Cassiterite", "forestry:cassiterite",
                        Collections.singletonList("oreTin")));
    }

    @Test
    public void foodAndLogsCountAsLoot() {
        assertTrue(LootValue.isValuable("Cooked Beef", "minecraft:cooked_beef", null));
        assertTrue(LootValue.isValuable("Oak Log", "minecraft:log", null));
        assertTrue(LootValue.isValuable("Bread", "minecraft:bread", null));
    }

    @Test
    public void theJunkListWinsOverAValueLookingName() {
        assertFalse(LootValue.isValuable("Cobblestone", "minecraft:cobblestone", null));
        assertFalse(LootValue.isValuable("Dirt", "minecraft:dirt", null));
        assertFalse(LootValue.isValuable("Gravel", "minecraft:gravel", null));
        assertFalse(LootValue.isValuable("Rotten Flesh", "minecraft:rotten_flesh", null));
        assertFalse(LootValue.isValuable("Bone", "minecraft:bone", null));
        assertFalse("blockGravel must stay junk even with a block* tag",
                LootValue.isValuable("Gravel Block", "mod:block_gravel",
                        Collections.singletonList("blockGravel")));
    }

    @Test
    public void unknownModdedItemsAreKept() {
        assertTrue("ignoring an unfamiliar mod's drop is worse than picking it up",
                LootValue.isValuable("Mysterious Alloy", "mymod:mysterious_alloy", null));
    }

    @Test
    public void filtersSelectValueOrJunk() {
        ItemStack real = stack();
        // A bare Item() has no display name and no registry entry: the fallback
        // is "valuable" (unknown), which is the documented behaviour.
        assertTrue(LootValue.matchesFilter(real, "valuable"));
        assertFalse(LootValue.matchesFilter(real, "junk"));
        assertTrue(LootValue.matchesFilter(real, "all"));
        assertTrue(LootValue.matchesFilter(real, null));
        assertTrue(LootValue.matchesFilter(real, ""));
    }

    @Test
    public void tagStemHandlesCamelCase() {
        assertTrue(LootValue.isValuable("Thing", "mod:thing",
                Arrays.asList("ingotSteel", "dustSmallIron")));
        assertTrue(LootValue.isValuable("Thing", "mod:thing",
                Arrays.asList("cropWheat")));
        assertFalse("seeds are junk even though they are a crop",
                LootValue.isValuable("Thing", "mod:thing",
                        Arrays.asList("seedWheat")));
    }
}
