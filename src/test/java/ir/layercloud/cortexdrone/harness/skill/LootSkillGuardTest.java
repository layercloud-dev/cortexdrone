package ir.layercloud.cortexdrone.harness.skill;

import ir.layercloud.cortexdrone.SourceTree;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Gathering and combat skills must loot as they go.
 *
 * <p>The live drone has eight slots. Mining used to break the ore and leave the
 * drop on the ground (or sweep everything into the slots, cobblestone included),
 * and hunting only vacuumed once at the end. These guards pin the shared helper
 * and its use in the skills where loot is actually created.</p>
 */
public class LootSkillGuardTest {
    private static final String SKILLS = SourceTree.RESOURCES + "skills/";
    private static final String BOOTSTRAP = SourceTree.RESOURCES + "js/bootstrap.js";

    @Test
    public void theSharedHelperFiltersOnValue() throws Exception {
        String bootstrap = SourceTree.read(BOOTSTRAP);
        assertTrue("pickupNearbyLoot must exist",
                bootstrap.contains("function pickupNearbyLoot("));
        assertTrue("it must only vacuum valuable drops",
                bootstrap.contains("filter: 'valuable'"));
        assertTrue("it must walk to loot outside vacuum range",
                bootstrap.contains("getDrops({radius: scanRadius, filter: 'valuable'})"));
    }

    @Test
    public void miningSkillsLootAfterEachBreak() throws Exception {
        for (String skill : new String[]{"mine_blocks", "mine_box", "strip_mine", "quarry", "chop_tree"}) {
            String code = SourceTree.read(SKILLS + skill + "/code.js");
            assertTrue(skill + " must loot after a break", code.contains("pickupNearbyLoot("));
            assertTrue(skill + " must report what it picked up", code.contains("pickedUp"));
        }
    }

    @Test
    public void combatSkillsLootTheKill() throws Exception {
        String hunt = SourceTree.read(SKILLS + "hunt/code.js");
        assertTrue("hunt must loot right after a kill", hunt.contains("pickupNearbyLoot("));
        assertFalse("hunt must not vacuum everything blindly",
                hunt.contains("drone.suckItems({radius: 6})"));
        String attack = SourceTree.read(SKILLS + "attack_to_death/code.js");
        assertTrue("attack_to_death must use the shared loot helper",
                attack.contains("pickupNearbyLoot("));
    }

    @Test
    public void collectDropsRespectsItsFilter() throws Exception {
        String collect = SourceTree.read(SKILLS + "collect_drops/code.js");
        assertTrue("the vacuum must reuse the requested filter",
                collect.contains("drone.suckItems({radius: 4, filter: filter})"));
    }
}
