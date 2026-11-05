package ir.layercloud.cortexdrone.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * Targeted container item search and indexing service.
 * Scans nearby chests/crates for specific item filters (instead of dumping all raw slots)
 * and ranks matching chests so the AI can decide which chest (x,y,z) to use.
 */
public class ContainerSearchService {
    private static class ChestMatchResult {
        final JsonObject chestJson;
        final int bestScore;
        final int totalMatching;
        final double dist;

        ChestMatchResult(JsonObject chestJson, int bestScore, int totalMatching, double dist) {
            this.chestJson = chestJson;
            this.bestScore = bestScore;
            this.totalMatching = totalMatching;
            this.dist = dist;
        }
    }

    private final InventoryService inventoryService;

    public ContainerSearchService(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * Searches all nearby storage containers for items matching `rawQuery` and returns only
     * the chests and slots that contain matches, ranked by relevance score, quantity, and distance.
     */
    public JsonObject searchChestsForItem(World world, DroneController ctrl, EntityPlayerMP player, String rawQuery, int radius) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        if (world == null || ctrl == null || ctrl.drone == null) {
            res.addProperty("ok", false);
            res.addProperty("error", "No active drone");
            return res;
        }

        Drone d = ctrl.drone;
        EntityPlayerMP owner = player != null ? player : CortexDroneMod.findPlayer(ctrl.ownerName);
        String query = ItemClassifier.resolveHeldFilter(owner, rawQuery != null ? rawQuery.trim() : "all");
        boolean matchAll = query.isEmpty() || query.equalsIgnoreCase("all") || query.equalsIgnoreCase("any") || query.equals("*");
        int r = Math.min(64, Math.max(4, radius));

        List<TileEntity> tiles = this.inventoryService.findAllInventoryTiles(world, d.field_70165_t, d.field_70163_u, d.field_70161_v, r);
        if (owner != null && owner.field_70170_p == world) {
            List<TileEntity> ownerTiles = this.inventoryService.findAllInventoryTiles(world, owner.field_70165_t, owner.field_70163_u, owner.field_70161_v, r);
            for (TileEntity ote : ownerTiles) {
                if (!tiles.contains(ote)) {
                    tiles.add(ote);
                }
            }
        }

        Map<String, Integer> totalsByItem = new LinkedHashMap<String, Integer>();
        List<ChestMatchResult> matchedChests = new ArrayList<ChestMatchResult>();
        int grandTotalMatching = 0;

        for (TileEntity te : tiles) {
            if (!(te instanceof IInventory)) continue;
            IInventory inv = (IInventory) te;
            int bx = te.field_145851_c, by = te.field_145848_d, bz = te.field_145849_e;
            int totalSlots = inv.func_70302_i_();
            int usedSlots = 0;
            int matchingInChest = 0;
            int bestChestScore = 0;

            JsonArray matchesArr = new JsonArray();
            Map<String, JsonObject> aggregatedByItem = new LinkedHashMap<String, JsonObject>();

            for (int i = 0; i < totalSlots; i++) {
                ItemStack st = inv.func_70301_a(i);
                if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
                usedSlots++;

                String disp = st.func_82833_r();
                String fullId = ItemClassifier.getItemIdentifier(st);
                int meta = st.func_77960_j();
                int score = matchAll ? 50 : ItemClassifier.scoreBlockOrItemMatch(disp, fullId, meta, query);
                if (score <= 0) continue;

                matchingInChest += st.field_77994_a;
                if (score > bestChestScore) bestChestScore = score;

                String itemKey = (disp != null ? disp : "Item") + ":" + meta;
                totalsByItem.put(disp, (totalsByItem.containsKey(disp) ? totalsByItem.get(disp) : 0) + st.field_77994_a);

                // Aggregate stacks of the same item within the chest so 27 stacks of Cobblestone become 1 concise entry
                JsonObject existing = aggregatedByItem.get(itemKey);
                if (existing != null) {
                    existing.addProperty("count", existing.get("count").getAsInt() + st.field_77994_a);
                    existing.addProperty("stacks", existing.get("stacks").getAsInt() + 1);
                } else {
                    JsonObject slotMatch = ItemClassifier.itemStackToJson(i, st);
                    slotMatch.addProperty("stacks", 1);
                    slotMatch.addProperty("score", score);
                    aggregatedByItem.put(itemKey, slotMatch);
                }
            }

            if (!matchAll && matchingInChest == 0) {
                continue;
            }

            for (JsonObject agg : aggregatedByItem.values()) {
                matchesArr.add(agg);
            }

            grandTotalMatching += matchingInChest;
            Block b = world.func_147439_a(bx, by, bz);
            int bMeta = world.func_72805_g(bx, by, bz);
            String bName = ItemClassifier.getBlockDisplayName(b, bMeta);
            String bReg = b != null ? Block.field_149771_c.func_148750_c(b) : "";
            double dist = Math.round(d.func_70011_f(bx + 0.5, by + 0.5, bz + 0.5) * 10.0) / 10.0;

            JsonObject co = new JsonObject();
            co.addProperty("x", bx);
            co.addProperty("y", by);
            co.addProperty("z", bz);
            co.addProperty("blockName", bName);
            co.addProperty("registryName", bReg != null ? bReg : "");
            co.addProperty("dist", dist);
            co.addProperty("totalSlots", totalSlots);
            co.addProperty("usedSlots", usedSlots);
            co.addProperty("freeSlots", Math.max(0, totalSlots - usedSlots));
            co.addProperty("totalMatchingInChest", matchingInChest);
            co.add("matches", matchesArr);
            co.add("items", matchesArr);

            matchedChests.add(new ChestMatchResult(co, bestChestScore, matchingInChest, dist));
        }

        Collections.sort(matchedChests, new Comparator<ChestMatchResult>() {
            @Override
            public int compare(ChestMatchResult a, ChestMatchResult b) {
                if (a.bestScore != b.bestScore) return Integer.compare(b.bestScore, a.bestScore);
                if (a.totalMatching != b.totalMatching) return Integer.compare(b.totalMatching, a.totalMatching);
                return Double.compare(a.dist, b.dist);
            }
        });

        JsonArray chestsOut = new JsonArray();
        for (ChestMatchResult cm : matchedChests) {
            chestsOut.add(cm.chestJson);
        }

        JsonObject totalsJson = new JsonObject();
        for (Map.Entry<String, Integer> e : totalsByItem.entrySet()) {
            totalsJson.addProperty(e.getKey(), e.getValue());
        }

        res.addProperty("query", query);
        res.addProperty("found", grandTotalMatching > 0);
        res.addProperty("totalMatchingItems", grandTotalMatching);
        res.addProperty("matchingChestCount", chestsOut.size());
        res.add("totalsByItem", totalsJson);
        if (!matchedChests.isEmpty()) {
            JsonObject best = matchedChests.get(0).chestJson;
            JsonObject bestSummary = new JsonObject();
            bestSummary.addProperty("x", best.get("x").getAsInt());
            bestSummary.addProperty("y", best.get("y").getAsInt());
            bestSummary.addProperty("z", best.get("z").getAsInt());
            bestSummary.addProperty("blockName", best.get("blockName").getAsString());
            bestSummary.addProperty("dist", best.get("dist").getAsDouble());
            bestSummary.addProperty("count", best.get("totalMatchingInChest").getAsInt());
            res.add("bestChest", bestSummary);
        }
        res.add("chests", chestsOut);
        return res;
    }

