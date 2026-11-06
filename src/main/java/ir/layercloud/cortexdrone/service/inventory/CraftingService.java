package ir.layercloud.cortexdrone.service.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import lombok.RequiredArgsConstructor;
import lombok.Value;
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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Dynamic Forge CraftingManager recipe introspection, relevance ranking, and 3-pass crafting engine.
 * Pass 0: Craft directly from droneInv.
 * Pass 1: Pull missing ingredients from nearby chests or player inventory.
 * Pass 2: Recursively sub-craft intermediate ingredients.
 */
@RequiredArgsConstructor
public class CraftingService {
    private static Field shapedItemsField = null;
    private static Field shapelessItemsField = null;

    static {
        try {
            shapedItemsField = ShapedRecipes.class.getDeclaredField("recipeItems");
            shapedItemsField.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            shapelessItemsField = ShapelessRecipes.class.getDeclaredField("recipeItems");
            shapelessItemsField.setAccessible(true);
        } catch (Throwable ignored) {
        }
    }

    @Value
    private static class ScoredRecipe {
        IRecipe recipe;
        int score;
    }

    private final DroneLogger logger;
    private final InventoryService inventoryService;

    @SuppressWarnings("unchecked")
    public static List<List<ItemStack>> extractRecipeIngredients(IRecipe recipe) {
        List<List<ItemStack>> slots = new ArrayList<>();
        if (recipe == null) return slots;
        try {
            if (recipe instanceof ShapedRecipes && shapedItemsField != null) {
                ItemStack[] arr = (ItemStack[]) shapedItemsField.get(recipe);
                if (arr != null) {
                    for (ItemStack st : arr) {
                        if (st != null && st.getItem() != null) {
                            slots.add(Collections.singletonList(st));
                        }
                    }
                }
            } else if (recipe instanceof ShapelessRecipes && shapelessItemsField != null) {
                List<?> list = (List<?>) shapelessItemsField.get(recipe);
                if (list != null) {
                    for (Object o : list) {
                        if (o instanceof ItemStack && ((ItemStack) o).getItem() != null) {
                            slots.add(Collections.singletonList((ItemStack) o));
                        }
                    }
                }
            } else if (recipe instanceof ShapedOreRecipe) {
                Object[] input = ((ShapedOreRecipe) recipe).getInput();
                if (input != null) {
                    for (Object o : input) {
                        if (o instanceof ItemStack && ((ItemStack) o).getItem() != null) {
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
                        if (o instanceof ItemStack && ((ItemStack) o).getItem() != null) {
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
        if (candidate == null || candidate.stackSize <= 0 || candidate.getItem() == null) return false;
        if (option == null || option.getItem() == null) return false;
        if (candidate.getItem() != option.getItem()) return false;
        int optMeta = option.getItemDamage();
        return optMeta == OreDictionary.WILDCARD_VALUE || optMeta == candidate.getItemDamage();
    }

    private static boolean stackMatchesAnyOption(ItemStack candidate, List<ItemStack> options) {
        if (candidate == null || options == null) return false;
        for (ItemStack opt : options) {
            if (stackMatchesRecipeOption(candidate, opt)) return true;
        }
        return false;
    }

    @Value
    private static class RecipeCacheEntry {
        java.util.List<IRecipe> ranked;
        long expiresAtMs;

        RecipeCacheEntry(java.util.List<IRecipe> ranked, long ttlMs) {
            this.ranked = ranked;
            this.expiresAtMs = System.currentTimeMillis() + ttlMs;
        }
    }

    private static final long RECIPE_CACHE_TTL_MS = 30000L;
    private final java.util.Map<String, RecipeCacheEntry> recipeRankCache = new java.util.concurrent.ConcurrentHashMap<>();

    private java.util.List<IRecipe> findRankedMatchingRecipesCached(String filter) {
        if (filter == null || filter.trim().isEmpty()) return java.util.Collections.emptyList();
        String key = filter.trim().toLowerCase();
        RecipeCacheEntry cached = recipeRankCache.get(key);
        if (cached != null && System.currentTimeMillis() < cached.getExpiresAtMs()) {
            return cached.getRanked();
        }
        java.util.List<IRecipe> ranked = findRankedMatchingRecipes(filter);
        // Cap total ranked list — LLM rarely inspects beyond 25 and craftItem only needs the first few.
        java.util.List<IRecipe> capped = ranked.size() > 25 ? ranked.subList(0, 25) : ranked;
        recipeRankCache.put(key, new RecipeCacheEntry(capped, RECIPE_CACHE_TTL_MS));
        if (recipeRankCache.size() > 64) {
            // bound growth under pathological filter churn; ConcurrentHashMap purge is approximate
            java.util.Iterator<String> it = recipeRankCache.keySet().iterator();
            for (int i = 0; i < 16 && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
        return capped;
    }

    private static List<IRecipe> findRankedMatchingRecipes(String filter) {
        List<ScoredRecipe> scored = new ArrayList<>();
        List<?> allRecipes = CraftingManager.getInstance().getRecipeList();
        if (allRecipes != null) {
            for (Object ro : allRecipes) {
                if (!(ro instanceof IRecipe)) continue;
                IRecipe r = (IRecipe) ro;
                ItemStack out = r.getRecipeOutput();
                if (out == null || out.getItem() == null) continue;
                int s = ItemClassifier.scoreBlockOrItemMatch(out.getDisplayName(), ItemClassifier.getItemIdentifier(out), out.getItemDamage(), filter);
                if (s > 0 && !extractRecipeIngredients(r).isEmpty()) {
                    scored.add(new ScoredRecipe(r, s));
                }
            }
        }
        Collections.sort(scored, new Comparator<ScoredRecipe>() {
            @Override
            public int compare(ScoredRecipe a, ScoredRecipe b) {
                return Integer.compare(b.getScore(), a.getScore());
            }
        });
        List<IRecipe> result = new ArrayList<>(scored.size());
        for (ScoredRecipe sr : scored) {
            result.add(sr.getRecipe());
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
            ItemStack out = r.getRecipeOutput();
            List<List<ItemStack>> ingSlots = extractRecipeIngredients(r);
            if (out == null || ingSlots.isEmpty()) continue;
            JsonObject roJson = new JsonObject();
            roJson.addProperty("output", out.getDisplayName());
            roJson.addProperty("outputCount", Math.max(1, out.stackSize));
            JsonArray ingArr = new JsonArray();
            for (List<ItemStack> opts : ingSlots) {
                if (!opts.isEmpty() && opts.get(0) != null) {
                    ItemStack first = opts.get(0);
                    int m = first.getItemDamage() == OreDictionary.WILDCARD_VALUE ? 0 : first.getItemDamage();
                    ItemStack dispSt = new ItemStack(first.getItem(), 1, m);
                    ingArr.add(new JsonPrimitive(dispSt.getDisplayName()));
                }
            }
            roJson.add("ingredients", ingArr);
            recipesArr.add(roJson);
        }
        res.addProperty("count", recipesArr.size());
        res.add("recipes", recipesArr);
        return res;
    }

    public CraftResult craftItem(World world, DroneController ctrl, EntityPlayerMP owner, String targetFilter, int wantedCount) {
        return craftItemInternal(world, ctrl, owner, targetFilter, wantedCount, 0);
    }

    private CraftResult craftItemInternal(World world, DroneController ctrl, EntityPlayerMP owner, String targetFilter, int wantedCount, int depth) {
        if (ctrl == null || ctrl.drone == null || targetFilter == null || targetFilter.trim().isEmpty()) {
            return CraftResult.failure("Invalid craft arguments");
        }
        String f = targetFilter.trim();
        int targetTotal = Math.max(1, Math.min(256, wantedCount));
        IInventory droneInv = ctrl.drone.mainInventory();

        List<IRecipe> matchingRecipes = findRankedMatchingRecipesCached(f);
        if (matchingRecipes.isEmpty()) {
            return CraftResult.failure("No crafting recipe found matching '" + f + "'");
        }

        int totalCrafted = 0;
        String craftedName = "";

        while (totalCrafted < targetTotal) {
            boolean craftedThisPass = false;

            for (int modePass = 0; modePass < 2 && !craftedThisPass; modePass++) {
                // Strict: ingredients come from the drone inventory only. Never
                // pull from nearby chests or the player; the model does that
                // explicitly (transfer withdraw/take, break_block, craft).
                boolean allowSubcraft = (modePass == 1);
                for (IRecipe recipe : matchingRecipes) {
                    List<List<ItemStack>> reqSlots = extractRecipeIngredients(recipe);
                    if (reqSlots.isEmpty()) continue;

                    int[] reservedCounts = new int[droneInv.getSizeInventory()];
                    boolean allAvailable = true;

                    for (List<ItemStack> slotOpts : reqSlots) {
                        int foundSlot = -1;
                        for (int i = 0; i < droneInv.getSizeInventory(); i++) {
                            ItemStack st = droneInv.getStackInSlot(i);
                            if (st != null && (st.stackSize - reservedCounts[i]) > 0 && stackMatchesAnyOption(st, slotOpts)) {
                                foundSlot = i;
                                break;
                            }
                        }
                        if (foundSlot < 0 && allowSubcraft && depth < 1 && !slotOpts.isEmpty()) {
                            ItemStack opt = slotOpts.get(0);
                            if (opt != null && opt.getItem() != null) {
                                int m = opt.getItemDamage() == OreDictionary.WILDCARD_VALUE ? 0 : opt.getItemDamage();
                                String subName = new ItemStack(opt.getItem(), 1, m).getDisplayName();
                                craftItemInternal(world, ctrl, owner, subName, reqSlots.size(), depth + 1);
                                for (int i = 0; i < droneInv.getSizeInventory(); i++) {
                                    ItemStack st = droneInv.getStackInSlot(i);
                                    if (st != null && (st.stackSize - reservedCounts[i]) > 0 && stackMatchesAnyOption(st, slotOpts)) {
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
                                droneInv.decrStackSize(i, reservedCounts[i]);
                            }
                        }
                        ItemStack outTemplate = recipe.getRecipeOutput().copy();
                        int outCount = Math.max(1, outTemplate.stackSize);
                        outTemplate.stackSize = outCount;
                        craftedName = outTemplate.getDisplayName();
                        InventoryService.insertIntoInventory(droneInv, outTemplate);
                        if (outTemplate.stackSize > 0) {
                            for (int i = droneInv.getSizeInventory() - 1; i >= 0; i--) {
                                ItemStack st = droneInv.getStackInSlot(i);
                                if (st != null && !st.isItemEqual(outTemplate)) {
                                    if (owner != null && owner.inventory != null && owner.inventory.addItemStackToInventory(st.copy())) {
                                        droneInv.setInventorySlotContents(i, null);
                                        InventoryService.syncPlayerInventory(owner);
                                        break;
                                    }
                                }
                            }
                            InventoryService.insertIntoInventory(droneInv, outTemplate);
                        }
                        if (outTemplate.stackSize > 0 && owner != null && owner.inventory != null) {
                            owner.inventory.addItemStackToInventory(outTemplate);
                            InventoryService.syncPlayerInventory(owner);
                        }
                        droneInv.markDirty();
                        totalCrafted += outCount;
                        craftedThisPass = true;
                        break;
                    }
                }
            }

            if (!craftedThisPass) break;
        }

        if (totalCrafted <= 0) {
            return CraftResult.failure("Missing ingredients for '" + f
                    + "' in the drone inventory; take or gather them first");
        }
        this.logger.log("INV", ctrl, "Crafted " + totalCrafted + "x " + craftedName, "filter=" + f);
        return CraftResult.success(totalCrafted, craftedName);
    }
}
