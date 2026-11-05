package ir.layercloud.cortexdrone.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

/**
 * Dynamic Forge CraftingManager recipe introspection, relevance ranking, and 3-pass crafting engine.
 * Pass 0: Craft directly from droneInv.
 * Pass 1: Pull missing ingredients from nearby chests or player inventory.
 * Pass 2: Recursively sub-craft intermediate ingredients.
 */
public class CraftingService {
    private static Field shapedItemsField = null;
    private static Field shapelessItemsField = null;

    static {
        try {
            shapedItemsField = ShapedRecipes.class.getDeclaredField("field_77574_d");
            shapedItemsField.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            shapelessItemsField = ShapelessRecipes.class.getDeclaredField("field_77579_b");
            shapelessItemsField.setAccessible(true);
        } catch (Throwable ignored) {
        }
    }

    private static class ScoredRecipe {
        final IRecipe recipe;
        final int score;

        ScoredRecipe(IRecipe recipe, int score) {
            this.recipe = recipe;
            this.score = score;
        }
    }

    private final DroneLogger logger;
    private final InventoryService inventoryService;

    public CraftingService(DroneLogger logger, InventoryService inventoryService) {
        this.logger = logger;
        this.inventoryService = inventoryService;
    }

    @SuppressWarnings("unchecked")
    public static List<List<ItemStack>> extractRecipeIngredients(IRecipe recipe) {
        List<List<ItemStack>> slots = new ArrayList<List<ItemStack>>();
        if (recipe == null) return slots;
        try {
            if (recipe instanceof ShapedRecipes && shapedItemsField != null) {
                ItemStack[] arr = (ItemStack[]) shapedItemsField.get(recipe);
                if (arr != null) {
                    for (ItemStack st : arr) {
                        if (st != null && st.func_77973_b() != null) {
                            slots.add(Collections.singletonList(st));
                        }
                    }
                }
            } else if (recipe instanceof ShapelessRecipes && shapelessItemsField != null) {
                List<?> list = (List<?>) shapelessItemsField.get(recipe);
                if (list != null) {
                    for (Object o : list) {
                        if (o instanceof ItemStack && ((ItemStack) o).func_77973_b() != null) {
                            slots.add(Collections.singletonList((ItemStack) o));
                        }
                    }
                }
            } else if (recipe instanceof ShapedOreRecipe) {
                Object[] input = ((ShapedOreRecipe) recipe).getInput();
                if (input != null) {
                    for (Object o : input) {
                        if (o instanceof ItemStack && ((ItemStack) o).func_77973_b() != null) {
                            slots.add(Collections.singletonList((ItemStack) o));
                        } else if (o instanceof List && !((List<?>) o).isEmpty()) {
                            slots.add((List<ItemStack>) o);
                        }
                    }
                }
            } else if (recipe instanceof ShapelessOreRecipe) {
                List<?> input = ((ShapelessOreRecipe) recipe).getInput();
                if (input != null) {
                    for (Object o : input) {
                        if (o instanceof ItemStack && ((ItemStack) o).func_77973_b() != null) {
                            slots.add(Collections.singletonList((ItemStack) o));
                        } else if (o instanceof List && !((List<?>) o).isEmpty()) {
                            slots.add((List<ItemStack>) o);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return slots;
    }

    private static boolean stackMatchesRecipeOption(ItemStack candidate, ItemStack option) {
        if (candidate == null || candidate.field_77994_a <= 0 || candidate.func_77973_b() == null) return false;
        if (option == null || option.func_77973_b() == null) return false;
        if (candidate.func_77973_b() != option.func_77973_b()) return false;
        int optMeta = option.func_77960_j();
        return optMeta == OreDictionary.WILDCARD_VALUE || optMeta == candidate.func_77960_j();
    }

    private static boolean stackMatchesAnyOption(ItemStack candidate, List<ItemStack> options) {
        if (candidate == null || options == null) return false;
        for (ItemStack opt : options) {
            if (stackMatchesRecipeOption(candidate, opt)) return true;
        }
        return false;
    }

    private static class RecipeCacheEntry {
        final java.util.List<IRecipe> ranked;
        final long expiresAtMs;
        RecipeCacheEntry(java.util.List<IRecipe> ranked, long ttlMs) {
            this.ranked = ranked;
            this.expiresAtMs = System.currentTimeMillis() + ttlMs;
        }
    }

    private static final long RECIPE_CACHE_TTL_MS = 30000L;
    private final java.util.Map<String, RecipeCacheEntry> recipeRankCache = new java.util.concurrent.ConcurrentHashMap<String, RecipeCacheEntry>();

    private java.util.List<IRecipe> findRankedMatchingRecipesCached(String filter) {
        if (filter == null || filter.trim().isEmpty()) return java.util.Collections.emptyList();
        String key = filter.trim().toLowerCase();
        RecipeCacheEntry cached = recipeRankCache.get(key);
        if (cached != null && System.currentTimeMillis() < cached.expiresAtMs) {
            return cached.ranked;
        }
        java.util.List<IRecipe> ranked = findRankedMatchingRecipes(filter);
        // Cap total ranked list — LLM rarely inspects beyond 25 and craftItem only needs the first few.
        java.util.List<IRecipe> capped = ranked.size() > 25 ? ranked.subList(0, 25) : ranked;
        recipeRankCache.put(key, new RecipeCacheEntry(capped, RECIPE_CACHE_TTL_MS));
        if (recipeRankCache.size() > 64) {
            // bound growth under pathological filter churn; ConcurrentHashMap purge is approximate
            java.util.Iterator<String> it = recipeRankCache.keySet().iterator();
            for (int i = 0; i < 16 && it.hasNext(); i++) {
                it.next(); it.remove();
            }
        }
        return capped;
    }

    private static List<IRecipe> findRankedMatchingRecipes(String filter) {
        List<ScoredRecipe> scored = new ArrayList<ScoredRecipe>();
        List<?> allRecipes = CraftingManager.func_77594_a().func_77592_b();
        if (allRecipes != null) {
            for (Object ro : allRecipes) {
                if (!(ro instanceof IRecipe)) continue;
                IRecipe r = (IRecipe) ro;
                ItemStack out = r.func_77571_b();
                if (out == null || out.func_77973_b() == null) continue;
                int s = ItemClassifier.scoreBlockOrItemMatch(out.func_82833_r(), ItemClassifier.getItemIdentifier(out), out.func_77960_j(), filter);
                if (s > 0 && !extractRecipeIngredients(r).isEmpty()) {
                    scored.add(new ScoredRecipe(r, s));
                }
            }
        }
        Collections.sort(scored, new Comparator<ScoredRecipe>() {
            @Override
            public int compare(ScoredRecipe a, ScoredRecipe b) {
                return Integer.compare(b.score, a.score);
            }
        });
        List<IRecipe> result = new ArrayList<IRecipe>(scored.size());
        for (ScoredRecipe sr : scored) {
            result.add(sr.recipe);
        }
        return result;
    }

    public JsonObject getRecipesForFilter(String filter) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        String f = (filter == null || filter.trim().isEmpty()) ? "chest" : filter.trim();
        List<IRecipe> ranked = findRankedMatchingRecipesCached(f);
        JsonArray recipesArr = new JsonArray();
        for (IRecipe r : ranked) {
            if (recipesArr.size() >= 25) break;
            ItemStack out = r.func_77571_b();
            List<List<ItemStack>> ingSlots = extractRecipeIngredients(r);
            if (out == null || ingSlots.isEmpty()) continue;
            JsonObject roJson = new JsonObject();
            roJson.addProperty("output", out.func_82833_r());
            roJson.addProperty("outputCount", Math.max(1, out.field_77994_a));
            JsonArray ingArr = new JsonArray();
            for (List<ItemStack> opts : ingSlots) {
                if (!opts.isEmpty() && opts.get(0) != null) {
                    ItemStack first = opts.get(0);
                    int m = first.func_77960_j() == OreDictionary.WILDCARD_VALUE ? 0 : first.func_77960_j();
                    ItemStack dispSt = new ItemStack(first.func_77973_b(), 1, m);
                    ingArr.add(new JsonPrimitive(dispSt.func_82833_r()));
                }
            }
            roJson.add("ingredients", ingArr);
            recipesArr.add(roJson);
        }
        res.addProperty("count", recipesArr.size());
        res.add("recipes", recipesArr);
        return res;
    }

    public JsonObject craftItem(World world, DroneController ctrl, EntityPlayerMP owner, String targetFilter, int wantedCount) {
        return craftItemInternal(world, ctrl, owner, targetFilter, wantedCount, 0);
    }

    private JsonObject craftItemInternal(World world, DroneController ctrl, EntityPlayerMP owner, String targetFilter, int wantedCount, int depth) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        if (ctrl == null || ctrl.drone == null || targetFilter == null || targetFilter.trim().isEmpty()) {
            res.addProperty("ok", false);
            res.addProperty("error", "Invalid craft arguments");
            return res;
        }
        String f = targetFilter.trim();
        int targetTotal = Math.max(1, Math.min(256, wantedCount));
        IInventory droneInv = ctrl.drone.mainInventory();

        List<IRecipe> matchingRecipes = findRankedMatchingRecipesCached(f);
        if (matchingRecipes.isEmpty()) {
            res.addProperty("ok", false);
            res.addProperty("crafted", 0);
            res.addProperty("error", "No crafting recipe found matching '" + f + "'");
            return res;
        }

        int totalCrafted = 0;
        String craftedName = "";

        while (totalCrafted < targetTotal) {
            boolean craftedThisPass = false;

            for (int modePass = 0; modePass < 3 && !craftedThisPass; modePass++) {
                boolean allowExternalPull = (modePass >= 1);
                boolean allowSubcraft = (modePass == 2);
                for (IRecipe recipe : matchingRecipes) {
                    List<List<ItemStack>> reqSlots = extractRecipeIngredients(recipe);
                    if (reqSlots.isEmpty()) continue;

                    int[] reservedCounts = new int[droneInv.func_70302_i_()];
                    boolean allAvailable = true;

                    for (List<ItemStack> slotOpts : reqSlots) {
                        int foundSlot = -1;
                        for (int i = 0; i < droneInv.func_70302_i_(); i++) {
                            ItemStack st = droneInv.func_70301_a(i);
                            if (st != null && (st.field_77994_a - reservedCounts[i]) > 0 && stackMatchesAnyOption(st, slotOpts)) {
                                foundSlot = i;
                                break;
                            }
                        }
                        if (foundSlot < 0 && allowExternalPull && !slotOpts.isEmpty()) {
                            for (ItemStack opt : slotOpts) {
                                if (opt == null || opt.func_77973_b() == null) continue;
                                int m = opt.func_77960_j() == OreDictionary.WILDCARD_VALUE ? 0 : opt.func_77960_j();
                                ItemStack probe = new ItemStack(opt.func_77973_b(), 1, m);
                                String optName = probe.func_82833_r();
                                int pulled = this.inventoryService.withdrawMatchingFromNearbyChests(world, ctrl, owner, optName, 8);
                                if (pulled == 0 && owner != null) {
                                    pulled = this.inventoryService.takeFromPlayerInventory(ctrl, owner, optName, 8);
                                }
                                if (pulled > 0) break;
                            }
                            for (int i = 0; i < droneInv.func_70302_i_(); i++) {
                                ItemStack st = droneInv.func_70301_a(i);
                                if (st != null && (st.field_77994_a - reservedCounts[i]) > 0 && stackMatchesAnyOption(st, slotOpts)) {
                                    foundSlot = i;
                                    break;
                                }
                            }
                        }
                        if (foundSlot < 0 && allowSubcraft && depth < 1 && !slotOpts.isEmpty()) {
                            ItemStack opt = slotOpts.get(0);
                            if (opt != null && opt.func_77973_b() != null) {
                                int m = opt.func_77960_j() == OreDictionary.WILDCARD_VALUE ? 0 : opt.func_77960_j();
                                String subName = new ItemStack(opt.func_77973_b(), 1, m).func_82833_r();
                                craftItemInternal(world, ctrl, owner, subName, reqSlots.size(), depth + 1);
                                for (int i = 0; i < droneInv.func_70302_i_(); i++) {
                                    ItemStack st = droneInv.func_70301_a(i);
                                    if (st != null && (st.field_77994_a - reservedCounts[i]) > 0 && stackMatchesAnyOption(st, slotOpts)) {
                                        foundSlot = i;
                                        break;
                                    }
                                }
                            }
                        }
                        if (foundSlot >= 0) {
                            reservedCounts[foundSlot]++;
                        } else {
                            allAvailable = false;
                            break;
                        }
                    }

                    if (allAvailable) {
                        for (int i = 0; i < reservedCounts.length; i++) {
                            if (reservedCounts[i] > 0) {
                                droneInv.func_70298_a(i, reservedCounts[i]);
                            }
                        }
                        ItemStack outTemplate = recipe.func_77571_b().func_77946_l();
                        int outCount = Math.max(1, outTemplate.field_77994_a);
                        outTemplate.field_77994_a = outCount;
                        craftedName = outTemplate.func_82833_r();
                        InventoryService.insertIntoInventory(droneInv, outTemplate);
                        if (outTemplate.field_77994_a > 0) {
                            for (int i = droneInv.func_70302_i_() - 1; i >= 0; i--) {
                                ItemStack st = droneInv.func_70301_a(i);
                                if (st != null && !st.func_77969_a(outTemplate)) {
                                    if (owner != null && owner.field_71071_by != null && owner.field_71071_by.func_70441_a(st.func_77946_l())) {
                                        droneInv.func_70299_a(i, null);
                                        InventoryService.syncPlayerInventory(owner);
                                        break;
                                    }
                                }
                            }
                            InventoryService.insertIntoInventory(droneInv, outTemplate);
                        }
                        if (outTemplate.field_77994_a > 0 && owner != null && owner.field_71071_by != null) {
                            owner.field_71071_by.func_70441_a(outTemplate);
                            InventoryService.syncPlayerInventory(owner);
                        }
                        droneInv.func_70296_d();
                        totalCrafted += outCount;
                        craftedThisPass = true;
                        break;
                    }
                }
            }

            if (!craftedThisPass) break;
        }

        if (totalCrafted > 0) {
            this.logger.log("INV", ctrl, "Crafted " + totalCrafted + "x " + craftedName, "filter=" + f);
        }
        res.addProperty("crafted", totalCrafted);
        res.addProperty("item", craftedName);
        return res;
    }
}
