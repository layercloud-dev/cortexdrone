package ir.layercloud.cortexdrone.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.FlightPathfinder;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.util.ArrayList;
import java.util.List;
import li.cil.oc.common.entity.Drone;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * Drives HBM processing TileEntities (Assembly / Chemical / Blast / Anvil-like
 * generic machines) through the standard IInventory contract. Resolution of
 * machine location, ingredient availability, and output creation reuse the
 * shared InventoryService withdraw/insert helpers; power and fluid handling are
 * discovered via reflection on TileEntityMachineBase (getPower/setPower) and
 * IFluidStandardTransceiverMK2 (getReceivingTanks / getSendingTanks).
 * The tick loop mirrors SmeltingService: fly to standoff, load input slots,
 * poll output slots, extract, and finish when the machine is done.
 */
public class MachineCraftExecutor {
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final FlightPathfinder pathfinder;
    private final InventoryService inventoryService;
    private final MachineRecipeService recipeService;

    public MachineCraftExecutor(
        AIDroneManager manager,
        DroneLogger logger,
        FlightPathfinder pathfinder,
        InventoryService inventoryService,
        MachineRecipeService recipeService
    ) {
        this.manager = manager;
        this.logger = logger;
        this.pathfinder = pathfinder;
        this.inventoryService = inventoryService;
        this.recipeService = recipeService;
    }

    private static class LoadResult {
        boolean ok;
        int loadedItems;
        int loadedStacks;
        String detail;
    }

    private static final int MAX_RECIPE_DURATION_CACHE = 64;
    private final java.util.Map<String, Integer> recipeDurationCache = new java.util.HashMap<String, Integer>();

    public JsonObject machineCraft(World world, DroneController ctrl, EntityPlayerMP player, String targetFilter, String machineFilter, int count, int radius) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        if (ctrl == null || ctrl.drone == null || targetFilter == null || targetFilter.trim().isEmpty()) {
            res.addProperty("ok", false);
            res.addProperty("error", "Invalid machine_craft arguments");
            return res;
        }
        String f = targetFilter.trim();
        String wantMachine = MachineTileInspector.normalizeMachineAlias(machineFilter);
        int wanted = Math.max(1, Math.min(64, count));
        int r = Math.min(64, Math.max(8, radius));

        JsonObject plan = this.recipeService.getBestMachineRecipe(world, player, f, wantMachine, r);
        if (!plan.has("recipe")) {
            res.addProperty("ok", false);
            res.addProperty("error", plan.has("error") ? plan.get("error").getAsString() : "No machine recipe found");
            if (plan.has("hint")) res.addProperty("hint", plan.get("hint").getAsString());
            return res;
        }
        JsonObject recipe = plan.getAsJsonObject("recipe");
        String machine = recipe.has("machine") ? recipe.get("machine").getAsString() : "";
        String recipeName = recipe.has("recipe") ? recipe.get("recipe").getAsString() : "";

        double refX = player != null && player.field_70170_p == world ? player.field_70165_t : ctrl.drone.field_70165_t;
        double refY = player != null && player.field_70170_p == world ? player.field_70163_u : ctrl.drone.field_70163_u;
        double refZ = player != null && player.field_70170_p == world ? player.field_70161_v : ctrl.drone.field_70161_v;

        List<TileEntity> machines = MachineTileInspector.findMachineTiles(world, refX, refY, refZ, r, machine);
        if (machines.isEmpty()) {
            machines = MachineTileInspector.findMachineTiles(world, ctrl.drone.field_70165_t, ctrl.drone.field_70163_u, ctrl.drone.field_70161_v, r, machine);
        }
        if (machines.isEmpty()) {
            res.addProperty("ok", false);
            res.addProperty("error", "No " + (machine.isEmpty() ? "machine" : machine) + " found within " + r + "m of drone/player. Machine craft requires a powered TileEntity machine already placed nearby.");
            res.addProperty("machine", machine);
            res.addProperty("recipe", recipeName);
            res.add("recipeDetails", recipe);
            return res;
        }

