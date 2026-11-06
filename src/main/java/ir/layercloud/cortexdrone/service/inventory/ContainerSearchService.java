package ir.layercloud.cortexdrone.service.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import lombok.Value;
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
@RequiredArgsConstructor
public class ContainerSearchService {
    @Value
    private static class ChestMatchResult {
        JsonObject chestJson;
        int bestScore;
        int totalMatching;
        double dist;
    }

    private final InventoryService inventoryService;

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

        List<TileEntity> tiles = this.inventoryService.findAllInventoryTiles(world, d.posX, d.posY, d.posZ, r);
        if (owner != null && owner.worldObj == world) {
            List<TileEntity> ownerTiles = this.inventoryService.findAllInventoryTiles(world, owner.posX, owner.posY, owner.posZ, r);
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
            int bx = te.xCoord, by = te.yCoord, bz = te.zCoord;
            int totalSlots = inv.getSizeInventory();
            int usedSlots = 0;
            int matchingInChest = 0;
            int bestChestScore = 0;

            JsonArray matchesArr = new JsonArray();
            Map<String, JsonObject> aggregatedByItem = new LinkedHashMap<String, JsonObject>();

            for (int i = 0; i < totalSlots; i++) {
                ItemStack st = inv.getStackInSlot(i);
                if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
                usedSlots++;

                String disp = st.getDisplayName();
                String fullId = ItemClassifier.getItemIdentifier(st);
                int meta = st.getItemDamage();
                int score = matchAll ? 50 : ItemClassifier.scoreBlockOrItemMatch(disp, fullId, meta, query);
                if (score <= 0) continue;

                matchingInChest += st.stackSize;
                if (score > bestChestScore) bestChestScore = score;

                String itemKey = (disp != null ? disp : "Item") + ":" + meta;
                totalsByItem.put(disp, (totalsByItem.containsKey(disp) ? totalsByItem.get(disp) : 0) + st.stackSize);

                // Aggregate stacks of the same item within the chest so 27 stacks of Cobblestone become 1 concise entry
                JsonObject existing = aggregatedByItem.get(itemKey);
                if (existing != null) {
                    existing.addProperty("count", existing.get("count").getAsInt() + st.stackSize);
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
            Block b = world.getBlock(bx, by, bz);
            int bMeta = world.getBlockMetadata(bx, by, bz);
            String bName = ItemClassifier.getBlockDisplayName(b, bMeta);
            String bReg = b != null ? Block.blockRegistry.getNameForObject(b) : "";
            double dist = Math.round(d.getDistance(bx + 0.5, by + 0.5, bz + 0.5) * 10.0) / 10.0;

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
                if (a.getBestScore() != b.getBestScore()) return Integer.compare(b.getBestScore(), a.getBestScore());
                if (a.getTotalMatching() != b.getTotalMatching()) return Integer.compare(b.getTotalMatching(), a.getTotalMatching());
                return Double.compare(a.getDist(), b.getDist());
            }
        });

        JsonArray chestsOut = new JsonArray();
        for (ChestMatchResult cm : matchedChests) {
            chestsOut.add(cm.getChestJson());
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
            for (int i = 0; i < droneInv.getSizeInventory(); i++) {
                ItemStack st = droneInv.getStackInSlot(i);
                if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
                if (matchAll || ItemClassifier.matchesBlockOrItemName(st.getDisplayName(), ItemClassifier.getItemIdentifier(st), st.getItemDamage(), query)) {
                    inDroneCount += st.stackSize;
                    droneMatches.add(ItemClassifier.itemStackToJson(i, st));
                }
            }
        }

        int inPlayerCount = 0;
        JsonArray playerMatches = new JsonArray();
        if (owner != null && owner.inventory != null) {
            for (int i = 0; i < 36; i++) {
                ItemStack st = owner.inventory.getStackInSlot(i);
                if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
                if (matchAll || ItemClassifier.matchesBlockOrItemName(st.getDisplayName(), ItemClassifier.getItemIdentifier(st), st.getItemDamage(), query)) {
                    inPlayerCount += st.stackSize;
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
