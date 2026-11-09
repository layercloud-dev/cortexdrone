package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.harness.tool.ToolTimeouts;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.service.world.OreDictResolver;

import java.util.Collections;
import java.util.List;

/**
 * {@code find_oredict}: resolve a player's material word to Forge
 * OreDictionary tags and show what is registered under them.
 *
 * <p>This is the lookup step before a filtered scan/withdraw: "tin" is not a
 * registry name, but it is {@code oreTin} and {@code ingotTin}. Keeping the
 * handler here lets {@link WorldTools} stay focused on spatial queries.</p>
 */
public final class OreDictTools implements ToolProvider {
    private static final int MIN_LIMIT = 1;
    private static final int DEFAULT_LIMIT = 24;
    private static final int MAX_LIMIT = 64;

    public static List<ToolSpec> specs() {
        return Collections.singletonList(
                ToolSpec.builder("find_oredict")
                        .description("Resolve a name or phrase to Forge OreDictionary tags and list the "
                                + "blocks/items registered under them. Use it when a player names a material "
                                + "instead of an exact item ('tin', 'iron ingot', 'planks'); then pass the "
                                + "tag as '#oreTin' or as the bare tag name to scan_blocks, find_item, "
                                + "withdraw_chest, mine_blocks and the other filter tools. Resources may be "
                                + "far from the player: the filter still works anywhere the drone can fly.")
                        .parameters(ToolSchema.object()
                                .requiredString("query", "Name, tag or phrase, e.g. 'tin', 'oreIron', '#ingotTin'.")
                                .optionalString("mod_id", "Restrict item results to one mod prefix, e.g. 'hbm'.")
                                .optionalInt("limit", "Maximum item stacks to list, " + MIN_LIMIT + ".." + MAX_LIMIT + ".", MIN_LIMIT, MAX_LIMIT,
                                        DEFAULT_LIMIT))
                        .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.FAST_MS).build()
        );
    }

    @Override
    public void register(ToolRegistry registry) {
        registry.bind("find_oredict", getClass().getSimpleName(),
                (context, arguments) -> handle(ToolArgs.camelAliases(arguments)));
    }

    public JsonObject handle(JsonObject args) {
        JsonObject res = new JsonObject();
        String query = firstString(args, "query", "filter", "item");
        if (query.trim().isEmpty()) {
            res.addProperty("ok", false);
            res.addProperty("code", "invalid_arguments");
            res.addProperty("error", "find_oredict requires a query");
            res.addProperty("hint", "Pass a material word or tag, e.g. {\"query\":\"tin\"}.");
            res.addProperty("retryable", false);
            return res;
        }
        int limit = args != null && args.has("limit")
                ? Math.min(MAX_LIMIT, Math.max(1, args.get("limit").getAsInt()))
                : DEFAULT_LIMIT;
        String modId = firstString(args, "modId", "mod_id");
        modId = modId.isEmpty() ? null : modId.trim().toLowerCase();

        JsonObject described = OreDictResolver.describeTagQuery(query, modId == null ? limit : MAX_LIMIT);
        if (modId != null && described.has("items")) {
            JsonArray filtered = filterByMod(described.getAsJsonArray("items"), modId, limit);
            described.add("items", filtered);
            described.addProperty("itemCount", filtered.size());
        }
        int tagCount = described.has("tagCount") ? described.get("tagCount").getAsInt() : 0;
        described.addProperty("ok", true);
        described.addProperty("found", tagCount > 0);
        if (tagCount == 0) {
            described.addProperty("hint", "no OreDictionary tag matched; try a shorter material word "
                    + "such as 'iron', 'tin' or 'plank', or a registry name from doc_lookup");
        }
        return described;
    }

    private static JsonArray filterByMod(JsonArray source, String modId, int limit) {
        JsonArray kept = new JsonArray();
        String prefix = modId + ":";
        for (int i = 0; i < source.size() && kept.size() < limit; i++) {
            if (!source.get(i).isJsonObject()) continue;
            JsonObject item = source.get(i).getAsJsonObject();
            String registry = item.has("registryName")
                    ? item.get("registryName").getAsString().toLowerCase() : "";
            if (registry.startsWith(prefix)) kept.add(item);
        }
        return kept;
    }

    private static String firstString(JsonObject args, String... keys) {
        if (args == null) return "";
        for (String key : keys) {
            if (args.has(key) && !args.get(key).isJsonNull()) {
                return args.get(key).getAsString();
            }
        }
        return "";
    }
}
