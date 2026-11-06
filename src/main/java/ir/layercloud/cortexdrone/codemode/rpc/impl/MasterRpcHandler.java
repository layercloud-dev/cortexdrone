package ir.layercloud.cortexdrone.codemode.rpc.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.codemode.rpc.RpcHandler;
import ir.layercloud.cortexdrone.harness.MasterToolCatalog;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.service.FlightPathfinder;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

/**
 * Master tool surface for Code Mode.
 *
 * <p>Only generic primitives are exposed. Mining, harvesting, building,
 * farming etc. are not tools: the AI composes them from inspection +
 * navigation + single-block break/place/interact/use operations.</p>
 *
 * <p>Read-only domain handlers are kept as internal delegates. They are not
 * registered as tools, so the model cannot call a monolithic
 * "mine(filter,count)" or "buildStructure" command.</p>
 */
public class MasterRpcHandler implements RpcHandler {
    private static final double REACH = 4.5;

    private final AIDroneManager manager;
    private final WorldRpcHandler worldReader;
    private final InventoryRpcHandler inventoryReader;

    public MasterRpcHandler(AIDroneManager manager) {
        this.manager = manager;
        this.worldReader = new WorldRpcHandler(manager, manager.getWorldScanner());
        this.inventoryReader = new InventoryRpcHandler(manager.getInventoryService());
    }

