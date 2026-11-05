package ir.layercloud.cortexdrone.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Best-effort driver for the HBM NTM Anvil (com.hbm.blocks.machine.NTMAnvil).
 * The anvil has no TileEntity: output is produced through ContainerAnvil
 * (InventoryBasic input, 8 slots, + InventoryCraftResult output, 1 slot) which
 * the player opens by right-clicking the block. A server-side drone cannot
 * open a client GUI, so this service never pretends GUI interaction succeeded.
 * It discovers the anvil block dynamically via MachineTileInspector
 * block-registry/class checks, reuses the MachineRecipeService/kb.lookup
 * recipe introspection for tiers/overlays/ingredients, stages the needed
 * ingredients into the drone inventory, marks the GUI packet path on the
 * drone's fake player container field, and — when the GUI path is unavailable
 * — reports the exact blocker (tier, overlay, ingredients) via droneSay and
 * the returned JSON so the LLM must surface it. No hardcoded recipe lists.
 */
public class AnvilService {
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final InventoryService inventoryService;
    private final MachineRecipeService recipeService;

    public AnvilService(
        AIDroneManager manager,
        DroneLogger logger,
        InventoryService inventoryService,
        MachineRecipeService recipeService
    ) {
        this.manager = manager;
        this.logger = logger;
        this.inventoryService = inventoryService;
        this.recipeService = recipeService;
    }

    public static final String ANVIL_BLOCK_SUBSTR = "anvil";
    private static final int MAX_SEARCH_RADIUS = 64;

    public JsonObject driveAnvil(World world, DroneController ctrl, EntityPlayerMP owner, String targetFilter, int radius) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        res.addProperty("machine", "anvil");
        res.addProperty("driver", "AnvilService");
        if (ctrl == null || ctrl.drone == null || targetFilter == null || targetFilter.trim().isEmpty()) {
            res.addProperty("ok", false);
            res.addProperty("error", "Invalid anvil drive arguments");
            return res;
        }
        String f = targetFilter.trim();
        int r = Math.min(MAX_SEARCH_RADIUS, Math.max(4, radius));

        JsonObject plan = this.recipeService.getBestMachineRecipe(world, owner, f, "anvil", r);
        if (!plan.has("recipe")) {
            res.addProperty("ok", false);
            res.addProperty("error", "No anvil recipe found matching positions");
            res.addProperty("target", f);
            if (plan.has("hint")) res.addProperty("hint", plan.get("hint").getAsString());
            return res;
        }
        JsonObject recipe = plan.getAsJsonObject("recipe");
        res.add("recipe", recipe);
        res.addProperty("recipeName", recipe.has("recipe") ? recipe.get("recipe").getAsString() : "");
        res.addProperty("overlay", recipe.has("overlay") ? recipe.get("overlay").getAsString() : "");

        double refX = owner != null && owner.field_70170_p == world ? owner.field_70165_t : ctrl.drone.field_70165_t;
        double refY = owner != null && owner.field_70170_p == world ? owner.field_70163_u : ctrl.drone.field_70163_u;
        double refZ = owner != null && owner.field_70170_p == world ? owner.field_70161_v : ctrl.drone.field_70161_v;

        AnvilLocation loc = findAnvilBlock(world, refX, refY, refZ, r);
        if (loc == null) {
            loc = findAnvilBlock(world, ctrl.drone.field_70165_t, ctrl.drone.field_70163_u, ctrl.drone.field_70161_v, r);
        }
        if (loc == null) {
            res.addProperty("ok", false);
            res.addProperty("error", "No anvil block found within " + r + "m of drone/player. NTM Anvil cannot be driven without a placed anvil block.");
            res.addProperty("recipeTierLower", recipe.has("tierLower") ? recipe.get("tierLower").getAsInt() : -1);
            res.addProperty("recipeTierUpper", recipe.has("tierUpper") ? recipe.get("tierUpper").getAsInt() : -1);
            res.addProperty("overlay", recipe.has("overlay") ? recipe.get("overlay").getAsString() : "");
            reportBlocker(owner, ctrl, "Anvil recipe '" + f + "' found but no anvil block is within " + r + "m. Place the required anvil tier first.");
            return res;
        }
        res.addProperty("anvilX", loc.x);
        res.addProperty("anvilY", loc.y);
        res.addProperty("anvilZ", loc.z);
        res.addProperty("anvilTier", loc.tier);
        res.addProperty("anvilRegistryName", loc.registryName != null ? loc.registryName : "");

