package ir.layercloud.cortexdrone.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemTool;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.tileentity.TileEntityFurnace;

@RequiredArgsConstructor
public class DocLookupService {
    private final InventoryService inventoryService;
    private final Map<String, String> webDocCache = new ConcurrentHashMap<String, String>();

    @SuppressWarnings("unchecked")
    public JsonObject lookup(String rawQuery, String rawModId, boolean includeWeb) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        String query = (rawQuery != null) ? rawQuery.trim() : "";
        String modFilter = (rawModId != null && !rawModId.trim().isEmpty()) ? rawModId.trim().toLowerCase() : null;
        res.addProperty("query", query);
        if (query.isEmpty()) {
            res.addProperty("ok", false);
            res.addProperty("error", "Empty query");
            return res;
        }

        String qLower = query.toLowerCase().replace('_', ' ');
        JsonArray registryMatches = new JsonArray();

        // 1) Inspect live Forge Item & Block registries
        Set<?> itemKeys = Item.itemRegistry.getKeys();
        if (itemKeys != null) {
            for (Object keyObj : itemKeys) {
                if (registryMatches.size() >= 18) break;
                String regName = String.valueOf(keyObj);
                if (modFilter != null && !regName.toLowerCase().startsWith(modFilter + ":")) continue;
                Item item = (Item) Item.itemRegistry.getObject(regName);
                if (item == null) continue;

                for (int meta = 0; meta < (item.getHasSubtypes() ? 8 : 1); meta++) {
                    try {
                        ItemStack st = new ItemStack(item, 1, meta);
                        String disp = st.getDisplayName();
                        String combined = ItemClassifier.getItemIdentifier(st);
                        if (ItemClassifier.matchesBlockOrItemName(disp, combined, meta, qLower)
                            || (disp != null && disp.toLowerCase().contains(qLower))
                            || regName.toLowerCase().replace('_', ' ').contains(qLower)) {
                            JsonObject io = new JsonObject();
                            io.addProperty("name", disp != null ? disp : regName);
                            io.addProperty("registryName", regName);
                            io.addProperty("id", Item.getIdFromItem(item));
                            io.addProperty("meta", meta);
                            int colon = regName.indexOf(':');
                            io.addProperty("modId", colon > 0 ? regName.substring(0, colon) : "minecraft");
                            Block block = Block.getBlockFromItem(item);
                            boolean isBlock = (block != null && block != net.minecraft.init.Blocks.air);
                            io.addProperty("isBlock", isBlock);
                            io.addProperty("isFood", item instanceof ItemFood);
                            io.addProperty("isTool", item instanceof ItemTool);
                            int burnTime = TileEntityFurnace.getItemBurnTime(st);
                            if (burnTime > 0) {
                                io.addProperty("fuelBurnTicks", burnTime);
                            }
                            ItemStack smeltOut = FurnaceRecipes.smelting().getSmeltingResult(st);
                            if (smeltOut != null && smeltOut.getItem() != null) {
                                io.addProperty("smeltsInto", smeltOut.getDisplayName() + " x" + Math.max(1, smeltOut.stackSize));
                            }
                            JsonArray oreTags = new JsonArray();
                            for (String tag : ItemClassifier.getOreDictNames(st)) {
                                oreTags.add(new JsonPrimitive(tag));
                            }
                            if (oreTags.size() > 0) {
                                io.add("oreDict", oreTags);
                            }
                            registryMatches.add(io);
                            if (registryMatches.size() >= 18) break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        res.addProperty("matchCount", registryMatches.size());
        res.add("items", registryMatches);

        // 2) Inspect CraftingManager recipes matching query
        JsonObject craftObj = this.inventoryService.getRecipesForFilter(query);
        if (craftObj != null && craftObj.has("recipes")) {
            res.add("craftingRecipes", craftObj.get("recipes"));
        }

        // 2b) Inspect HBM mod machine recipes via principled dynamic JSON parser
        // (parses every HBM config file found in hbmRecipes directory, not a static list)
        try {
            JsonObject machineObj = this.inventoryService.getMachineRecipeService().getMachineRecipesForFilter(query, modFilter);
            if (machineObj != null && machineObj.has("recipes") && machineObj.getAsJsonArray("recipes").size() > 0) {
                res.add("machineConfigRecipes", machineObj.getAsJsonArray("recipes"));
            }
        } catch (Throwable ignored) {
        }

        // 3) Inspect local mod machine recipe files (HBM Nuclear Tech & OpenComputers) — legacy raw substring search fallback
        JsonArray machineRecipes = searchLocalModRecipeConfigs(query);
        if (machineRecipes.size() > 0 && !res.has("machineConfigRecipes")) {
            res.add("machineConfigRecipes", machineRecipes);
        }

        // 4) External Web Wiki Fallback if in-game matches are empty or web lookup requested
        if (includeWeb || (registryMatches.size() == 0 && machineRecipes.size() == 0)) {
            String wikiExtract = fetchWebWikiSummary(query, modFilter);
            if (wikiExtract != null && !wikiExtract.isEmpty()) {
                res.addProperty("wikiSummary", wikiExtract);
            }
        }

        return res;
    }

    private static File resolveConfigDirectory() {
        try {
            net.minecraft.server.MinecraftServer srv = net.minecraft.server.MinecraftServer.getServer();
            if (srv != null) {
                File f = srv.getFile("config");
                if (f != null && f.isDirectory()) return f;
            }
        } catch (Throwable ignored) {
        }
        File rel = new File("config");
        if (rel.isDirectory()) return rel;
        return new File("/root/forge-smp/config");
    }

    private JsonArray searchLocalModRecipeConfigs(String query) {
        JsonArray out = new JsonArray();
        if (query == null || query.length() < 3) return out;
        String qToken = query.trim().toLowerCase().replace(' ', '_');
        String qSpaced = query.trim().toLowerCase();

        File configDir = resolveConfigDirectory();
        File hbmDir = new File(configDir, "hbmRecipes");
        if (hbmDir.isDirectory()) {
            File[] files = hbmDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (out.size() >= 10) break;
                    if (!f.getName().endsWith(".json")) continue;
                    String machineName = f.getName().replace("_hbm", "").replace(".json", "");
                    try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                        StringBuilder contentSb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            contentSb.append(line.trim()).append(' ');
                        }
                        String full = contentSb.toString();
                        String lower = full.toLowerCase();
                        int idx = lower.indexOf(qToken);
                        if (idx < 0) idx = lower.indexOf(qSpaced);
                        if (idx < 0 && machineName.toLowerCase().contains(qToken)) idx = 0;
                        int matchesInFile = 0;
                        while (idx >= 0 && matchesInFile < 3 && out.size() < 10) {
                            int start = Math.max(0, idx - 120);
                            int end = Math.min(full.length(), idx + 280);
                            JsonObject mo = new JsonObject();
                            mo.addProperty("machine", "HBM " + machineName);
                            mo.addProperty("recipe", full.substring(start, end));
                            out.add(mo);
                            matchesInFile++;
                            idx = lower.indexOf(qToken, idx + qToken.length() + 40);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return out;
    }

    private String fetchWebWikiSummary(String query, String modId) {
        String cacheKey = ((modId != null ? modId : "") + ":" + query).toLowerCase();
        if (this.webDocCache.containsKey(cacheKey)) {
            return this.webDocCache.get(cacheKey);
        }
        String[] endpoints = (modId != null && modId.contains("hbm"))
            ? new String[]{"https://ntm.fandom.com/api.php", "https://minecraft.wiki/api.php"}
            : new String[]{"https://minecraft.wiki/api.php", "https://ntm.fandom.com/api.php"};

        for (String baseApi : endpoints) {
            try {
                String urlStr = baseApi + "?action=query&list=search&srsearch="
                    + URLEncoder.encode(query, "UTF-8") + "&utf8=1&format=json&srlimit=2";
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "CortexDrone/1.4 (Minecraft 1.7.10 AI Engine)");
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(3000);
                if (conn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                    }
                    JsonElement parsed = new JsonParser().parse(sb.toString());
                    if (parsed != null && parsed.isJsonObject()) {
                        JsonObject qObj = parsed.getAsJsonObject().getAsJsonObject("query");
                        if (qObj != null && qObj.has("search") && qObj.get("search").isJsonArray()) {
                            JsonArray sArr = qObj.getAsJsonArray("search");
                            if (sArr.size() > 0) {
                                JsonObject first = sArr.get(0).getAsJsonObject();
                                String title = first.has("title") ? first.get("title").getAsString() : query;
                                String snippet = first.has("snippet")
                                    ? first.get("snippet").getAsString().replaceAll("<[^>]+>", "").trim()
                                    : "";
                                String summary = title + ": " + snippet;
                                this.webDocCache.put(cacheKey, summary);
                                return summary;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }
}
