package ir.layercloud.cortexdrone.service.combat;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Structural guards for the combat reconnaissance contract.
 *
 * <p>The kill loop depends on {@code attack_entity} telling the truth about
 * damage dealt and the next safe swing time. These source assertions keep a
 * future edit from turning the result into a guess.</p>
 */
public class CombatGuardTest {
    private static final String MAIN = "src/main/java/ir/layercloud/cortexdrone/";

    @Test
    public void attackResultReportsDealtDamageAndTheNextSafeSwing() throws Exception {
        String action = read(MAIN + "action/interact/impl/AttackEntityAction.java");
        assertTrue("the result must distinguish requested from dealt damage",
                action.contains("\"damageDealt\""));
        assertTrue("the loop needs the hurt-resistance wait",
                action.contains("\"nextAttackInMs\""));
        assertTrue("the cooldown retry must carry a millisecond hint",
                action.contains("\"retryAfterMs\""));
        assertTrue("the result must carry the remaining hit count",
                action.contains("\"attacksRemaining\""));
    }

    @Test
    public void combatServiceStaysReadOnly() throws Exception {
        String service = read(MAIN + "service/combat/CombatAssessmentService.java");
        assertFalse("a service must not attack", service.contains("attackEntityFrom"));
        assertFalse("a service must not start navigation", service.contains("gotoGoal"));
        assertFalse("a service must not retain work", service.contains("retain("));
        assertTrue("armor must come from vanilla", service.contains("getTotalArmorValue"));
        assertTrue("resistance must come from the potion effect",
                service.contains("Potion.resistance"));
    }

    @Test
    public void combatToolsDoNotShareTheInteractionManager() throws Exception {
        String tools = read(MAIN + "harness/tools/CombatTools.java");
        assertFalse("combat reconnaissance must not perform actions",
                tools.contains("InteractionView"));
        assertTrue("both tools share one assessment",
                tools.contains("this.combat.assess("));
        String catalog = read(MAIN + "harness/tool/ToolCatalog.java");
        assertTrue("CombatTools must be in the catalog", catalog.contains("CombatTools"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
