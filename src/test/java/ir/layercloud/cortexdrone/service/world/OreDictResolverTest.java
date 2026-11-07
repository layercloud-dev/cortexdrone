package ir.layercloud.cortexdrone.service.world;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Filters must understand OreDictionary tags, not just display names.
 *
 * <p>"Bring me tin" is the request; {@code oreTin} is the mechanism. These
 * tests pin the pure text/dictionary plumbing that does not need a booted
 * Minecraft registry, plus the guarantee that a missing dictionary degrades to
 * "no tag match" instead of throwing.</p>
 */
public class OreDictResolverTest {

    @Test
    public void explicitTagPrefixesAreRecognised() {
        assertTrue(OreDictResolver.hasExplicitPrefix("#oreTin"));
        assertTrue(OreDictResolver.hasExplicitPrefix("oredict:oreTin"));
        assertTrue(OreDictResolver.hasExplicitPrefix("ORE_DICT:oreTin"));
        assertTrue(OreDictResolver.hasExplicitPrefix("dict:plankWood"));
        assertTrue(OreDictResolver.hasExplicitPrefix("tag:ingotIron"));
        assertFalse(OreDictResolver.hasExplicitPrefix("tin"));
        assertFalse(OreDictResolver.hasExplicitPrefix("iron ingot"));
    }

    @Test
    public void prefixIsStrippedBeforeLookup() {
        assertEquals("oreTin", OreDictResolver.stripPrefix("#oreTin"));
        assertEquals("oreTin", OreDictResolver.stripPrefix("oredict:oreTin"));
        assertEquals("plankWood", OreDictResolver.stripPrefix("DICT:plankWood"));
        assertEquals("tin", OreDictResolver.stripPrefix(" tin "));
        assertEquals("", OreDictResolver.stripPrefix("#"));
    }

    @Test
    public void compactKeyIgnoresCaseUnderscoresAndSpaces() {
        assertEquals("oreiron", OreDictResolver.compactKey("oreIron"));
        assertEquals("oreiron", OreDictResolver.compactKey("ore_iron"));
        assertEquals("oreiron", OreDictResolver.compactKey("ORE IRON"));
        assertEquals("", OreDictResolver.compactKey(null));
    }

    @Test
    public void wordBoundaryMatchingDoesNotMatchSubstrings() {
        // "tin" must not match a tag that merely contains the letters.
        assertTrue(OreDictResolver.containsWord("oreg tin ingot_tin", "tin"));
        assertFalse(OreDictResolver.containsWord("destiny plating", "tin"));
        assertFalse(OreDictResolver.containsWord("", "tin"));
    }

    @Test
    public void realTagUniverseResolvesPlayerWords() {
        List<String> tags = Arrays.asList(
                "oreIron", "ingotIron", "dustIron", "nuggetIron", "blockIron",
                "oreTin", "ingotTin", "blockTin",
                "ingotCopper", "oreCopper",
                "plankWood", "logWood", "stairWood", "slabWood",
                "gemDiamond", "oreDiamond"
        );

        // Exact tag names win in any spelling.
        assertEquals(Arrays.asList("oreIron"), OreDictResolver.resolveTags("oreIron", tags));
        assertEquals(Arrays.asList("oreIron"), OreDictResolver.resolveTags("ore_iron", tags));
        assertEquals(Arrays.asList("oreIron"), OreDictResolver.resolveTags("ore iron", tags));
        assertEquals(Arrays.asList("oreIron"), OreDictResolver.resolveTags("#oreIron", tags));

        // A bare material word resolves to its real material shapes, not
        // decorative blocks.
        List<String> tin = OreDictResolver.resolveTags("tin", tags);
        assertTrue("tin must find tin tags: " + tin, tin.contains("ingotTin"));
        assertTrue("tin must find the ore: " + tin, tin.contains("oreTin"));
        assertFalse("tin must not match an unrelated tag: " + tin, tin.contains("oreIron"));
        assertFalse("tin must not match decorative blocks: " + tin, tin.contains("blockTin"));
    }

    @Test
    public void queriesDoNotSweepEveryTagContainingTheWord() {
        List<String> tags = Arrays.asList("oreIron", "ingotIron", "oreTin", "ingotTin");
        List<String> planks = OreDictResolver.resolveTags("iron ingot", tags);
        assertTrue("iron ingot must reach ingotIron: " + planks, planks.contains("ingotIron"));
        assertFalse("iron ingot must not return ores: " + planks, planks.contains("oreIron"));

        // A category word must not turn into a whole-dictionary match.
        assertTrue(OreDictResolver.resolveTags("ores", tags).isEmpty());
        assertTrue(OreDictResolver.resolveTags("all", tags).isEmpty());
        assertTrue(OreDictResolver.resolveTags("stone", tags).isEmpty());
    }

    @Test
    public void derivativeShapesOnlyMatchWhenAskedFor() {
        List<String> tags = Arrays.asList("ingotIron", "dustIron", "nuggetIron");
        List<String> plain = OreDictResolver.resolveTags("iron", tags);
        assertTrue(plain.contains("ingotIron"));
        assertFalse("plain iron must not return dust: " + plain, plain.contains("dustIron"));
        assertFalse("plain iron must not return nuggets: " + plain, plain.contains("nuggetIron"));
        assertTrue("an asked-for shape must still match",
                OreDictResolver.resolveTags("iron dust", tags).contains("dustIron"));
    }

    @Test
    public void materialThatOnlyExistsAsADerivativeStillMatches() {
        List<String> tags = Arrays.asList("dustRedstone", "blockRedstone");
        List<String> redstone = OreDictResolver.resolveTags("redstone", tags);
        assertTrue("redstone only exists as dustRedstone: " + redstone,
                redstone.contains("dustRedstone"));
    }

    @Test
    public void explicitUnknownTagResolvesToNothing() {
        List<String> tags = Arrays.asList("oreIron", "ingotIron");
        assertTrue(OreDictResolver.resolveTags("#oreUnobtainium", tags).isEmpty());
        assertTrue(OreDictResolver.resolveTags("oredict:oreUnobtainium", tags).isEmpty());
    }

    @Test
    public void unknownQueryDegradesInsteadOfThrowing() {        List<String> tags = OreDictResolver.resolveTagQuery("this-tag-does-not-exist-xyz");
        assertNotNull(tags);
        assertTrue("an unknown tag must resolve to nothing", tags.isEmpty());
        assertEquals(0, OreDictResolver.scoreIdentifierTagMatch("", "tin"));
        assertEquals(0, OreDictResolver.scoreIdentifierTagMatch("minecraft:iron_ore", "this-tag-does-not-exist-xyz"));
    }

    @Test
    public void scoreIdentifierMatchesAppendedTagText() {
        // Real identifiers are built by ItemClassifier with ore tags appended.
        String identifier = "minecraft:iron_ore minecraft:iron_ore:0 tile.iron_ore iron ore oreiron";
        assertTrue(OreDictResolver.scoreIdentifierTagMatch(identifier, "#oreIron") >= 0);
        // A tag that is not present must never produce a match.
        assertEquals(0, OreDictResolver.scoreIdentifierTagMatch(identifier, "#oreTin"));
    }
}