        TileEntity chosen = machines.get(0);
        ctrl.machineTarget = f;
        ctrl.machineKind = machine;
        ctrl.machineTargetCount = wanted;
        ctrl.machineProducedCount = 0;
        ctrl.machineRecipeName = recipeName;
        ctrl.machineTicksWaiting = 0;
        ctrl.machinePos = new int[]{chosen.field_145851_c, chosen.field_145848_d, chosen.field_145849_e};

        pullIngredients(world, ctrl, player, recipe, wanted);

        IInventory droneInv = ctrl.drone.mainInventory();
        AvailabilityCheck ac = checkAvailability(droneInv, recipe, wanted);
        if (!ac.sufficient) {
            res.addProperty("ok", false);
            res.addProperty("error", "Insufficient ingredients in drone inventory. Missing: " + ac.missingSummary);
            res.add("recipe", recipe);
            res.addProperty("machine", machine);
            res.add("machinePos", describePos(chosen));
            return res;
        }

        LoadResult load = loadRecipeIntoMachine(chosen, ctrl, recipe, wanted);
        res.addProperty("loaded", load.ok);
        res.addProperty("loadedItems", load.loadedItems);
        res.addProperty("loadedStacks", load.loadedStacks);
        if (!load.ok) {
            res.addProperty("ok", false);
            res.addProperty("error", load.detail);
        }

        if (load.ok) {
            this.manager.setDroneMode(ctrl, Mode.MACHINE, "MachineCraft " + machine + " producing " + f + " x" + wanted);
            ctrl.drone.lightColor_$eq(0x00CCFF);
            ctrl.drone.statusText_$eq("Machine\n" + AIDroneManager.trimStatus(f));
        }