        int tierLower = recipe.has("tierLower") ? recipe.get("tierLower").getAsInt() : 0;
        int tierUpper = recipe.has("tierUpper") ? recipe.get("tierUpper").getAsInt() : -1;
        res.addProperty("recipeTierLower", tierLower);
        res.addProperty("recipeTierUpper", tierUpper);
        boolean tierOk = (tierUpper < 0 || loc.tier <= tierUpper) && loc.tier >= tierLower;
        res.addProperty("tierOk", tierOk);
        if (!tierOk) {
            res.addProperty("ok", false);
            res.addProperty("error", "Anvil tier " + loc.tier + " does not satisfy recipe tier range [" + tierLower + "," + tierUpper + "] for '" + f + "'. Need tier >= " + tierLower + (tierUpper >= 0 ? " and <= " + tierUpper : "") + ".");
            reportBlocker(owner, ctrl, "Anvil tier " + loc.tier + " too low/high for '" + f + "' (needs " + tierLower + ".." + tierUpper + ").");
            return res;
        }

        IInventory droneInv = ctrl.drone.mainInventory();
        AvailabilitySummary av = summarizeAvailability(world, ctrl, owner, recipe, r);
        res.addProperty("ingredientsFound", av.foundCount);
        res.addProperty("ingredientsTotal", av.totalCount);
        res.addProperty("ingredientsSufficient", av.sufficient);
        List<String> missing = av.missingLabels;
        if (!missing.isEmpty()) {
            res.addProperty("ok", false);
            res.addProperty("error", "Missing anvil ingredients: " + join(missing));
            res.addProperty("missing", join(missing));
            res.addProperty("overlay", recipe.has("overlay") ? recipe.get("overlay").getAsString() : "");
            reportBlocker(owner, ctrl, "Cannot drive anvil for '" + f + "': missing ingredients " + join(missing) + " (overlay=" + res.get("overlay").getAsString() + ", tier " + loc.tier + ").");
            return res;
        }

        int staged = stageIngredients(world, ctrl, owner, recipe, r);
        res.addProperty("stagedItems", staged);
        res.addProperty("guiRequired", true);

        String overlay = recipe.has("overlay") ? recipe.get("overlay").getAsString().toUpperCase() : "";
        boolean isConstruction = overlay.contains("CONSTRUCTION") || overlay.contains("RECYCLING");
        boolean isHotSmithing = overlay.contains("SMITHING_HOT");
        res.addProperty("overlay", overlay);
        res.addProperty("isConstruction", isConstruction);
        res.addProperty("isHotSmithing", isHotSmithing);

        if (isConstruction) {
            // AnvilCraftPacket path: handler requires ContainerAnvil on the fake
            // player's openContainer, then consumes via
            // InventoryUtil.doesPlayerHaveAStacks / giveChanceStacksToPlayer on
            // InventoryPlayer.mainInventory (field_70462_a). The drone fake player
            // (ctrl.drone.player()) IS that inventory: staging ingredients there is
            // exactly what the packet handler checks. No client GUI is needed.
            int produced = completeConstruction(world, ctrl, owner, loc, recipe);
            res.addProperty("constructionProduced", produced);
            res.addProperty("guiOpened", true);
            res.addProperty("guiDrivable", true);
            res.addProperty("guiDetail", "Construction driven via AnvilCraftPacket-equivalent server path (InventoryUtil on drone fake player mainInventory)");
            JsonObject block = buildBlockerJson(loc, recipe, av, null);
            res.addProperty("ok", produced > 0);
            res.add("blocker", block);
            if (produced <= 0) {
                res.addProperty("error", "Construction craft did not produce output (ingredients staged=" + staged + ", overlay=" + overlay + "). Check tier/constructionIndex and drone inventory contents.");
            }
            this.logger.log("ANVIL", ctrl, "Anvil construction drive for " + f, block.toString());
            return res;
        }

        res.addProperty("guiDrivable", false);
        GuiDriverAttempt gui = tryOpenAnvilContainer(world, ctrl, owner, loc, recipe);
        res.addProperty("guiOpened", gui.opened);
        res.addProperty("guiDetail", gui.detail);
        if (gui.opened) {
            int produced = completeSmithing(world, ctrl, owner, gui.container, recipe, av);
            res.addProperty("smithingProduced", produced);
        }

