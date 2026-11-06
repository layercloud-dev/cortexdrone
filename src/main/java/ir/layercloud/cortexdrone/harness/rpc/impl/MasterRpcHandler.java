package ir.layercloud.cortexdrone.harness.rpc.impl;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.harness.ToolRegistry;
import ir.layercloud.cortexdrone.harness.session.DroneSession;
import ir.layercloud.cortexdrone.harness.rpc.RpcHandler;
import ir.layercloud.cortexdrone.harness.rpc.translate.BreadcrumbRpcTranslator;
import ir.layercloud.cortexdrone.harness.rpc.translate.RpcJson;
import ir.layercloud.cortexdrone.manager.navigation.NavigationState;
import ir.layercloud.cortexdrone.manager.navigation.impl.NavigationManager;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
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
    private final BreadcrumbRpcTranslator breadcrumbReader;
    private final ToolRegistry tools;

    public MasterRpcHandler(AIDroneManager manager) {
        this.manager = manager;
        this.worldReader = new WorldRpcHandler(manager, manager.getWorldScanner());
        this.inventoryReader = new InventoryRpcHandler(manager.getInventoryService());
        this.breadcrumbReader = new BreadcrumbRpcTranslator(manager.getBreadcrumbService());
        this.tools = ToolRegistry.master();
    }

    @Override
    public String[] getSupportedMethods() {
        return this.tools.names();
    }

    /** The tools this handler exposes, with their descriptions. */
    public ToolRegistry toolRegistry() {
        return this.tools;
    }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        if (args == null) args = new JsonObject();
        if (ctrl == null || ctrl.drone == null || ctrl.drone.worldObj == null) {
            return error("No active drone or world");
        }
        if ("state".equals(m)) return state(ctrl, args);
        if ("inspect".equals(m)) return inspect(ctrl, player, args);
        if ("goto".equals(m) || "stop".equals(m)) {
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
        if ("visuals".equals(m)) return visuals(ctrl, player, args);
        if ("skills".equals(m)) return skills(ctrl, args);
        if ("cancel".equals(m)) return cancel(ctrl, args);
        return error("Unknown master tool: " + m);
    }

    private JsonObject state(DroneController ctrl, JsonObject args) {
        JsonObject res = this.manager.getActionRpcTranslator().status(ctrl, args);
        DroneSession session = this.manager.getSessionManager().session(ctrl);
        String summary = session.getSummary();
        res.addProperty("activeTask", session.isActive());
        res.addProperty("taskPrompt", session.getPrompt());
        res.addProperty("taskSummary", summary);
        res.addProperty("taskAgeMs", session.getAgeMs());
        JsonArray notes = new JsonArray();
        for (String note : session.getNotes()) notes.add(new JsonPrimitive(note));
        res.add("taskNotes", notes);
        NavigationState nav = this.manager.getNavigationManager().state(ctrl);
        double distGoal = nav == null || Double.isNaN(nav.goalX) ? -1.0
                : Math.sqrt((ctrl.drone.posX - nav.goalX) * (ctrl.drone.posX - nav.goalX)
                + (ctrl.drone.posY - nav.goalY) * (ctrl.drone.posY - nav.goalY)
                + (ctrl.drone.posZ - nav.goalZ) * (ctrl.drone.posZ - nav.goalZ));
        res.addProperty("distanceToGoal", Math.round(distGoal * 100.0) / 100.0);
        NavigationManager.Status navigation = this.manager.getNavigationManager().status(ctrl);
        res.addProperty("navigationActive", navigation.isActive());
        res.addProperty("navigationPhase", navigation.getPhase());
        res.addProperty("navigationReason", navigation.getReason());
        res.addProperty("navigationError", nav != null ? nav.error : "");
        res.addProperty("arrived", !navigation.isActive()
                && distGoal >= 0.0 && distGoal <= 1.0);
        res.addProperty("busy", navigation.isActive()
                || this.manager.getActionRegistry().activeContextCount(ctrl) > 0);
        return res;
    }

    private JsonObject inspect(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        String op = str(args, "op", "block").trim().toLowerCase();
        String method;
        RpcHandler handler;
        switch (op) {
            case "block":
            case "getblock":
                method = "get_block";
                handler = this.worldReader;
                break;
            case "box":
            case "blocksinbox":
                method = "get_blocks_in_box";
                handler = this.worldReader;
                break;
            case "top":
            case "topy":
                method = "get_top_y";
                handler = this.worldReader;
                break;
            case "room":
            case "scanroom":
                method = "scan_room";
                handler = this.worldReader;
                break;
            case "blocks":
            case "scanblocks":
                method = "scan_blocks";
                handler = this.worldReader;
                break;
            case "veins":
            case "scanveins":
                method = "scan_veins";
                handler = this.worldReader;
                break;
            case "distant":
            case "finddistant":
                method = "find_distant";
                handler = this.worldReader;
                break;
            case "entities":
            case "getentities":
                method = "get_entities";
                handler = this.worldReader;
                break;
            case "drops":
            case "getdrops":
                method = "get_drops";
                handler = this.worldReader;
                break;
            case "raycast":
                method = "raycast_player";
                handler = this.worldReader;
                break;
            case "buildsite":
            case "findbuildsite":
                method = "find_build_site";
                handler = this.worldReader;
                break;
            case "plan":
            case "planstructure":
                method = "plan_structure";
                handler = this.worldReader;
                break;
            case "player":
            case "getplayer":
                method = "get_player";
                handler = this.inventoryReader;
                break;
            case "playerinventory":
                method = "get_player_inventory";
                handler = this.inventoryReader;
                break;
            case "recipe":
            case "recipes":
            case "getrecipes":
                method = "get_recipes";
                handler = this.inventoryReader;
                break;
            case "chest":
            case "getchest":
                method = "get_chest";
                handler = this.inventoryReader;
                break;
            case "chests":
            case "scanchests":
                method = "scan_chests";
                handler = this.inventoryReader;
                break;
            case "finditem":
                method = "find_item";
                handler = this.inventoryReader;
                break;
            case "finditeminchests":
                method = "find_item_in_chests";
                handler = this.inventoryReader;
                break;
            case "furnaces":
            case "scanfurnaces":
                method = "scan_furnaces";
                handler = this.inventoryReader;
                break;
            case "machinerecipes":
                method = "get_machine_recipes";
                handler = this.inventoryReader;
                break;
            case "machinerecipe":
                method = "get_machine_recipe";
                handler = this.inventoryReader;
                break;
            case "machines":
            case "scanmachines":
                method = "scan_machines";
                handler = this.inventoryReader;
                break;
            case "machineingredients":
                method = "machine_ingredients";
                handler = this.inventoryReader;
                break;
            case "breadcrumbs":
                return this.breadcrumbReader.read(ctrl, args);
            case "doc":
            case "lookup":
            case "lookupdoc":
                method = "lookup_doc";
                handler = this.worldReader;
                break;
            default:
                return error("Unknown inspect op: " + op);
        }
        return handler.handle(ctrl, player, method, args);
    }

    private JsonObject breakBlock(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        if (!args.has("x") || !args.has("y") || !args.has("z")) return error("break_block requires x/y/z");
        int x = intValue(args, "x");
        int y = intValue(args, "y");
        int z = intValue(args, "z");
        World world = ctrl.drone.worldObj;
        if (!ItemClassifier.isMineableBlock(world, x, y, z)) {
            return error("Block at (" + x + "," + y + "," + z + ") is not mineable/air");
        }
        double dist = ctrl.drone.getDistance(x + 0.5, y + 0.5, z + 0.5);
        if (dist > REACH) {
            return needsMovement(ctrl, x, y, z, "break", dist);
        }
        JsonObject res = this.manager.getActionRpcTranslator().handle(ctrl, player, "break_block", args);
        res.add("target", point(x, y, z));
        return res;
    }

    private JsonObject placeBlock(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        if (!args.has("x") || !args.has("y") || !args.has("z")) return error("place_block requires x/y/z");
        int x = intValue(args, "x");
        int y = intValue(args, "y");
        int z = intValue(args, "z");
        World world = ctrl.drone.worldObj;
        if (!ItemClassifier.isReplaceableBlock(world, x, y, z)) {
            return error("Target (" + x + "," + y + "," + z + ") is already occupied/protected");
        }
        double dist = ctrl.drone.getDistance(x + 0.5, y + 0.5, z + 0.5);
        if (dist > REACH) {
            return needsMovement(ctrl, x, y, z, "place", dist);
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
        int x = intValue(args, "x");
        int y = intValue(args, "y");
        int z = intValue(args, "z");
        double dist = ctrl.drone.getDistance(x + 0.5, y + 0.5, z + 0.5);
        if (dist > REACH) {
            return needsMovement(ctrl, x, y, z, "interact", dist);
        }
        return this.manager.getActionRpcTranslator().handle(ctrl, player, "interact_block", args);
    }

    private JsonObject useItem(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        if (args.has("x") && args.has("y") && args.has("z")) {
            int x = intValue(args, "x");
            int y = intValue(args, "y");
            int z = intValue(args, "z");
            double dist = ctrl.drone.getDistance(x + 0.5, y + 0.5, z + 0.5);
            if (dist > REACH) {
                return needsMovement(ctrl, x, y, z, "use", dist);
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

    private JsonObject visuals(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        String kind = str(args, "kind", "led").trim().toLowerCase();
        String method;
        switch (kind) {
            case "led":
            case "color":
                method = "set_led";
                break;
            case "ledeffect":
                method = "led_effect";
                break;
            case "light":
            case "aura":
                method = "set_light";
                break;
            case "particles":
                method = "spawn_particles";
                break;
            case "sound":
                method = "play_sound";
                break;
            case "note":
                method = "play_note";
                break;
            case "status":
                method = "set_status_text";
                break;
            case "say":
                method = "say";
                break;
            default:
                return error("Unknown visuals kind: " + kind);
        }
        return this.manager.getActionRpcTranslator().handle(ctrl, player, method, args);
    }

    private JsonObject skills(DroneController ctrl, JsonObject args) {
        String op = str(args, "op", "search").trim().toLowerCase();
        String method;
        switch (op) {
            case "search":
                method = "search_skills";
                break;
            case "list":
                method = "list_skills";
                break;
            case "save":
                method = "save_skill";
                break;
            default:
                return error("Unknown skills op: " + op);
        }
        return this.manager.getActionRpcTranslator().skills(ctrl, method, args);
    }

    private JsonObject cancel(DroneController ctrl, JsonObject args) {
        int callerLoopId = intValue(args, "loopId");
        DroneSession session = this.manager.getSessionManager().session(ctrl);
        int cancelled = session.cancelOtherLoops(callerLoopId);
        this.manager.clearActionState(ctrl);
        session.cancel();
        this.manager.getNavigationManager().stop(ctrl, null, "Master cancel");
        JsonObject res = RpcJson.ok();
        res.addProperty("cancelled", true);
        res.addProperty("cancelledLoops", cancelled);
        return res;
    }

    private JsonObject needsMovement(DroneController ctrl, int x, int y, int z, String action, double dist) {
        World world = ctrl.drone.worldObj;
        double[] st = FlightPathfinder.findBlockStandoff(world, ctrl.drone, x, y, z);
        if (st == null) {
            int[] open = FlightPathfinder.findNearestPassableVoxel(world, x, y + 1, z, 3);
            if (open == null) return error("No reachable standoff near (" + x + "," + y + "," + z + ")");
            st = new double[]{open[0] + 0.5, open[1] + 0.35, open[2] + 0.5};
        }
        JsonObject res = RpcJson.ok();
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

    private static JsonObject error(String message) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", false);
        res.addProperty("error", message);
        return res;
    }

    private static String str(JsonObject args, String key, String fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return fallback;
        try {
            return args.get(key).getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int intValue(JsonObject args, String key) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return 0;
        try {
            return args.get(key).getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }
}
