package ir.layercloud.cortexdrone.service.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only Forge {@code CraftingManager} introspection: recipe discovery,
 * ingredient extraction and relevance ranking.
 *
 * <p>Crafting itself (consuming ingredients and inserting the output) is the
 * {@code CraftAction} job. This service never mutates an inventory.</p>
 */
@RequiredArgsConstructor
public class CraftingService {
    private static final long RECIPE_CACHE_TTL_MS = 30000L;
    private static final int MAX_RANKED_RECIPES = 25;

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

    @Value
    private static class RecipeCacheEntry {
        List<IRecipe> ranked;
        long expiresAtMs;

        RecipeCacheEntry(List<IRecipe> ranked) {
            this.ranked = ranked;
            this.expiresAtMs = System.currentTimeMillis() + RECIPE_CACHE_TTL_MS;
        }
    }

    private final Map<String, RecipeCacheEntry> recipeRankCache = new ConcurrentHashMap<>();

    /**
     * Ranked recipes matching {@code filter}, capped and cached for 30s.
     */
    public List<IRecipe> rankedRecipes(String filter) {
        if (filter == null || filter.trim().isEmpty()) return Collections.emptyList();
        String key = filter.trim().toLowerCase();
        RecipeCacheEntry cached = this.recipeRankCache.get(key);
        if (cached != null && System.currentTimeMillis() < cached.getExpiresAtMs()) {
            return cached.getRanked();
        }
        List<IRecipe> ranked = findRankedMatchingRecipes(filter);
        // Cap total ranked list — the model rarely inspects beyond 25 and
        // crafting only needs the first few.
        List<IRecipe> capped = ranked.size() > MAX_RANKED_RECIPES
                ? new ArrayList<>(ranked.subList(0, MAX_RANKED_RECIPES)) : ranked;
        this.recipeRankCache.put(key, new RecipeCacheEntry(capped));
        if (this.recipeRankCache.size() > 64) {
            // Bound growth under pathological filter churn; purge is approximate.
            Iterator<String> it = this.recipeRankCache.keySet().iterator();
            for (int i = 0; i < 16 && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
        return capped;
    }

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

    public static boolean stackMatchesRecipeOption(ItemStack candidate, ItemStack option) {
        if (candidate == null || candidate.stackSize <= 0 || candidate.getItem() == null) return false;
        if (option == null || option.getItem() == null) return false;
        if (candidate.getItem() != option.getItem()) return false;
        int optMeta = option.getItemDamage();
        return optMeta == OreDictionary.WILDCARD_VALUE || optMeta == candidate.getItemDamage();
    }

    public static boolean stackMatchesAnyOption(ItemStack candidate, List<ItemStack> options) {
        if (candidate == null || options == null) return false;
        for (ItemStack opt : options) {
            if (stackMatchesRecipeOption(candidate, opt)) return true;
        }
        return false;
    }

    private static List<IRecipe> findRankedMatchingRecipes(String filter) {
        List<ScoredRecipe> scored = new ArrayList<>();
        List<?> allRecipes = CraftingManager.getInstance().getRecipeList();
        if (allRecipes != null) {
            for (Object ro : allRecipes) {
                if (!(ro instanceof IRecipe)) continue;
                IRecipe recipe = (IRecipe) ro;
                ItemStack out = recipe.getRecipeOutput();
                if (out == null || out.getItem() == null) continue;
                int score = ItemClassifier.scoreBlockOrItemMatch(out.getDisplayName(),
                        ItemClassifier.getItemIdentifier(out), out.getItemDamage(), filter);
                if (score > 0 && !extractRecipeIngredients(recipe).isEmpty()) {
                    scored.add(new ScoredRecipe(recipe, score));
                }
            }
        }
        scored.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
        List<IRecipe> result = new ArrayList<>(scored.size());
        for (ScoredRecipe sr : scored) {
            result.add(sr.getRecipe());
        }
        return result;
    }

    /**
     * JSON view of matching recipes for the read-only recipe tools.
     */
    public JsonObject getRecipesForFilter(String filter) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        String f = (filter == null || filter.trim().isEmpty()) ? "chest" : filter.trim();
        List<IRecipe> ranked = rankedRecipes(f);
        JsonArray recipesArr = new JsonArray();
        for (IRecipe recipe : ranked) {
            if (recipesArr.size() >= MAX_RANKED_RECIPES) break;
            ItemStack out = recipe.getRecipeOutput();
            List<List<ItemStack>> ingSlots = extractRecipeIngredients(recipe);
            if (out == null || ingSlots.isEmpty()) continue;
            JsonObject roJson = new JsonObject();
            roJson.addProperty("output", out.getDisplayName());
            roJson.addProperty("outputCount", Math.max(1, out.stackSize));
            JsonArray ingArr = new JsonArray();
            for (List<ItemStack> opts : ingSlots) {
                if (!opts.isEmpty() && opts.get(0) != null) {
                    ItemStack first = opts.get(0);
                    int meta = first.getItemDamage() == OreDictionary.WILDCARD_VALUE
                            ? 0 : first.getItemDamage();
                    ItemStack display = new ItemStack(first.getItem(), 1, meta);
                    ingArr.add(new JsonPrimitive(display.getDisplayName()));
                }
            }
            roJson.add("ingredients", ingArr);
            recipesArr.add(roJson);
        }
        res.addProperty("count", recipesArr.size());
        res.add("recipes", recipesArr);
        return res;
    }
}