    /**
     * Searches for `rawQuery` across drone inventory, player inventory, and all nearby chests,
     * returning exact counts and locations so the AI can choose the best source.
     */
    public JsonObject findItemEverywhere(World world, DroneController ctrl, EntityPlayerMP player, String rawQuery, int radius) {
        JsonObject res = searchChestsForItem(world, ctrl, player, rawQuery, radius);
        if (!res.has("ok") || !res.get("ok").getAsBoolean()) return res;

        EntityPlayerMP owner = player != null ? player : CortexDroneMod.findPlayer(ctrl.ownerName);
        String query = ItemClassifier.resolveHeldFilter(owner, rawQuery != null ? rawQuery.trim() : "all");
        boolean matchAll = query.isEmpty() || query.equalsIgnoreCase("all") || query.equalsIgnoreCase("any") || query.equals("*");

        int inDroneCount = 0;
        JsonArray droneMatches = new JsonArray();
        IInventory droneInv = ctrl.drone.mainInventory();
        if (droneInv != null) {
            for (int i = 0; i < droneInv.func_70302_i_(); i++) {
                ItemStack st = droneInv.func_70301_a(i);
                if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
                if (matchAll || ItemClassifier.matchesBlockOrItemName(st.func_82833_r(), ItemClassifier.getItemIdentifier(st), st.func_77960_j(), query)) {
                    inDroneCount += st.field_77994_a;
                    droneMatches.add(ItemClassifier.itemStackToJson(i, st));
                }
            }
        }

        int inPlayerCount = 0;
        JsonArray playerMatches = new JsonArray();
        if (owner != null && owner.field_71071_by != null) {
            for (int i = 0; i < 36; i++) {
                ItemStack st = owner.field_71071_by.func_70301_a(i);
                if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
                if (matchAll || ItemClassifier.matchesBlockOrItemName(st.func_82833_r(), ItemClassifier.getItemIdentifier(st), st.func_77960_j(), query)) {
                    inPlayerCount += st.field_77994_a;
                    playerMatches.add(ItemClassifier.itemStackToJson(i, st));
                }
            }
        }

        int inChestsCount = res.get("totalMatchingItems").getAsInt();
        res.addProperty("inDroneCount", inDroneCount);
        res.add("droneMatches", droneMatches);
        res.addProperty("inPlayerCount", inPlayerCount);
        res.add("playerMatches", playerMatches);
        res.addProperty("inChestsCount", inChestsCount);
        res.addProperty("totalAvailable", inDroneCount + inPlayerCount + inChestsCount);
        res.addProperty("found", (inDroneCount + inPlayerCount + inChestsCount) > 0);
        return res;
    }
}
