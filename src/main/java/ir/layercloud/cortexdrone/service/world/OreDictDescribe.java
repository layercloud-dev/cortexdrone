package ir.layercloud.cortexdrone.service.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JSON description of an OreDictionary query.
 *
 * <p>Extracted from {@code OreDictResolver}. Answers "which tags matched, and
 * which real items are behind them", deduplicated by item id and metadata so the
 * same stack does not appear twice under two tags.</p>
 */
final class OreDictDescribe {
    private OreDictDescribe() {
    }

    /**
     * Builds the {@code find_oredict} response for already-resolved tags.
     */
    static JsonObject tagsAndItems(String query, int limit, List<String> tags) {
        JsonObject out = new JsonObject();
        out.addProperty("query", query);
        JsonArray tagArray = new JsonArray();
        JsonArray itemArray = new JsonArray();
        int max = Math.max(1, limit);
        Set<String> seen = new HashSet<>();

        for (String tag : tags) {
            tagArray.add(new JsonPrimitive(tag));
            if (itemArray.size() >= max) continue;
            for (ItemStack stack : OreDictIndex.ores(tag)) {
                if (itemArray.size() >= max) break;
                if (stack == null || stack.getItem() == null) continue;
                String key = Item.itemRegistry.getNameForObject(stack.getItem())
                        + ":" + stack.getItemDamage();
                if (!seen.add(key)) continue;
                JsonObject item = ItemClassifier.itemStackToJson(-1, stack);
                item.addProperty("oreDict", tag);
                itemArray.add(item);
            }
        }
        out.add("tags", tagArray);
        out.add("items", itemArray);
        out.addProperty("tagCount", tagArray.size());
        out.addProperty("itemCount", itemArray.size());
        return out;
    }
}