    @Override
    public String[] getSupportedMethods() {
        return MasterToolCatalog.METHODS;
    }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        if (args == null) args = new JsonObject();
        if (ctrl == null || ctrl.drone == null || ctrl.drone.worldObj == null) {
            return error("No active drone or world");
        }
        if ("state".equals(m)) return state(ctrl, player, args);
        if ("inspect".equals(m)) return inspect(ctrl, player, args);
        if ("navigate".equals(m) || "tunnel".equals(m) || "digup".equals(m) || "digdown".equals(m)) {
            return this.manager.getActionRpcTranslator().handle(ctrl, player, m, args);
        }
        if ("break_block".equals(m)) return breakBlock(ctrl, player, args);
        if ("place_block".equals(m)) return placeBlock(ctrl, player, args);
        if ("interact_block".equals(m)) return interactBlock(ctrl, player, args);
        if ("use_item".equals(m)) return useItem(ctrl, player, args);
        if ("pick_entity".equals(m) || "unpick_entity".equals(m)) {
            return this.manager.getActionRpcTranslator().handle(ctrl, player, m, args);
        }
        if ("transfer".equals(m)) return transfer(ctrl, player, args);
        if ("craft".equals(m)) return craft(ctrl, player, args);
        if ("effect".equals(m)) return effect(ctrl, player, args);
        if ("skills".equals(m)) return skills(ctrl, player, args);
        if ("cancel".equals(m)) return cancel(ctrl, args);
        return error("Unknown master tool: " + m);
    }

    private JsonObject state(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        JsonObject res = this.manager.getActionRpcTranslator().status(ctrl, player, args);
        String summary = ctrl.task.getSummary();
        res.addProperty("activeTask", ctrl.task.isActive());
        res.addProperty("taskPrompt", ctrl.task.getPrompt());
        res.addProperty("taskSummary", summary);
        res.addProperty("taskAgeMs", ctrl.task.getAgeMs());
        res.addProperty("navigationRoute", ctrl.navigationRoute != null ? ctrl.navigationRoute : "auto");
        res.addProperty("navViaSurface", ctrl.navViaSurface);
        JsonArray notes = new JsonArray();
        for (String note : ctrl.task.getNotes()) notes.add(new JsonPrimitive(note));
        res.add("taskNotes", notes);
        double distGoal = Double.isNaN(ctrl.goalX) ? -1.0
            : Math.sqrt((ctrl.drone.posX - ctrl.goalX) * (ctrl.drone.posX - ctrl.goalX)
                + (ctrl.drone.posY - ctrl.goalY) * (ctrl.drone.posY - ctrl.goalY)
                + (ctrl.drone.posZ - ctrl.goalZ) * (ctrl.drone.posZ - ctrl.goalZ));
        res.addProperty("distanceToGoal", Math.round(distGoal * 100.0) / 100.0);
        res.addProperty("arrived", ctrl.mode != Mode.GOTO || (distGoal >= 0.0 && distGoal <= 1.0));
        if (ctrl.currentMineTarget != null) {
            JsonObject target = new JsonObject();
            target.addProperty("x", ctrl.currentMineTarget[0]);
            target.addProperty("y", ctrl.currentMineTarget[1]);
            target.addProperty("z", ctrl.currentMineTarget[2]);
            res.add("activeBreakTarget", target);
        }
        return res;
    }

    private JsonObject inspect(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        String op = str(args, "op", "block").trim().toLowerCase();
        String method;
        RpcHandler handler;
        if (op.equals("block") || op.equals("getblock")) { method = "get_block"; handler = this.worldReader; }
        else if (op.equals("box") || op.equals("blocksinbox")) { method = "get_blocks_in_box"; handler = this.worldReader; }
        else if (op.equals("top") || op.equals("topy")) { method = "get_top_y"; handler = this.worldReader; }
        else if (op.equals("room") || op.equals("scanroom")) { method = "scan_room"; handler = this.worldReader; }
        else if (op.equals("blocks") || op.equals("scanblocks")) { method = "scan_blocks"; handler = this.worldReader; }
        else if (op.equals("veins") || op.equals("scanveins")) { method = "scan_veins"; handler = this.worldReader; }
        else if (op.equals("distant") || op.equals("finddistant")) { method = "find_distant"; handler = this.worldReader; }
        else if (op.equals("entities") || op.equals("getentities")) { method = "get_entities"; handler = this.worldReader; }
        else if (op.equals("drops") || op.equals("getdrops")) { method = "get_drops"; handler = this.worldReader; }
        else if (op.equals("raycast")) { method = "raycast_player"; handler = this.worldReader; }
        else if (op.equals("base") || op.equals("findbase")) { method = "find_base"; handler = this.worldReader; }
        else if (op.equals("locations") || op.equals("listlocations")) { method = "list_locations"; handler = this.worldReader; }
        else if (op.equals("location") || op.equals("getlocation")) { method = "get_location"; handler = this.worldReader; }
        else if (op.equals("buildsite") || op.equals("findbuildsite")) { method = "find_build_site"; handler = this.worldReader; }
        else if (op.equals("plan") || op.equals("planstructure")) { method = "plan_structure"; handler = this.worldReader; }
        else if (op.equals("player") || op.equals("getplayer")) { method = "get_player"; handler = this.inventoryReader; }
        else if (op.equals("playerinventory")) { method = "get_player_inventory"; handler = this.inventoryReader; }
        else if (op.equals("recipe") || op.equals("recipes") || op.equals("getrecipes")) { method = "get_recipes"; handler = this.inventoryReader; }
        else if (op.equals("chest") || op.equals("getchest")) { method = "get_chest"; handler = this.inventoryReader; }
        else if (op.equals("chests") || op.equals("scanchests")) { method = "scan_chests"; handler = this.inventoryReader; }
        else if (op.equals("finditem")) { method = "find_item"; handler = this.inventoryReader; }
        else if (op.equals("finditeminchests")) { method = "find_item_in_chests"; handler = this.inventoryReader; }
        else if (op.equals("furnaces") || op.equals("scanfurnaces")) { method = "scan_furnaces"; handler = this.inventoryReader; }
        else if (op.equals("machinerecipes")) { method = "get_machine_recipes"; handler = this.inventoryReader; }
        else if (op.equals("machinerecipe")) { method = "get_machine_recipe"; handler = this.inventoryReader; }
        else if (op.equals("machines") || op.equals("scanmachines")) { method = "scan_machines"; handler = this.inventoryReader; }
        else if (op.equals("machineingredients")) { method = "machine_ingredients"; handler = this.inventoryReader; }
        else if (op.equals("breadcrumbs")) return this.manager.getActionRpcTranslator().handle(ctrl, player, "get_breadcrumbs", args);
        else if (op.equals("doc") || op.equals("lookup") || op.equals("lookupdoc")) { method = "lookup_doc"; handler = this.worldReader; }
        else return error("Unknown inspect op: " + op);
        return handler.handle(ctrl, player, method, args);
    }

    private JsonObject breakBlock(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        if (!args.has("x") || !args.has("y") || !args.has("z")) return error("break_block requires x/y/z");
        int x = intValue(args, "x", 0);
        int y = intValue(args, "y", 0);
        int z = intValue(args, "z", 0);
        World world = ctrl.drone.worldObj;
        if (!ItemClassifier.isMineableBlock(world, x, y, z)) {
            return error("Block at (" + x + "," + y + "," + z + ") is not mineable/air");
        }
        double dist = ctrl.drone.getDistance(x + 0.5, y + 0.5, z + 0.5);
        if (dist > REACH) {
            JsonObject res = needsMovement(ctrl, x, y, z, "break", dist);
            if (res != null) return res;
        }
        JsonObject res = this.manager.getActionRpcTranslator().handle(ctrl, player, "break_block", args);
        res.add("target", point(x, y, z));
        return res;
    }

    private JsonObject placeBlock(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        if (!args.has("x") || !args.has("y") || !args.has("z")) return error("place_block requires x/y/z");
        int x = intValue(args, "x", 0);
        int y = intValue(args, "y", 0);
        int z = intValue(args, "z", 0);
        World world = ctrl.drone.worldObj;
        if (!ItemClassifier.isReplaceableBlock(world, x, y, z)) {
            return error("Target (" + x + "," + y + "," + z + ") is already occupied/protected");
        }
        double dist = ctrl.drone.getDistance(x + 0.5, y + 0.5, z + 0.5);
        if (dist > REACH) {
            JsonObject res = needsMovement(ctrl, x, y, z, "place", dist);
            if (res != null) return res;
        }
        String filter = args.has("block") ? str(args, "block", "any")
            : (args.has("with") ? str(args, "with", "any") : str(args, "item", "any"));
        JsonObject res = this.manager.getActionRpcTranslator().handle(ctrl, player, "place_block", args);
        res.addProperty("block", filter);
        res.add("target", point(x, y, z));
        return res;
    }

    private JsonObject interactBlock(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        if (!args.has("x") || !args.has("y") || !args.has("z")) return error("interact_block requires x/y/z");
        int x = intValue(args, "x", 0);
        int y = intValue(args, "y", 0);
        int z = intValue(args, "z", 0);
        double dist = ctrl.drone.getDistance(x + 0.5, y + 0.5, z + 0.5);
        if (dist > REACH) {
            JsonObject res = needsMovement(ctrl, x, y, z, "interact", dist);
            if (res != null) return res;
        }
        return this.manager.getActionRpcTranslator().handle(ctrl, player, "interact_block", args);
    }

    private JsonObject useItem(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        if (args.has("x") && args.has("y") && args.has("z")) {
            int x = intValue(args, "x", 0);
            int y = intValue(args, "y", 0);
            int z = intValue(args, "z", 0);
            double dist = ctrl.drone.getDistance(x + 0.5, y + 0.5, z + 0.5);
            if (dist > REACH) {
                JsonObject res = needsMovement(ctrl, x, y, z, "use", dist);
                if (res != null) return res;
            }
        }
        return this.manager.getActionRpcTranslator().handle(ctrl, player, "use_item", args);
    }

    private JsonObject transfer(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        return this.manager.getActionRpcTranslator().handle(ctrl, player, "transfer", args);
    }

    private JsonObject craft(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        return this.manager.getActionRpcTranslator().handle(ctrl, player, "craft", args);
    }

    private JsonObject effect(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        String kind = str(args, "kind", "led").trim().toLowerCase();
        String method;
        if (kind.equals("led") || kind.equals("color")) method = "set_led";
        else if (kind.equals("ledeffect") || kind.equals("effect")) method = "led_effect";
        else if (kind.equals("particles")) method = "spawn_particles";
        else if (kind.equals("sound")) method = "play_sound";
        else if (kind.equals("note")) method = "play_note";
        else if (kind.equals("status")) method = "set_status_text";
        else if (kind.equals("say")) method = "say";
        else return error("Unknown effect kind: " + kind);
        return this.manager.getActionRpcTranslator().handle(ctrl, player, method, args);
    }

    private JsonObject skills(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        String op = str(args, "op", "search").trim().toLowerCase();
        String method;
        if (op.equals("search")) method = "search_skills";
        else if (op.equals("list")) method = "list_skills";
        else if (op.equals("save")) method = "save_skill";
        else return error("Unknown skills op: " + op);
        return this.manager.getActionRpcTranslator().skills(ctrl, player, method, args);
    }

    private JsonObject cancel(DroneController ctrl, JsonObject args) {
        int callerLoopId = intValue(args, "loopId", 0);
        int cancelled = ctrl.cancelOtherLoops(callerLoopId);
        this.manager.clearActionState(ctrl);
        ctrl.task.cancel();
        this.manager.getMovementService().stop(ctrl, "Master cancel");
        JsonObject res = ok();
        res.addProperty("cancelled", true);
        res.addProperty("cancelledLoops", cancelled);
        return res;
    }

    private JsonObject needsMovement(DroneController ctrl, int x, int y, int z, String action, double dist) {
        World world = ctrl.drone.worldObj;
        double[] st = FlightPathfinder.findMiningStandoff(world, ctrl.drone, x, y, z);
        if (st == null) {
            int[] open = FlightPathfinder.findNearestPassableVoxel(world, x, y + 1, z, 3);
            if (open == null) return error("No reachable standoff near (" + x + "," + y + "," + z + ")");
            st = new double[]{open[0] + 0.5, open[1] + 0.35, open[2] + 0.5};
        }
        JsonObject res = ok();
        res.addProperty("needsMovement", true);
        res.addProperty("action", action);
        res.addProperty("distance", Math.round(dist * 100.0) / 100.0);
        res.add("standoff", point(st[0], st[1], st[2]));
        res.add("target", point(x, y, z));
        return res;
    }

    private static JsonObject point(double x, double y, double z) {
        JsonObject p = new JsonObject();
        p.addProperty("x", Math.round(x * 100.0) / 100.0);
        p.addProperty("y", Math.round(y * 100.0) / 100.0);
        p.addProperty("z", Math.round(z * 100.0) / 100.0);
        return p;
    }

    private static JsonObject ok() {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        return res;
    }

    private static JsonObject error(String message) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", false);
        res.addProperty("error", message);
        return res;
    }

    private static String str(JsonObject args, String key, String fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return fallback;
        try { return args.get(key).getAsString(); } catch (Exception e) { return fallback; }
    }

    private static int intValue(JsonObject args, String key, int fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return fallback;
        try { return args.get(key).getAsInt(); } catch (Exception e) { return fallback; }
    }
}
