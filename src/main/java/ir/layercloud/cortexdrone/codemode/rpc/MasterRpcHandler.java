package ir.layercloud.cortexdrone.codemode.rpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.behavior.BuildingService;
import ir.layercloud.cortexdrone.behavior.CombatService;
import ir.layercloud.cortexdrone.behavior.MiningService;
import ir.layercloud.cortexdrone.harness.MasterToolCatalog;
import ir.layercloud.cortexdrone.inventory.ChestSorter;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.inventory.SmeltingService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.FlightPathfinder;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import ir.layercloud.cortexdrone.world.WorldScanner;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
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
    private final WorldScanner worldScanner;
    private final FlightPathfinder pathfinder;
    private final InventoryService inventoryService;
    private final ChestSorter chestSorter;
    private final SmeltingService smeltingService;
    private final MiningService miningService;
    private final BuildingService buildingService;
    private final CombatService combatService;
    private final WorldRpcHandler worldReader;
    private final InventoryRpcHandler inventoryReader;
    private final NavigationRpcHandler navigationReader;
    private final ActionRpcHandler actionPrimitives;
    private final EffectsRpcHandler effects;

    public MasterRpcHandler(
        AIDroneManager manager,
        WorldScanner worldScanner,
        FlightPathfinder pathfinder,
        InventoryService inventoryService,
        ChestSorter chestSorter,
        SmeltingService smeltingService,
        MiningService miningService,
        BuildingService buildingService,
        CombatService combatService
    ) {
        this.manager = manager;
        this.worldScanner = worldScanner;
        this.pathfinder = pathfinder;
        this.inventoryService = inventoryService;
        this.chestSorter = chestSorter;
        this.smeltingService = smeltingService;
        this.miningService = miningService;
        this.buildingService = buildingService;
        this.combatService = combatService;
        this.worldReader = new WorldRpcHandler(manager, worldScanner, buildingService);
        this.inventoryReader = new InventoryRpcHandler(manager, inventoryService, chestSorter, smeltingService);
        this.navigationReader = new NavigationRpcHandler(manager, pathfinder, worldScanner);
        this.actionPrimitives = new ActionRpcHandler(manager, inventoryService);
        this.effects = new EffectsRpcHandler(manager);
    }

    @Override
    public String[] getSupportedMethods() {
        return MasterToolCatalog.METHODS;
    }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        if (args == null) args = new JsonObject();
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) {
            return error("No active drone or world");
        }
        if ("state".equals(m)) return state(ctrl, player, args);
        if ("inspect".equals(m)) return inspect(ctrl, player, args);
        if ("navigate".equals(m)) return navigate(ctrl, args);
        if ("break_block".equals(m)) return breakBlock(ctrl, player, args);
        if ("place_block".equals(m)) return placeBlock(ctrl, player, args);
        if ("interact_block".equals(m)) return interactBlock(ctrl, player, args);
        if ("use_item".equals(m)) return useItem(ctrl, player, args);
        if ("transfer".equals(m)) return transfer(ctrl, player, args);
        if ("craft".equals(m)) return craft(ctrl, player, args);
        if ("effect".equals(m)) return effect(ctrl, player, args);
        if ("skills".equals(m)) return skills(ctrl, player, args);
        if ("cancel".equals(m)) return cancel(ctrl, args);
        return error("Unknown master tool: " + m);
    }

    private JsonObject state(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        JsonObject res = this.effects.handle(ctrl, player, "get_drone", args);
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
            : Math.sqrt((ctrl.drone.field_70165_t - ctrl.goalX) * (ctrl.drone.field_70165_t - ctrl.goalX)
                + (ctrl.drone.field_70163_u - ctrl.goalY) * (ctrl.drone.field_70163_u - ctrl.goalY)
                + (ctrl.drone.field_70161_v - ctrl.goalZ) * (ctrl.drone.field_70161_v - ctrl.goalZ));
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
        else if (op.equals("breadcrumbs")) { method = "get_breadcrumbs"; handler = this.navigationReader; }
        else if (op.equals("doc") || op.equals("lookup") || op.equals("lookupdoc")) { method = "lookup_doc"; handler = this.worldReader; }
        else return error("Unknown inspect op: " + op);
        return handler.handle(ctrl, player, method, args);
    }

    private JsonObject navigate(DroneController ctrl, JsonObject args) {
        String route = str(args, "route", "auto").trim().toLowerCase();
        if (!route.equals("auto") && !route.equals("direct") && !route.equals("tunnel") && !route.equals("up")) {
            route = "auto";
        }
        double gx;
        double gy;
        double gz;
        JsonObject standoffOf = obj(args, "standoffOf");
        if (standoffOf == null) standoffOf = obj(args, "nearBlock");
        if (standoffOf != null) {
            int bx = intValue(standoffOf, "x", 0);
            int by = intValue(standoffOf, "y", 0);
            int bz = intValue(standoffOf, "z", 0);
            double[] st = FlightPathfinder.findMiningStandoff(ctrl.drone.field_70170_p, ctrl.drone, bx, by, bz);
            if (st == null) {
                int[] open = FlightPathfinder.findNearestPassableVoxel(ctrl.drone.field_70170_p, bx, by + 1, bz, 3);
                if (open == null) return error("No reachable standoff near (" + bx + "," + by + "," + bz + ")");
                st = new double[]{open[0] + 0.5, open[1] + 0.35, open[2] + 0.5};
            }
            gx = st[0]; gy = st[1]; gz = st[2];
        } else {
            if (!args.has("x") || !args.has("y") || !args.has("z")) {
                return error("navigate requires x/y/z (or standoffOf:{x,y,z})");
            }
            gx = num(args, "x", 0.0);
            gy = num(args, "y", 0.0);
            gz = num(args, "z", 0.0);
        }

        ctrl.navigationRoute = route;
        ctrl.navViaSurface = false;
        ctrl.navFinalX = Double.NaN;
        ctrl.navFinalY = Double.NaN;
        ctrl.navFinalZ = Double.NaN;

        if ("up".equals(route)) {
            int cx = MathHelper.func_76128_c(ctrl.drone.field_70165_t);
            int cy = MathHelper.func_76128_c(ctrl.drone.field_70163_u);
            int cz = MathHelper.func_76128_c(ctrl.drone.field_70161_v);
            boolean sky = ctrl.drone.field_70170_p.func_72937_j(cx, cy, cz)
                || cy >= ctrl.drone.field_70170_p.func_72976_f(cx, cz) - 1;
            if (!sky) {
                ctrl.navFinalX = gx;
                ctrl.navFinalY = gy;
                ctrl.navFinalZ = gz;
                int top = ctrl.drone.field_70170_p.func_72976_f(cx, cz);
                gx = cx + 0.5;
                gy = Math.min(250, top + 2.5);
                gz = cz + 0.5;
                ctrl.navViaSurface = true;
            }
        }

        ctrl.goalX = gx;
        ctrl.goalY = gy;
        ctrl.goalZ = gz;
        this.manager.setDroneMode(ctrl, Mode.GOTO, "Master navigate " + route + String.format(" (%.1f,%.1f,%.1f)", gx, gy, gz));
        JsonObject res = ok();
        res.addProperty("route", route);
        res.addProperty("viaSurface", ctrl.navViaSurface);
        res.add("goal", point(gx, gy, gz));
        return res;
    }

    private JsonObject breakBlock(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        if (!args.has("x") || !args.has("y") || !args.has("z")) return error("break_block requires x/y/z");
        int x = intValue(args, "x", 0);
        int y = intValue(args, "y", 0);
        int z = intValue(args, "z", 0);
        World world = ctrl.drone.field_70170_p;
        if (!ItemClassifier.isMineableBlock(world, x, y, z)) {
            return error("Block at (" + x + "," + y + "," + z + ") is not mineable/air");
        }
        double dist = ctrl.drone.func_70011_f(x + 0.5, y + 0.5, z + 0.5);
        if (dist > REACH) {
            JsonObject res = needsMovement(ctrl, x, y, z, "break", dist);
            if (res != null) return res;
        }
        boolean started = this.miningService.startSingleBlockTask(ctrl, player, x, y, z);
        JsonObject res = ok();
        res.addProperty("started", started);
        res.add("target", point(x, y, z));
        if (!started) res.addProperty("error", "Could not start single-block break");
        return res;
    }

    private JsonObject placeBlock(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        if (!args.has("x") || !args.has("y") || !args.has("z")) return error("place_block requires x/y/z");
        int x = intValue(args, "x", 0);
        int y = intValue(args, "y", 0);
        int z = intValue(args, "z", 0);
        World world = ctrl.drone.field_70170_p;
        if (!ItemClassifier.isReplaceableBlock(world, x, y, z)) {
            return error("Target (" + x + "," + y + "," + z + ") is already occupied/protected");
        }
        double dist = ctrl.drone.func_70011_f(x + 0.5, y + 0.5, z + 0.5);
        if (dist > REACH) {
            JsonObject res = needsMovement(ctrl, x, y, z, "place", dist);
            if (res != null) return res;
        }
        String filter = args.has("block") ? str(args, "block", "any")
            : (args.has("with") ? str(args, "with", "any") : str(args, "item", "any"));
        int meta = intValue(args, "meta", -1);
        boolean placed = this.miningService.placeSingleBlock(ctrl, player, x, y, z, filter, meta);
        JsonObject res = ok();
        res.addProperty("placed", placed);
        res.addProperty("block", filter);
        res.add("target", point(x, y, z));
        if (!placed) res.addProperty("error", "Cannot place: no matching block in drone/chest/player inventory or target invalid");
        return res;
    }

    private JsonObject interactBlock(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        if (!args.has("x") || !args.has("y") || !args.has("z")) return error("interact_block requires x/y/z");
        int x = intValue(args, "x", 0);
        int y = intValue(args, "y", 0);
        int z = intValue(args, "z", 0);
        double dist = ctrl.drone.func_70011_f(x + 0.5, y + 0.5, z + 0.5);
        if (dist > REACH) {
            JsonObject res = needsMovement(ctrl, x, y, z, "interact", dist);
            if (res != null) return res;
        }
        return this.actionPrimitives.handle(ctrl, player, "interact_block", args);
    }

    private JsonObject useItem(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        if (args.has("x") && args.has("y") && args.has("z")) {
            int x = intValue(args, "x", 0);
            int y = intValue(args, "y", 0);
            int z = intValue(args, "z", 0);
            double dist = ctrl.drone.func_70011_f(x + 0.5, y + 0.5, z + 0.5);
            if (dist > REACH) {
                JsonObject res = needsMovement(ctrl, x, y, z, "use", dist);
                if (res != null) return res;
            }
        }
        return this.actionPrimitives.handle(ctrl, player, "use_item", args);
    }

    private JsonObject transfer(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        String op = str(args, "op", "move").trim().toLowerCase();
        String method;
        if (op.equals("move") || op.equals("moveslot")) method = "move_slot";
        else if (op.equals("withdraw") || op.equals("withdrawchest")) method = "withdraw_chest";
        else if (op.equals("deposit") || op.equals("depositchest")) method = "deposit_chest";
        else if (op.equals("give") || op.equals("givetoplayer")) method = "give_to_player";
        else if (op.equals("take") || op.equals("takefromplayer")) method = "take_from_player";
        else if (op.equals("drop")) method = "drop";
        else if (op.equals("suck") || op.equals("suckitems")) method = "suck_items";
        else return error("Unknown transfer op: " + op);
        return this.inventoryReader.handle(ctrl, player, method, args);
    }

    private JsonObject craft(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        return this.inventoryReader.handle(ctrl, player, "craft", args);
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
        return this.effects.handle(ctrl, player, method, args);
    }

    private JsonObject skills(DroneController ctrl, EntityPlayerMP player, JsonObject args) {
        String op = str(args, "op", "search").trim().toLowerCase();
        String method;
        if (op.equals("search")) method = "search_skills";
        else if (op.equals("list")) method = "list_skills";
        else if (op.equals("save")) method = "save_skill";
        else return error("Unknown skills op: " + op);
        return this.effects.handle(ctrl, player, method, args);
    }

    private JsonObject cancel(DroneController ctrl, JsonObject args) {
        int callerLoopId = intValue(args, "loopId", 0);
        int cancelled = ctrl.cancelOtherLoops(callerLoopId);
        ctrl.clearAllTaskQueues();
        ctrl.task.cancel();
        ctrl.navViaSurface = false;
        ctrl.navFinalX = Double.NaN;
        ctrl.navFinalY = Double.NaN;
        ctrl.navFinalZ = Double.NaN;
        ctrl.goalX = ctrl.drone.field_70165_t;
        ctrl.goalY = ctrl.drone.field_70163_u;
        ctrl.goalZ = ctrl.drone.field_70161_v;
        this.manager.setDroneMode(ctrl, Mode.STAY, "Master cancel");
        JsonObject res = ok();
        res.addProperty("cancelled", true);
        res.addProperty("cancelledLoops", cancelled);
        return res;
    }

    private JsonObject needsMovement(DroneController ctrl, int x, int y, int z, String action, double dist) {
        World world = ctrl.drone.field_70170_p;
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

    private static JsonObject obj(JsonObject args, String key) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull() || !args.get(key).isJsonObject()) return null;
        return args.getAsJsonObject(key);
    }

    private static String str(JsonObject args, String key, String fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return fallback;
        try { return args.get(key).getAsString(); } catch (Exception e) { return fallback; }
    }

    private static double num(JsonObject args, String key, double fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return fallback;
        try { return args.get(key).getAsDouble(); } catch (Exception e) { return fallback; }
    }

    private static int intValue(JsonObject args, String key, int fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return fallback;
        try { return args.get(key).getAsInt(); } catch (Exception e) { return fallback; }
    }
}