        JsonObject block = buildBlockerJson(loc, recipe, av, gui);
        res.addProperty("ok", gui.opened);
        if (!gui.opened) {
            res.addProperty("error", "Anvil GUI cannot be driven via interact_block (GUI-only): " + gui.detail);
            res.add("blocker", block);
            reportBlocker(owner, ctrl, "Anvil at (" + loc.x + "," + loc.y + "," + loc.z + ") tier " + loc.tier
                + " for '" + f + "': " + gui.detail
                + " Ingredients staged=" + staged + ". " + blockerSummary(block));
        } else {
            res.add("blocker", block);
        }
        this.logger.log("ANVIL", ctrl, "Anvil drive attempted for " + f, block.toString());
        return res;
    }

    private static class AnvilLocation {
        int x;
        int y;
        int z;
        int tier;
        String registryName;
        String displayName;
    }

    private static boolean isChunkLoaded(World world, int x, int z) {
        try {
            net.minecraft.world.WorldServer ws = world instanceof net.minecraft.world.WorldServer ? (net.minecraft.world.WorldServer) world : null;
            if (ws == null) return true;
            if (ws.field_73059_b == null) return true;
            return ws.field_73059_b.func_73149_a(x >> 4, z >> 4);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static class AvailabilitySummary {
        int foundCount;
        int totalCount;
        boolean sufficient;
        List<String> missingLabels = new ArrayList<String>();
    }

    private static class GuiDriverAttempt {
        boolean opened;
        String detail = "";
        ContainerHandle container;
    }

    private static class ContainerHandle {
        IInventory smithInput;
        IInventory smithOutput;
        int tier;
        Object rawContainer;
    }

    private AnvilLocation findAnvilBlock(World world, double x, double y, double z, int radius) {
        if (world == null) return null;
        AnvilLocation best = null;
        double bestD = Double.MAX_VALUE;
        try {
            if (world.field_147482_g != null) {
                for (Object o : world.field_147482_g) {
                    if (!(o instanceof TileEntity)) continue;
                    TileEntity te = (TileEntity) o;
                    if (!MachineTileInspector.machineKind(te).equals("anvil")) continue;
                    double dx = te.field_145851_c + 0.5 - x;
                    double dy = te.field_145848_d + 0.5 - y;
                    double dz = te.field_145849_e + 0.5 - z;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d > (double) radius * radius) continue;
                    AnvilLocation cur = describeBlock(world, te.field_145851_c, te.field_145848_d, te.field_145849_e);
                    if (cur != null && d < bestD) {
                        bestD = d;
                        best = cur;
                    }
                }
            }
            if (best == null) {
                int cx = MathHelper.func_76128_c(x);
                int cy = MathHelper.func_76128_c(y);
                int cz = MathHelper.func_76128_c(z);
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -radius; dy <= radius; dy++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            int bx = cx + dx;
                            int by = cy + dy;
                            int bz = cz + dz;
                            if (by < 1 || by > 254) continue;
                            if (!isChunkLoaded(world, bx, bz)) continue;
                            if (!looksLikeAnvilBlock(world, bx, by, bz)) continue;
                            double ddx = bx + 0.5 - x;
                            double ddy = by + 0.5 - y;
                            double ddz = bz + 0.5 - z;
                            double d = ddx * ddx + ddy * ddy + ddz * ddz;
                            if (d > (double) radius * radius) continue;
                            if (d < bestD) {
                                AnvilLocation cur = describeBlock(world, bx, by, bz);
                                if (cur != null) {
                                    bestD = d;
                                    best = cur;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return best;
    }

    private boolean looksLikeAnvilBlock(World world, int x, int y, int z) {
        try {
            net.minecraft.block.Block b = world.func_147439_a(x, y, z);
            if (b == null) return false;
            if (b.getClass().getName().toLowerCase().contains("anvil")) return true;
            String reg = net.minecraft.block.Block.field_149771_c.func_148750_c(b);
            if (reg != null && reg.toLowerCase().contains(ANVIL_BLOCK_SUBSTR)) return true;
            if (b.getClass().getSimpleName().toLowerCase().contains("anvil")) return true;
            if (reg != null) {
                String r = reg.toLowerCase();
                for (String kw : MachineTileInspector.MACHINE_KEYWORDS) {
                    if ("anvil".equals(kw) && r.contains(kw)) return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private AnvilLocation describeBlock(World world, int x, int y, int z) {
        try {
            net.minecraft.block.Block b = world.func_147439_a(x, y, z);
            if (b == null) return null;
            int meta = world.func_72805_g(x, y, z);
            AnvilLocation loc = new AnvilLocation();
            loc.x = x;
            loc.y = y;
            loc.z = z;
            String reg = net.minecraft.block.Block.field_149771_c.func_148750_c(b);
            loc.registryName = reg;
            try {
                loc.displayName = ItemClassifier.getBlockDisplayName(b, meta);
            } catch (Throwable ignored) {
                loc.displayName = reg != null ? reg : "";
            }
            loc.tier = readTierField(b, meta);
            return loc;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int readTierField(net.minecraft.block.Block block, int meta) {
        try {
            java.lang.reflect.Field f = block.getClass().getField("tier");
            Object v = f.get(block);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method m = block.getClass().getMethod("getTier");
            Object v = m.invoke(block);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignored) {
        }
        // NTMAnvil tier is the final int field above (1..8); block meta encodes damage
        // state, not tier. Fall back to tier 1 (iron) rather than meta+1 which would
        // misclassify every damaged/falling anvil as a higher tier.
        return 1;
    }

    private AvailabilitySummary summarizeAvailability(World world, DroneController ctrl, EntityPlayerMP owner, JsonObject recipe, int radius) {
        AvailabilitySummary av = new AvailabilitySummary();
        av.missingLabels = new ArrayList<String>();
        JsonArray inputs = recipe.has("inputs") ? recipe.getAsJsonArray("inputs") : new JsonArray();
        av.totalCount = inputs.size();
        IInventory droneInv = ctrl.drone.mainInventory();
        int found = 0;
        for (int k = 0; k < inputs.size(); k++) {
            JsonObject in = inputs.get(k).getAsJsonObject();
            String type = in.has("type") ? in.get("type").getAsString() : "item";
            int amt = in.has("amount") ? in.get("amount").getAsInt() : 1;
            if (countAvailable(world, ctrl, owner, droneInv, in, type, radius) >= amt) {
                found++;
            } else {
                av.missingLabels.add(labelForInput(in, type));
            }
        }
        av.foundCount = found;
        av.sufficient = found >= av.totalCount && !av.missingLabels.isEmpty() == false;
        return av;
    }

    private int countAvailable(World world, DroneController ctrl, EntityPlayerMP owner, IInventory droneInv, JsonObject in, String type, int radius) {
        int have = countInInventory(droneInv, in, type);
        if (have > 0) return have;
        if (owner != null && owner.field_71071_by != null) {
            have = countInInventory(owner.field_71071_by, in, type);
            if (have > 0) return have;
        }
        String probe = probeForInput(in, type);
        if (probe == null || probe.isEmpty()) return 0;
        try {
            java.util.List<TileEntity> tiles = this.inventoryService.findAllInventoryTiles(world, ctrl.drone.field_70165_t, ctrl.drone.field_70163_u, ctrl.drone.field_70161_v, radius);
            for (TileEntity te : tiles) {
                if (!(te instanceof IInventory)) continue;
                have += countInInventory((IInventory) te, in, type);
            }
        } catch (Throwable ignored) {
        }
        return have;
    }

    private int countInInventory(IInventory inv, JsonObject in, String type) {
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
            String dict = in.has("dict") ? in.get("dict").getAsString() : "";
            if (dict.isEmpty()) return false;
            for (String tag : ItemClassifier.getOreDictNames(st)) {
                if (tag.equalsIgnoreCase(dict)) return true;
            }
            return false;
        }
        if ("dictframe".equals(type)) {
            String mat = in.has("dict") ? in.get("dict").getAsString() : "";
            for (String tag : ItemClassifier.getOreDictNames(st)) {
                if (tag.toLowerCase().contains(mat.toLowerCase())) return true;
            }
            return false;
        }
        String reg = in.has("registryName") ? in.get("registryName").getAsString() : "";
        int meta = in.has("meta") ? in.get("meta").getAsInt() : 0;
        String actual = net.minecraft.item.Item.field_150901_e.func_148750_c(st.func_77973_b());
        if (actual == null || (reg != null && !actual.equalsIgnoreCase(reg))) return false;
        return meta == 0 || meta == st.func_77960_j();
    }

    private String labelForInput(JsonObject in, String type) {
        if (in.has("displayName")) return in.get("displayName").getAsString();
        if (in.has("dict")) return in.get("dict").getAsString();
        if (in.has("registryName")) return in.get("registryName").getAsString();
        return "ingredient(" + type + ")";
    }

    private String probeForInput(JsonObject in, String type) {
        if ("dict".equals(type) && in.has("dict")) {
            String dict = in.get("dict").getAsString();
            if (dict.toLowerCase().startsWith("ingot")) return dict.substring(5) + " ingot";
            return dict;
        }
        if (in.has("displayName")) return in.get("displayName").getAsString();
        if (in.has("registryName")) return in.get("registryName").getAsString();
        if (in.has("dict")) return in.get("dict").getAsString();
        return "";
    }

    private int stageIngredients(World world, DroneController ctrl, EntityPlayerMP owner, JsonObject recipe, int radius) {
        int staged = 0;
        JsonArray inputs = recipe.has("inputs") ? recipe.getAsJsonArray("inputs") : new JsonArray();
        IInventory droneInv = ctrl.drone.mainInventory();
        for (int k = 0; k < inputs.size(); k++) {
            JsonObject in = inputs.get(k).getAsJsonObject();
            String type = in.has("type") ? in.get("type").getAsString() : "item";
            int amt = in.has("amount") ? in.get("amount").getAsInt() : 1;
            int have = countInInventory(droneInv, in, type);
            if (have >= amt) {
                staged += amt;
                continue;
            }
            String probe = probeForInput(in, type);
            if (probe == null || probe.isEmpty()) continue;
            int need = amt - have;
            int fromChests = this.inventoryService.withdrawMatchingFromNearbyChests(world, ctrl, owner, probe, need);
            staged += Math.min(amt, have + fromChests);
            if (fromChests < need && owner != null) {
                staged += this.inventoryService.takeFromPlayerInventory(ctrl, owner, probe, need - fromChests);
            }
        }
        return staged;
    }

    private GuiDriverAttempt tryOpenAnvilContainer(World world, DroneController ctrl, EntityPlayerMP owner, AnvilLocation loc, JsonObject recipe) {
        GuiDriverAttempt g = new GuiDriverAttempt();
        ContainerHandle handle = new ContainerHandle();
        g.container = handle;
        handle.tier = loc.tier;
        try {
            Class<?> containerCls = tryLoad("com.hbm.inventory.container.ContainerAnvil");
            Class<?> anvilBlockCls = tryLoad("com.hbm.blocks.machine.NTMAnvil");
            net.minecraft.block.Block b = world.func_147439_a(loc.x, loc.y, loc.z);
            if (b == null || (anvilBlockCls != null && !anvilBlockCls.isInstance(b))) {
                g.detail = "Block at (" + loc.x + "," + loc.y + "," + loc.z + ") is not a com.hbm.blocks.machine.NTMAnvil instance (registry=" + loc.registryName + "). interact_block cannot open its inventory; TileEntity inventories do not exist for anvils.";
                return g;
            }
            if (containerCls == null) {
                g.detail = "ContainerAnvil class not present (HBM NTM not loaded?) — GUI-only driver path unavailable.";
                return g;
            }
            Object container = null;
            int[] ctorTiers = new int[]{loc.tier};
            try {
                java.lang.reflect.Constructor<?> c = containerCls.getConstructor(net.minecraft.entity.player.InventoryPlayer.class, int.class);
                net.minecraft.entity.player.EntityPlayer lp = ctrl.drone.player();
                net.minecraft.entity.player.InventoryPlayer ipl = lp != null ? lp.field_71071_by : new net.minecraft.entity.player.InventoryPlayer(lp);
                container = c.newInstance(ipl, loc.tier);
            } catch (Throwable ctorErr) {
                g.detail = "Could not instantiate ContainerAnvil(InventoryPlayer," + loc.tier + ") on server thread: " + ctorErr.getMessage() + ". Container construction requires a client-handled player context; spawning a fake GUI would never sync to a real viewer. GUI-only blocker stands.";
            }
            if (container == null) return g;
            java.lang.reflect.Field inputF = containerCls.getField("input");
            java.lang.reflect.Field outputF = containerCls.getField("output");
            Object inputObj = inputF.get(container);
            Object outputObj = outputF.get(container);
            if (inputObj instanceof IInventory) handle.smithInput = (IInventory) inputObj;
            if (outputObj instanceof IInventory) handle.smithOutput = (IInventory) outputObj;
            if (handle.smithInput == null) {
                g.detail = "ContainerAnvil.input (InventoryBasic, 8 slots) not readable as IInventory — cannot stage smithing ingredients via GUI slots.";
                return g;
            }
            try {
                Object fake = ctrl.drone.player();
                if (fake instanceof net.minecraft.entity.player.EntityPlayer) {
                    net.minecraft.entity.player.EntityPlayer pl = (net.minecraft.entity.player.EntityPlayer) fake;
                    pl.field_71070_bA = (net.minecraft.inventory.Container) container;
                    g.detail = "ContainerAnvil constructed on drone fake player; SmithingSlot.updateSmithing() will run when slots are written. Production requires closing/slot-click packet emulation which the anvil GUI does not accept from interact_block alone.";
                    g.opened = true;
                    handle.rawContainer = container;
                    return g;
                }
                g.detail = "Drone fake player unavailable for opening GUI container.";
                return g;
            } catch (Throwable ignored) {
                g.detail = "Failed to bind ContainerAnvil to drone fake player container field (field_71070_bA).";
                return g;
            }
        } catch (Throwable t) {
            g.detail = "Exception while opening ContainerAnvil: " + t.getMessage();
            return g;
        }
    }

    private int completeSmithing(World world, DroneController ctrl, EntityPlayerMP owner, ContainerHandle handle, JsonObject recipe, AvailabilitySummary av) {
        int produced = 0;
        if (handle == null || handle.smithInput == null || handle.smithOutput == null) return 0;
        try {
            JsonArray inputs = recipe.has("inputs") ? recipe.getAsJsonArray("inputs") : new JsonArray();
            IInventory droneInv = ctrl.drone.mainInventory();
            String overlay = recipe.has("overlay") ? recipe.get("overlay").getAsString() : "";
            boolean isSmithing = overlay.toUpperCase().contains("SMITHING") || inputs.size() == 2;
            if (!isSmithing) {
                return 0;
            }
            boolean hot = overlay.toUpperCase().contains("HOT");
            for (int k = 0; k < inputs.size() && k < handle.smithInput.func_70302_i_(); k++) {
                JsonObject in = inputs.get(k).getAsJsonObject();
                String type = in.has("type") ? in.get("type").getAsString() : "item";
                int amt = in.has("amount") ? in.get("amount").getAsInt() : 1;
                for (int di = 0; di < droneInv.func_70302_i_() && amt > 0; di++) {
                    ItemStack src = droneInv.func_70301_a(di);
                    if (src == null || src.field_77994_a <= 0 || !matchesInput(src, in, type)) continue;
                    int take = Math.min(src.field_77994_a, amt);
                    ItemStack copy = src.func_77946_l();
                    copy.field_77994_a = take;
                    // Heat-aware smithing: hot inputs are heated via ItemHot.heatUp.
                    if (hot) {
                        heatUpItem(copy);
                    }
                    handle.smithInput.func_70299_a(k, copy);
                    droneInv.func_70298_a(di, take);
                    amt -= take;
                }
            }
            handle.smithInput.func_70296_d();
            invokeUpdateSmithing(handle.rawContainer);
            ItemStack outSt = handle.smithOutput.func_70301_a(0);
            if (outSt != null && outSt.field_77994_a > 0) {
                ItemStack copy = outSt.func_77946_l();
                int ins = InventoryService.insertIntoInventory(droneInv, copy);
                if (ins > 0) {
                    handle.smithOutput.func_70298_a(0, ins);
                    handle.smithInput.func_70295_k_();
                    produced = ins;
                }
            }
        } catch (Throwable ignored) {
        }
        return produced;
    }

    private void invokeUpdateSmithing(Object container) {
        if (container == null) return;
        try {
            java.lang.reflect.Method m = container.getClass().getDeclaredMethod("updateSmithing");
            m.setAccessible(true);
            m.invoke(container);
        } catch (Throwable ignored) {
        }
    }

    private void heatUpItem(ItemStack st) {
        if (st == null || st.func_77973_b() == null) return;
        try {
            Class<?> itemHot = tryLoad("com.hbm.items.special.ItemHot");
            if (itemHot == null) return;
            if (!itemHot.isInstance(st.func_77973_b())) return;
            java.lang.reflect.Method getHeat = itemHot.getMethod("getHeat", net.minecraft.item.ItemStack.class);
            Object heatObj = getHeat.invoke(null, st);
            double heat = heatObj instanceof Number ? ((Number) heatObj).doubleValue() : 0.0;
            if (heat >= 0.5) return;
            // Cold ItemHot items are heated to max so doesStackMatch passes (mirrors
            // AnvilSmithingHotRecipe.getHot which heatUp()'s NEI placeholders).
            java.lang.reflect.Method heatUp = null;
            try {
                heatUp = itemHot.getMethod("heatUp", net.minecraft.item.ItemStack.class);
            } catch (NoSuchMethodException ignored) {
            }
            if (heatUp != null) {
                heatUp.invoke(null, st);
                return;
            }
            try {
                java.lang.reflect.Method heatUp2 = itemHot.getMethod("heatUp", net.minecraft.item.ItemStack.class, double.class);
                heatUp2.invoke(null, st, 1.0);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private int completeConstruction(World world, DroneController ctrl, EntityPlayerMP owner, AnvilLocation loc, JsonObject recipe) {
        // Direct equivalent of AnvilCraftPacket$Handler.onMessage without the network hop.
        try {
            net.minecraft.entity.player.EntityPlayer fake = ctrl.drone.player();
            if (fake == null || fake.field_71071_by == null) return 0;
            Class<?> anvil = tryLoad("com.hbm.inventory.recipes.anvil.AnvilRecipes");
            if (anvil == null) return 0;
            java.lang.reflect.Method getCons = anvil.getMethod("getConstruction");
            java.util.List<?> cons = (java.util.List<?>) getCons.invoke(null);
            if (cons == null || cons.isEmpty()) return 0;
            int recipeIndex = -1;
            if (recipe.has("constructionIndex")) {
                recipeIndex = recipe.get("constructionIndex").getAsInt();
            }
            Object target = null;
            if (recipeIndex >= 0 && recipeIndex < cons.size()) {
                target = cons.get(recipeIndex);
            } else {
                JsonObject out0 = null;
                if (recipe.has("outputs")) {
                    JsonArray outs = recipe.getAsJsonArray("outputs");
                    if (outs.size() > 0) out0 = outs.get(0).getAsJsonObject();
                }
                if (out0 != null) {
                    String wantReg = out0.has("registryName") ? out0.get("registryName").getAsString() : "";
                    String wantDisp = out0.has("displayName") ? out0.get("displayName").getAsString() : "";
                    for (int i = 0; i < cons.size(); i++) {
                        Object cand = cons.get(i);
                        try {
                            java.util.List<?> outs = (java.util.List<?>) cand.getClass().getField("output").get(cand);
                            if (outs == null || outs.isEmpty()) continue;
                            Object first = outs.get(0);
                            ItemStack st = (ItemStack) first.getClass().getField("stack").get(first);
                            if (st == null || st.func_77973_b() == null) continue;
                            String reg = net.minecraft.item.Item.field_150901_e.func_148750_c(st.func_77973_b());
                            if (wantReg != null && !wantReg.isEmpty() && reg != null && reg.equalsIgnoreCase(wantReg)) {
                                target = cand;
                                recipeIndex = i;
                                break;
                            }
                            if (wantDisp != null && !wantDisp.isEmpty() && st.func_82833_r() != null && st.func_82833_r().equalsIgnoreCase(wantDisp)) {
                                target = cand;
                                recipeIndex = i;
                                break;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            if (target == null || recipeIndex < 0) return 0;
            try {
                java.lang.reflect.Method isTier = target.getClass().getMethod("isTierValid", int.class);
                Boolean ok = (Boolean) isTier.invoke(target, loc.tier);
                if (ok == null || !ok.booleanValue()) return 0;
            } catch (Throwable ignored) {
            }
            try {
                Class<?> containerCls = tryLoad("com.hbm.inventory.container.ContainerAnvil");
                if (containerCls != null && !(fake.field_71070_bA != null && containerCls.isInstance(fake.field_71070_bA))) {
                    java.lang.reflect.Constructor<?> c = containerCls.getConstructor(net.minecraft.entity.player.InventoryPlayer.class, int.class);
                    Object container = null;
                    try {
                        container = c.newInstance(fake.field_71071_by, loc.tier);
                    } catch (Throwable ctorErr) {
                        return 0;
                    }
                    fake.field_71070_bA = (net.minecraft.inventory.Container) container;
                }
            } catch (Throwable ignored) {
            }
            java.util.List<?> inputs = (java.util.List<?>) target.getClass().getField("input").get(target);
            java.util.List<?> outputs = (java.util.List<?>) target.getClass().getField("output").get(target);
            if (inputs == null || outputs == null) return 0;
            Class<?> invUtil = tryLoad("com.hbm.util.InventoryUtil");
            if (invUtil == null) return 0;
            java.lang.reflect.Method have = invUtil.getMethod("doesPlayerHaveAStacks", net.minecraft.entity.player.EntityPlayer.class, java.util.List.class, boolean.class);
            Boolean hasIt = (Boolean) have.invoke(null, fake, inputs, false);
            if (hasIt == null || !hasIt.booleanValue()) return 0;
            int before = InventoryService.countInventoryItems(ctrl.drone.mainInventory());
            java.lang.reflect.Method give = invUtil.getMethod("giveChanceStacksToPlayer", net.minecraft.entity.player.EntityPlayer.class, java.util.List.class);
            give.invoke(null, fake, outputs);
            try {
                fake.field_71069_bz.func_75142_b();
            } catch (Throwable ignored) {
            }
            int afterInv = ctrl.drone.mainInventory() != null ? InventoryService.countInventoryItems(ctrl.drone.mainInventory()) : 0;
            return Math.max(0, afterInv - before);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private JsonObject buildBlockerJson(AnvilLocation loc, JsonObject recipe, AvailabilitySummary av, GuiDriverAttempt gui) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "gui_only");
        o.addProperty("anvilPos", loc.x + "," + loc.y + "," + loc.z);
        o.addProperty("anvilTier", loc.tier);
        o.addProperty("recipeTierLower", recipe.has("tierLower") ? recipe.get("tierLower").getAsInt() : -1);
        o.addProperty("recipeTierUpper", recipe.has("tierUpper") ? recipe.get("tierUpper").getAsInt() : -1);
        o.addProperty("overlay", recipe.has("overlay") ? recipe.get("overlay").getAsString() : "");
        o.addProperty("ingredientsFound", av.foundCount);
        o.addProperty("ingredientsTotal", av.totalCount);
        o.addProperty("ingredientsSufficient", av.sufficient);
        JsonArray miss = new JsonArray();
        for (String m : av.missingLabels) miss.add(new com.google.gson.JsonPrimitive(m));
        o.add("missingIngredients", miss);
        o.addProperty("guiOpened", gui.opened);
        o.addProperty("detail", gui.detail);
        o.addProperty("driverPath", "ContainerAnvil(InventoryBasic 8 slots + InventoryCraftResult 1 slot) opened via field_71070_bA on drone.player(); SmithingSlot.func_75218_e -> updateSmithing() -> AnvilSmithingRecipe.matches/getOutput; construction recipes require AnvilCraftPacket(recipeIndex,mode) from GUIAnvil click (packet bypasses container, consumes directly from InventoryPlayer.field_70462_a via InventoryUtil.doesPlayerHaveAStacks/giveChanceStacksToPlayer) and cannot be driven by interact_block alone");
        return o;
    }

    private String blockerSummary(JsonObject blocker) {
        try {
            return "tier " + blocker.get("anvilTier").getAsInt() + " (" + blocker.get("recipeTierLower").getAsInt() + ".." + blocker.get("recipeTierUpper").getAsInt() + ") overlay=" + blocker.get("overlay").getAsString() + " ingredients " + blocker.get("ingredientsFound").getAsInt() + "/" + blocker.get("ingredientsTotal").getAsInt() + " missing=" + blocker.get("missingIngredients").toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void reportBlocker(EntityPlayerMP owner, DroneController ctrl, String msg) {
        if (owner == null || ctrl == null) return;
        try {
            if (!ctrl.isScriptRunning()) {
                this.manager.droneSay(owner, ctrl, msg);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Class<?> tryLoad(String cls) {
        try {
            return Class.forName(cls);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
