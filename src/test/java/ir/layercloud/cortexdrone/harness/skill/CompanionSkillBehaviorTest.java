package ir.layercloud.cortexdrone.harness.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Carrying a player: climb/cruise/descent legs and safe-landing refusal.
 *
 * <p>Harness lives in {@link SkillTestSupport}; this suite keeps one behavioural area
 * per file.</p>
 */
public class CompanionSkillBehaviorTest extends SkillTestSupport {
    @Test
    public void carryPlayerClimbsCruisesThenLandsOnTheScannedSpot() throws Exception {
        JsonObject player = new JsonObject();
        player.addProperty("ok", true);
        player.addProperty("name", "Tester");
        player.addProperty("x", 0.5);
        player.addProperty("y", 64.0);
        player.addProperty("z", 0.5);
        player.addProperty("blockX", 0);
        player.addProperty("blockY", 64);
        player.addProperty("blockZ", 0);
        player.addProperty("riding", false);
        player.addProperty("underground", false);
        JsonObject entity = new JsonObject();
        entity.addProperty("id", 42);
        entity.addProperty("name", "Tester");
        entity.addProperty("x", 0.5);
        entity.addProperty("y", 64.0);
        entity.addProperty("z", 0.5);
        entity.addProperty("dist", 1.2);
        entity.addProperty("player", true);
        JsonArray entities = new JsonArray();
        entities.add(entity);

        Harness harness = new Harness(library(), state(), (name, args) -> {
            if ("get_player".equals(name)) return player;
            if ("get_entities".equals(name)) {
                JsonObject found = ToolJson.ok();
                found.addProperty("count", 1);
                found.add("entities", entities);
                return found;
            }
            if ("get_top_y".equals(name)) {
                JsonObject top = ToolJson.ok();
                // A hill between the drone and the destination: the cruise
                // altitude must rise above it, not fly through it.
                top.addProperty("topY", args.get("x").getAsInt() == 100 ? 96 : 64);
                return top;
            }
            return null;
        });
        JsonObject result = harness.run("carry_player",
                "{destination:{x:100,y:64,z:0}}");
        assertTrue("the carry should succeed: " + result, result.get("ok").getAsBoolean());
        assertTrue("cruise altitude must clear a 96-block hill: "
                        + result.get("cruiseY").getAsInt(),
                result.get("cruiseY").getAsInt() >= 96 + 8);

        Call pick = null;
        Call unpick = null;
        List<Call> gotoLegs = new ArrayList<>();
        for (Call call : harness.calls) {
            if ("pick_entity".equals(call.name)) pick = call;
            else if ("unpick_entity".equals(call.name)) unpick = call;
            else if ("goto".equals(call.name)) gotoLegs.add(call);
        }
        assertNotNull("the player must actually be mounted", pick);
        assertNotNull("the player must be dismounted", unpick);
        assertTrue("the drop must name the rider", unpick.args.has("entity_id"));
        assertEquals("the drop must be the player that was picked",
                pick.args.get("entity_id").getAsInt(), unpick.args.get("entity_id").getAsInt());

        assertTrue("carry needs climb + cruise + descent legs", gotoLegs.size() >= 3);
        int climbY = gotoLegs.get(0).args.get("y").getAsInt();
        int cruiseY = gotoLegs.get(1).args.get("y").getAsInt();
        int descendY = gotoLegs.get(2).args.get("y").getAsInt();
        assertTrue("the first leg must climb, not fly sideways: " + climbY,
                climbY >= result.get("cruiseY").getAsInt());
        assertTrue("the cruise leg must stay high: " + cruiseY,
                cruiseY >= result.get("cruiseY").getAsInt());
        assertTrue("the last leg must descend to the landing, not hover high: " + descendY,
                descendY < cruiseY);
        assertEquals("the descent must reach the scanned spot", 64,
                gotoLegs.get(2).args.get("y").getAsInt() == 64 ? 64
                        : gotoLegs.get(2).args.get("y").getAsInt());
        for (Call call : gotoLegs) {
            assertFalse("a carried player is never flown with digging enabled: " + call.args,
                    call.args.has("allow_dig") && call.args.get("allow_dig").getAsBoolean());
        }
    }

    @Test
    public void carryPlayerRefusesToDismountWithoutASafeLanding() throws Exception {
        JsonObject player = new JsonObject();
        player.addProperty("ok", true);
        player.addProperty("name", "Tester");
        player.addProperty("x", 0.5);
        player.addProperty("y", 64.0);
        player.addProperty("z", 0.5);
        player.addProperty("blockX", 0);
        player.addProperty("blockY", 64);
        player.addProperty("blockZ", 0);
        player.addProperty("riding", false);
        player.addProperty("underground", false);
        JsonObject entity = new JsonObject();
        entity.addProperty("id", 7);
        entity.addProperty("name", "Tester");
        entity.addProperty("x", 0.5);
        entity.addProperty("y", 64.0);
        entity.addProperty("z", 0.5);
        entity.addProperty("dist", 1.0);
        entity.addProperty("player", true);
        JsonArray entities = new JsonArray();
        entities.add(entity);

        Harness harness = new Harness(library(), state(), (name, args) -> {
            if ("get_player".equals(name)) return player;
            if ("get_entities".equals(name)) {
                JsonObject found = ToolJson.ok();
                found.addProperty("count", 1);
                found.add("entities", entities);
                return found;
            }
            if ("find_safe_landing".equals(name)) {
                // No safe spot anywhere: a mountain face, water, or a void.
                JsonObject none = ToolJson.ok();
                none.addProperty("found", false);
                none.addProperty("safe", false);
                none.addProperty("safeAtRequested", false);
                return none;
            }
            return null;
        });
        JsonObject result = harness.run("carry_player",
                "{destination:{x:20,y:120,z:20}}");
        assertFalse("a trip with no safe landing must not report success", 
                result.get("ok").getAsBoolean());
        boolean dismounted = false;
        for (Call call : harness.calls) {
            if ("unpick_entity".equals(call.name) && call.args.has("entity_id")
                    && call.args.get("entity_id").getAsInt() == 7) dismounted = true;
        }
        assertFalse("the rider must not be released without a safe landing", dismounted);
    }
}