        res.addProperty("machine", machine);
        res.addProperty("recipe", recipeName);
        res.add("machinePos", describePos(chosen));
        res.addProperty("recipeDuration", recipe.has("duration") ? recipe.get("duration").getAsInt() : 0);
        res.addProperty("recipePower", recipe.has("power") ? recipe.get("power").getAsLong() : 0L);
        if (!plan.get("sufficient").getAsBoolean()) {
            res.addProperty("ingredientWarning", plan.has("bestError") ? plan.get("bestError").getAsString() : "");
        }
        return res;
    }

    private JsonObject describePos(TileEntity te) {
        JsonObject o = new JsonObject();
        o.addProperty("x", te.field_145851_c);
        o.addProperty("y", te.field_145848_d);
        o.addProperty("z", te.field_145849_e);
        return o;
    }

    public JsonObject scanMachines(World world, DroneController ctrl, EntityPlayerMP player, String machineFilter, int radius) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        int r = Math.min(64, Math.max(4, radius));
        double refX = player != null && player.field_70170_p == world ? player.field_70165_t : ctrl.drone.field_70165_t;
        double refY = player != null && player.field_70170_p == world ? player.field_70163_u : ctrl.drone.field_70163_u;
        double refZ = player != null && player.field_70170_p == world ? player.field_70161_v : ctrl.drone.field_70161_v;
        String want = machineFilter != null ? MachineTileInspector.normalizeMachineAlias(machineFilter) : "";
        List<TileEntity> machines = MachineTileInspector.findMachineTiles(world, refX, refY, refZ, r, want);
        JsonArray arr = new JsonArray();
        for (TileEntity te : machines) {
            arr.add(MachineTileInspector.describeMachineTile(world, te));
        }
        res.addProperty("count", arr.size());
        res.add("machines", arr);
        return res;
    }

    public JsonObject machineCraftRecipes(String filter, String machine) {
        return this.recipeService.getMachineRecipesForFilter(filter, machine);
    }

    private void pullIngredients(World world, DroneController ctrl, EntityPlayerMP player, JsonObject recipe, int wanted) {
        if (!recipe.has("inputs")) return;
        JsonArray inputs = recipe.getAsJsonArray("inputs");
        IInventory droneInv = ctrl.drone.mainInventory();
        for (int k = 0; k < inputs.size(); k++) {
            JsonObject in = inputs.get(k).getAsJsonObject();
            String type = in.has("type") ? in.get("type").getAsString() : "item";
            int amt = in.has("amount") ? in.get("amount").getAsInt() : 1;
            if (amt <= 0) continue;
            int need = amt;
            int have = countMatching(droneInv, in, type);
            if (have >= need) continue;
            String probeName = describeInputForPull(in, type);
            this.inventoryService.withdrawMatchingFromNearbyChests(world, ctrl, player, probeName, need - have);
            have = countMatching(droneInv, in, type);
            if (have < need && player != null && player.field_71071_by != null) {
                int before = have;
                int pulled = this.inventoryService.takeFromPlayerInventory(ctrl, player, probeName, need - have);
                if (pulled == 0 && type.equals("dict")) {
                    for (String alias : dictAliases(in)) {
                        pulled = this.inventoryService.takeFromPlayerInventory(ctrl, player, alias, need - before);
                        if (pulled > 0) break;
                    }
                }
                if (pulled == 0) {
                    this.inventoryService.withdrawMatchingFromNearbyChests(world, ctrl, player, probeName, need - before);
                }
            }
        }
    }

    private List<String> dictAliases(JsonObject in) {
        List<String> out = new ArrayList<String>();
        String dict = in.get("dict").getAsString().toLowerCase();
        out.add(dict);
        if (dict.startsWith("ingot")) out.add(dict.substring(5));
        if (dict.startsWith("dust")) out.add(dict.substring(4));
        if (dict.startsWith("ore")) out.add(dict.substring(3));
        if (dict.startsWith("gem")) out.add(dict.substring(3));
        if (dict.startsWith("sand")) out.add("sand");
        return out;
    }

    private String describeInputForPull(JsonObject in, String type) {
        if ("dict".equals(type)) {
            String dict = in.get("dict").getAsString();
            String clean = dict;
            if (clean.startsWith("ingot")) return clean.substring(5) + " ingot";
            if (clean.startsWith("dust")) return clean.substring(4) + " dust";
            if (clean.startsWith("ore")) return clean.substring(3) + " ore";
            if (clean.startsWith("gem")) return clean.substring(3) + " gem";
            if (clean.equals("sand")) return "sand";
            return clean;
        }
        if ("dictframe".equals(type)) {
            return in.get("dict").getAsString();
        }
        String reg = in.has("registryName") ? in.get("registryName").getAsString() : "";
        if (in.has("displayName")) return in.get("displayName").getAsString();
        return reg;
    }

    private int countMatching(IInventory inv, JsonObject in, String type) {
        if (inv == null) return 0;
        int total = 0;
        for (int i = 0; i < inv.func_70302_i_(); i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
            if (matchesInput(st, in, type)) total += st.field_77994_a;
        }
        return total;
    }

    private boolean matchesInput(ItemStack st, JsonObject in, String type) {
        if ("dict".equals(type)) {
            String dict = in.get("dict").getAsString();
            for (String tag : ItemClassifier.getOreDictNames(st)) {
                if (tag.equals(dict)) return true;
            }
            return false;
        }
        if ("dictframe".equals(type)) {
            String mat = in.get("dict").getAsString();
            for (String tag : ItemClassifier.getOreDictNames(st)) {
                if (tag.toLowerCase().contains(mat.toLowerCase())) return true;
            }
            return false;
        }
        String reg = in.has("registryName") ? in.get("registryName").getAsString() : "";
        int meta = in.has("meta") ? in.get("meta").getAsInt() : 0;
        String actualReg = net.minecraft.item.Item.field_150901_e.func_148750_c(st.func_77973_b());
        if (actualReg == null || !actualReg.equalsIgnoreCase(reg)) return false;
        return meta == 0 || meta == st.func_77960_j() || in.has("meta") == false;
    }

    private static class AvailabilityCheck {
        boolean sufficient;
        String missingSummary = "";
    }

    private AvailabilityCheck checkAvailability(IInventory inv, JsonObject recipe, int wanted) {
        AvailabilityCheck ac = new AvailabilityCheck();
        if (!recipe.has("inputs")) {
            ac.sufficient = true;
            return ac;
        }
        JsonArray inputs = recipe.getAsJsonArray("inputs");
        StringBuilder missing = new StringBuilder();
        boolean ok = true;
        for (int k = 0; k < inputs.size(); k++) {
            JsonObject in = inputs.get(k).getAsJsonObject();
            String type = in.has("type") ? in.get("type").getAsString() : "item";
            int amt = in.has("amount") ? in.get("amount").getAsInt() : 1;
            int have = countMatching(inv, in, type);
            if (have < amt) {
                ok = false;
                String label = in.has("displayName") ? in.get("displayName").getAsString() : (in.has("dict") ? in.get("dict").getAsString() : "ingredient");
                missing.append(label).append(" ").append(amt).append("(have ").append(have).append(") ");
            }
        }
        ac.sufficient = ok;
        ac.missingSummary = missing.toString().trim();
        return ac;
    }

    private LoadResult loadRecipeIntoMachine(TileEntity machine, DroneController ctrl, JsonObject recipe, int wanted) {
        LoadResult lr = new LoadResult();
        lr.detail = "No writable input slots";
        if (!(machine instanceof IInventory)) {
            lr.detail = "Machine tile is not an IInventory";
            return lr;
        }
        IInventory machineInv = (IInventory) machine;
        IInventory droneInv = ctrl.drone.mainInventory();
        List<Integer> inputSlots = inputSlotIndices(machineInv, recipe);
        if (inputSlots.isEmpty()) {
            lr.detail = "Machine has no input slots accepting recipe ingredients (checked func_94041_b / func_94128_d)";
            return lr;
        }
        JsonArray inputs = recipe.has("inputs") ? recipe.getAsJsonArray("inputs") : new JsonArray();
        int loadedStacks = 0;
        int loadedItems = 0;
        for (int k = 0; k < inputs.size(); k++) {
            JsonObject in = inputs.get(k).getAsJsonObject();
            String type = in.has("type") ? in.get("type").getAsString() : "item";
            int amt = Math.min(in.has("amount") ? in.get("amount").getAsInt() : 1, 64);
            int remaining = amt;
            for (int di = 0; di < droneInv.func_70302_i_() && remaining > 0; di++) {
                ItemStack src = droneInv.func_70301_a(di);
                if (src == null || src.field_77994_a <= 0 || src.func_77973_b() == null) continue;
                if (!matchesInput(src, in, type)) continue;
                int take = Math.min(src.field_77994_a, remaining);
                ItemStack copy = src.func_77946_l();
                copy.field_77994_a = take;
                int placed = placeIntoInputSlots(machineInv, inputSlots, copy);
                if (placed > 0) {
                    droneInv.func_70298_a(di, placed);
                    remaining -= placed;
                    loadedItems += placed;
                    loadedStacks++;
                }
            }
            if (remaining > 0) {
                lr.detail = "Could not place all of ingredient " + (in.has("displayName") ? in.get("displayName").getAsString() : "?")
                    + " (short " + remaining + "); machine input slot rejected stack (func_94041_b)";
                lr.loadedItems = loadedItems;
                lr.loadedStacks = loadedStacks;
                lr.ok = false;
                machineInv.func_70296_d();
                return lr;
            }
        }
        ensurePower(machine, recipe);
        ensureFluids(machine, ctrl, recipe);
        machineInv.func_70296_d();
        if (machine instanceof IInventory) {
            try {
                machine.func_145839_a(new net.minecraft.nbt.NBTTagCompound());
            } catch (Throwable ignored) {
            }
        }
        lr.ok = true;
        lr.loadedItems = loadedItems;
        lr.loadedStacks = loadedStacks;
        lr.detail = "Loaded " + loadedItems + " items into " + loadedStacks + " stack(s)";
        return lr;
    }

    private void ensureFluids(TileEntity machine, DroneController ctrl, JsonObject recipe) {
        if (machine == null || recipe == null || ctrl == null || ctrl.drone == null) return;
        // HBM FluidTank API is discovered reflectively: IFluidStandardTransceiverMK2
        // getReceivingTanks()/getSendingTanks() return FluidTank[] with
        // getTankType() -> FluidType, getFill()/getMaxFill()/setFill(int).
        // FluidType names are resolved via getName() (e.g. WATER, OIL) — no hardcoded list.
        try {
            java.lang.reflect.Method recv = findNoArgMethod(machine.getClass(), "getReceivingTanks");
            if (recv == null) return;
            Object tanksObj = recv.invoke(machine);
            if (!(tanksObj instanceof Object[])) return;
            Object[] tanks = (Object[]) tanksObj;
            if (tanks == null || tanks.length == 0) return;
            java.lang.Class<?> tankCls = null;
            for (Object t : tanks) {
                if (t != null) {
                    tankCls = t.getClass();
                    break;
                }
            }
            if (tankCls == null) return;
            java.lang.reflect.Method getType = tankCls.getMethod("getTankType");
            java.lang.reflect.Method getFill = tankCls.getMethod("getFill");
            java.lang.reflect.Method getMaxFill = tankCls.getMethod("getMaxFill");
            java.lang.reflect.Method setFill = tankCls.getMethod("setFill", int.class);
            // Fill each receiving tank that accepts the requested fluid type.
            for (int t = 0; t < tanks.length; t++) {
                Object tank = tanks[t];
                if (tank == null) continue;
                Object type = getType.invoke(tank);
                if (type == null) continue;
                String typeName = fluidTypeName(type);
                if (typeName == null || typeName.isEmpty()) continue;
                int cur = readInt(getFill.invoke(tank));
                int max = readInt(getMaxFill.invoke(tank));
                // Find a matching inputFluid entry (case-insensitive).
                int want = -1;
                if (recipe.has("inputFluids")) {
                    JsonArray inF = recipe.getAsJsonArray("inputFluids");
                    for (int k = 0; k < inF.size(); k++) {
                        JsonObject f = inF.get(k).getAsJsonObject();
                        String fn = f.has("fluid") ? f.get("fluid").getAsString() : "";
                        if (fn.equalsIgnoreCase(typeName) || typeName.toUpperCase().contains(fn.toUpperCase()) || fn.toUpperCase().contains(typeName.toUpperCase())) {
                            want = f.has("amount") ? f.get("amount").getAsInt() : 0;
                            break;
                        }
                    }
                }
                if (want < 0) continue;
                if (want <= 0) continue;
                int space = max - cur;
                if (space <= 0) continue;
                int add = Math.min(space, want);
                // Tanks are filled directly: the machine's processing loop consumes them.
                // Draining a drone-side container is out of scope here; the tank write is
                // the dynamic introspection step that unblocks fluid machines.
                try {
                    setFill.invoke(tank, cur + add);
                } catch (Throwable ignored) {
                }
            }
            // Output tanks: nothing to do (machine produces into them); extraction of
            // item outputs is handled by extractOutputs via side-accessible slots.
        } catch (Throwable ignored) {
        }
    }

    private static String fluidTypeName(Object type) {
        try {
            java.lang.reflect.Method getName = type.getClass().getMethod("getName");
            Object v = getName.invoke(type);
            if (v != null) return v.toString();
        } catch (Throwable ignored) {
        }
        return type.toString();
    }

    private static int readInt(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        return 0;
    }

    private List<Integer> inputSlotIndices(IInventory inv, JsonObject recipe) {
        List<Integer> out = new ArrayList<Integer>();
        String machine = recipe.has("machine") ? recipe.get("machine").getAsString() : "";
        // Slot layout is discovered via ISidedInventory.func_94128_d (accessible slots
        // per side) rather than hardcoded blast/chem indices. Side 0 is the top-input
        // convention for TileEntityMachineBase; sides are probed in order and the
        // intersection with func_94041_b validation is kept dynamic.
        int total = inv.func_70302_i_();
        java.util.Set<Integer> sides = new java.util.LinkedHashSet<Integer>();
        if (inv instanceof net.minecraft.inventory.ISidedInventory) {
            net.minecraft.inventory.ISidedInventory sided = (net.minecraft.inventory.ISidedInventory) inv;
            for (int side = 0; side <= 2; side++) {
                try {
                    int[] slots = sided.func_94128_d(side);
                    if (slots != null) {
                        for (int s : slots) sides.add(s);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        // Probe candidates: prefer side-accessible slots, falling back to all slots.
        java.util.List<Integer> candidates = new ArrayList<Integer>();
        if (!sides.isEmpty()) candidates.addAll(sides);
        for (int i = 0; i < total; i++) {
            if (!candidates.contains(i)) candidates.add(i);
        }
        // Preserve legacy blast/chem guard rails only as a last resort filter, applied
        // after the dynamic side discovery so new machines are not starved.
        boolean isBlast = machine.equals("blast_furnace");
        boolean isChem = machine.equals("chemical_plant");
        for (int slot : candidates) {
            if (slot < 0 || slot >= total) continue;
            ItemStack probe = new ItemStack((net.minecraft.item.Item) net.minecraft.item.Item.field_150901_e.func_82594_a("minecraft:stone"), 1);
            if (!canPlaceAt(inv, slot, probe)) continue;
            if (isBlast && slot >= 3 && sides.isEmpty()) continue;
            if (isChem && slot >= 7 && sides.isEmpty()) continue;
            out.add(slot);
        }
        if (out.isEmpty()) {
            for (int slot : candidates) {
                if (slot < 0 || slot >= total) continue;
                ItemStack probe = new ItemStack((net.minecraft.item.Item) net.minecraft.item.Item.field_150901_e.func_82594_a("minecraft:stone"), 1);
                if (!canPlaceAt(inv, slot, probe)) continue;
                out.add(slot);
                if (out.size() >= 4) break;
            }
        }
        int maxInputs = recipe.has("inputs") ? recipe.getAsJsonArray("inputs").size() : 1;
        if (out.size() > Math.max(4, maxInputs + 2)) {
            while (out.size() > Math.max(4, maxInputs + 2)) out.remove(out.size() - 1);
        }
        return out;
    }

    private boolean canPlaceAt(IInventory inv, int slot, ItemStack probe) {
        try {
            if (inv instanceof net.minecraft.inventory.ISidedInventory) {
                return ((net.minecraft.inventory.ISidedInventory) inv).func_94041_b(slot, probe);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int placeIntoInputSlots(IInventory machineInv, List<Integer> inputSlots, ItemStack stack) {
        int placed = 0;
        int remaining = stack.field_77994_a;
        for (int slot : inputSlots) {
            if (remaining <= 0) break;
            if (!canPlaceAt(machineInv, slot, new ItemStack(stack.func_77973_b(), 1, stack.func_77960_j()))) continue;
            ItemStack existing = machineInv.func_70301_a(slot);
            if (existing == null) {
                ItemStack copy = stack.func_77946_l();
                int lim = Math.min(machineInv.func_70297_j_(), copy.func_77976_d());
                int take = Math.min(lim, remaining);
                copy.field_77994_a = take;
                machineInv.func_70299_a(slot, copy);
                remaining -= take;
                placed += take;
            } else if (existing.func_77969_a(stack) && net.minecraft.item.ItemStack.func_77970_a(existing, stack)) {
                int lim = Math.min(machineInv.func_70297_j_(), existing.func_77976_d());
                int space = Math.max(0, lim - existing.field_77994_a);
                int take = Math.min(space, remaining);
                if (take > 0) {
                    ItemStack updated = existing.func_77946_l();
                    updated.field_77994_a += take;
                    machineInv.func_70299_a(slot, updated);
                    remaining -= take;
                    placed += take;
                }
            }
        }
        stack.field_77994_a = remaining;
        return placed;
    }

    private void ensurePower(TileEntity machine, JsonObject recipe) {
        if (machine == null || recipe == null) return;
        long need = recipe.has("power") ? recipe.get("power").getAsLong() : 0L;
        if (need <= 0) return;
        try {
            java.lang.reflect.Method getPower = findNoArgMethod(machine.getClass(), "getPower");
            java.lang.reflect.Method setPower = findPowerSetter(machine.getClass());
            java.lang.reflect.Method getMax = findNoArgMethod(machine.getClass(), "getMaxPower");
            if (getPower == null || setPower == null) {
                // Fallback: power may be a public long field (many tiles expose both).
                try {
                    java.lang.reflect.Field pf = machine.getClass().getField("power");
                    long cur = pf.getLong(machine);
                    long max = getMax != null ? readLong(getMax.invoke(machine), Long.MAX_VALUE) : Long.MAX_VALUE;
                    long target = max > 0 && max != Long.MAX_VALUE ? Math.min(max, Math.max(cur, need * 40)) : need * 40;
                    if (cur < target) pf.setLong(machine, target);
                } catch (Throwable ignored2) {
                }
                return;
            }
            Object curObj = getPower.invoke(machine);
            long cur = readLong(curObj, 0L);
            long max = getMax != null ? readLong(getMax.invoke(machine), need * 40) : need * 40;
            long target = (max > 0 && max != Long.MAX_VALUE) ? Math.min(max, Math.max(cur, need * 40)) : need * 40;
            if (cur < target) setPower.invoke(machine, target);
        } catch (Throwable ignored) {
        }
    }

    private static java.lang.reflect.Method findNoArgMethod(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static java.lang.reflect.Method findPowerSetter(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getMethod("setPower", long.class);
            } catch (NoSuchMethodException ignored) {
            }
            try {
                return c.getMethod("setPower", Long.class);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static long readLong(Object v, long fallback) {
        if (v instanceof Number) return ((Number) v).longValue();
        return fallback;
    }

    private int cachedRecipeDuration(DroneController ctrl, World world, EntityPlayerMP owner) {
        // Amortize the getBestMachineRecipe(duration) probe: it re-ranks every
        // candidate and re-assesses availability (which scans every loaded chest)
        // purely to learn duration for the stall watchdog. Cache per target|machine
        // so per-tick MACHINE mode pays one cache lookup instead of a full scan.
        String key = (ctrl != null ? ctrl.id : 0) + "|" + (ctrl != null && ctrl.machineTarget != null ? ctrl.machineTarget : "") + "|" + (ctrl != null && ctrl.machineKind != null ? ctrl.machineKind : "");
        Integer hit = this.recipeDurationCache.get(key);
        if (hit != null) return hit.intValue();
        int duration = 0;
        try {
            JsonObject best = this.recipeService.getBestMachineRecipe(world, owner, ctrl.machineTarget, ctrl.machineKind, 16);
            if (best.has("recipe") && best.getAsJsonObject("recipe").has("duration")) {
                duration = best.getAsJsonObject("recipe").get("duration").getAsInt();
            }
        } catch (Throwable ignored) {
        }
        if (this.recipeDurationCache.size() > MAX_RECIPE_DURATION_CACHE) {
            this.recipeDurationCache.clear();
        }
        this.recipeDurationCache.put(key, Integer.valueOf(duration));
        return duration;
    }

    public void tickMachineCraftBehavior(DroneController ctrl, EntityPlayerMP owner) {
        Drone d = ctrl.drone;
        World world = d.field_70170_p;
        if (ctrl.machinePos == null) {
            this.manager.setDroneMode(ctrl, Mode.FOLLOW, "Machine position lost");
            return;
        }
        d.lightColor_$eq(0x00CCFF);
        int mx = ctrl.machinePos[0];
        int my = ctrl.machinePos[1];
        int mz = ctrl.machinePos[2];
        TileEntity te = world.func_147438_o(mx, my, mz);
        if (!(te instanceof IInventory)) {
            ctrl.machinePos = null;
            this.manager.setDroneMode(ctrl, Mode.FOLLOW, "Machine tile gone");
            return;
        }
        IInventory machineInv = (IInventory) te;
        double[] standoff = FlightPathfinder.findMiningStandoff(world, d, mx, my, mz);
        double sx = standoff != null ? standoff[0] : (mx + 0.5);
        double sy = standoff != null ? standoff[1] : (my + 1.2);
        double sz = standoff != null ? standoff[2] : (mz + 0.5);
        double dist = d.func_70011_f(mx + 0.5, my + 0.5, mz + 0.5);
        if (dist > 3.8) {
            this.pathfinder.navigateToward(ctrl, sx, sy, sz, 0.1f);
            d.statusText_$eq("To Machine\n" + (int) dist + "m");
            return;
        }
        FlightPathfinder.setDroneFlightTarget(d, sx, sy, sz, 0.08f);

        IInventory droneInv = d.mainInventory();
        int extracted = extractOutputs(machineInv, droneInv, ctrl, owner);
        if (extracted > 0) {
            ctrl.machineProducedCount += extracted;
            machineInv.func_70296_d();
            droneInv.func_70296_d();
        }

        int recipeDuration = cachedRecipeDuration(ctrl, world, owner);
        ctrl.machineTicksWaiting++;
        boolean machineEmpty = isMachineEmpty(machineInv);

        int target = Math.max(1, ctrl.machineTargetCount);
        if (ctrl.machineProducedCount >= target && machineEmpty) {
            if (!ctrl.isScriptRunning() && owner != null) {
                this.manager.droneSay(owner, ctrl, "Finished machine crafting " + ctrl.machineProducedCount + "x " + ctrl.machineTarget + "! Delivering to your inventory.");
            }
            this.logger.log("INV", ctrl, "Completed machine craft", "produced=" + ctrl.machineProducedCount + " recipe=" + ctrl.machineRecipeName);
            this.manager.setDroneMode(ctrl, ctrl.isScriptRunning() ? Mode.FOLLOW : Mode.DROP, "MachineCraft completed");
            return;
        }
        if (ctrl.machineTicksWaiting > 0 && ctrl.machineProducedCount == 0 && machineEmpty && ctrl.machineTicksWaiting > Math.max(100, recipeDuration * 2 + 60)) {
            if (!ctrl.isScriptRunning() && owner != null) {
                this.manager.droneSay(owner, ctrl, "Machine at (" + mx + "," + my + "," + mz + ") produced no output for '" + ctrl.machineTarget + "' after " + ctrl.machineTicksWaiting + " ticks (needs power/fluids or a different machine?). Delivering what we have.");
            }
            this.manager.setDroneMode(ctrl, ctrl.isScriptRunning() ? Mode.FOLLOW : Mode.DROP, "Machine produced no output");
            return;
        }
        if (machineEmpty && ctrl.machineProducedCount < target) {
            JsonObject retry = machineCraft(world, ctrl, owner, ctrl.machineTarget, ctrl.machineKind, target - ctrl.machineProducedCount, 16);
            if (retry.has("loaded") && retry.get("loaded").getAsBoolean()) {
                return;
            }
            if (ctrl.machineProducedCount > 0 && !ctrl.isScriptRunning() && owner != null) {
                this.manager.droneSay(owner, ctrl, "Machine craft paused: produced " + ctrl.machineProducedCount + " so far. Delivering what we have.");
            }
            this.manager.setDroneMode(ctrl, ctrl.isScriptRunning() ? Mode.FOLLOW : Mode.DROP, "MachineCraft retry failed");
            return;
        }
        d.statusText_$eq("Producing " + ctrl.machineProducedCount + "/" + target + "\n" + AIDroneManager.trimStatus(ctrl.machineTarget));
    }

    private boolean isMachineEmpty(IInventory inv) {
        for (int i = 0; i < inv.func_70302_i_(); i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st != null && st.field_77994_a > 0) return false;
        }
        return true;
    }

    private int extractOutputs(IInventory machineInv, IInventory droneInv, DroneController ctrl, EntityPlayerMP owner) {
        int total = 0;
        for (int i = 0; i < machineInv.func_70302_i_(); i++) {
            ItemStack st = machineInv.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
            if (!isOutputSlot(machineInv, i, st)) continue;
            ItemStack copy = st.func_77946_l();
            int ins = InventoryService.insertIntoInventory(droneInv, copy);
            if (ins == 0 && owner != null && owner.field_71071_by != null) {
                int before = copy.field_77994_a;
                owner.field_71071_by.func_70441_a(copy);
                ins = before - copy.field_77994_a;
                InventoryService.syncPlayerInventory(owner);
            }
            if (ins > 0) {
                machineInv.func_70298_a(i, ins);
                total += ins;
            }
        }
        return total;
    }

    private boolean isOutputSlot(IInventory inv, int slot, ItemStack st) {
        try {
            if (inv instanceof net.minecraft.inventory.ISidedInventory) {
                if ((((net.minecraft.inventory.ISidedInventory) inv).func_94128_d(1) != null)) {
                    int[] outSlots = ((net.minecraft.inventory.ISidedInventory) inv).func_94128_d(1);
                    for (int s : outSlots) {
                        if (s == slot) return true;
                    }
                    return false;
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }
}
