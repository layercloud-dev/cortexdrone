package ir.layercloud.cortexdrone.service.machine;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import lombok.Value;
import lombok.experimental.UtilityClass;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Ranking, filtering, and ingredient-availability assessment for HBM
 * machine recipes. Moved verbatim from MachineRecipeService
 * (rankAndFilter, assess, countAllNearby and helpers) so the core stays
 * under the line limit.
 */
@UtilityClass
@SuppressWarnings("unused")
final class MachineRecipeRanker {

    private static final long COUNT_CACHE_TTL_MS = 1000L;
    private static final java.util.Map<String, java.util.Map<String, Integer>> COUNT_CACHE = new java.util.HashMap<>();
    private static final java.util.Map<String, Long> COUNT_CACHE_EXPIRY = new java.util.HashMap<>();

    static java.util.Map<String, Integer> countAllNearby(InventoryService inventoryService, World world,
                                                         li.cil.oc.common.entity.Drone drone,
                                                         EntityPlayerMP player, int radius) {
        return countAllNearbyCached(inventoryService, world, drone, player, radius);
    }

    private static java.util.Map<String, Integer> countAllNearbyCached(InventoryService inventoryService, World world,
                                                                       li.cil.oc.common.entity.Drone drone,
                                                                       EntityPlayerMP player, int radius) {
        // Cache the expensive nearby-inventory sweep for ~1s so the
        // getBestMachineRecipe loop (which re-assesses every ranked candidate
        // plus recipeDuration lookups re-enter getBest) does not re-scan
        // every loaded TileEntity inventory for each candidate each tick.
        // Bucket every ~16 blocks: chest contents do not change on sub-block
        // movement, so a coarser key avoids an uncached scan per block step.
        long now = System.currentTimeMillis();
        long bucket = radius / 16;
        // Every scan is centred on the drone, so the cache key must be too.
        int droneX = drone != null ? (int) drone.posX : 0;
        int droneZ = drone != null ? (int) drone.posZ : 0;
        int dimId = 0;
        if (world != null && world.provider != null) {
            dimId = world.provider.dimensionId;
        }
        String key = dimId + "|" + droneX / 16 + "|" + droneZ / 16 + "|" + bucket + "|"
                + (drone != null ? drone.getUniqueID() : (player != null ? player.getCommandSenderName() : "null"));
        java.util.Map<String, Integer> hitCache = COUNT_CACHE.get(key);
        Long expiry = COUNT_CACHE_EXPIRY.get(key);
        if (hitCache != null && expiry != null && expiry > now) {
            return new java.util.LinkedHashMap<>(hitCache);
        }
        java.util.Map<String, Integer> totals = countAllNearbyUncached(inventoryService, world, drone, player, radius);
        COUNT_CACHE.put(key, new java.util.LinkedHashMap<>(totals));
        COUNT_CACHE_EXPIRY.put(key, now + COUNT_CACHE_TTL_MS);
        if (COUNT_CACHE.size() > 16) {
            java.util.Iterator<String> it = COUNT_CACHE_EXPIRY.keySet().iterator();
            while (it.hasNext()) {
                String k = it.next();
                Long exp = COUNT_CACHE_EXPIRY.get(k);
                if (exp == null || exp <= now) {
                    it.remove();
                    COUNT_CACHE.remove(k);
                }
            }
            if (COUNT_CACHE.size() > 16) {
                COUNT_CACHE.clear();
                COUNT_CACHE_EXPIRY.clear();
            }
        }
        return totals;
    }

    static java.util.Map<String, Integer> countAllNearbyUncached(InventoryService inventoryService, World world,
                                                                 li.cil.oc.common.entity.Drone drone,
                                                                 EntityPlayerMP player, int radius) {
        java.util.Map<String, Integer> totals = new java.util.LinkedHashMap<>();
        try {
            if (world == null) return totals;
            IInventory droneInv = findDroneInventory(world, drone);
            if (droneInv != null) {
                for (int i = 0; i < droneInv.getSizeInventory(); i++) {
                    ItemStack st = droneInv.getStackInSlot(i);
                    if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
                    addToTotals(totals, st);
                }
            }
            if (player != null && player.inventory != null) {
                for (int i = 0; i < Math.min(36, player.inventory.getSizeInventory()); i++) {
                    ItemStack st = player.inventory.getStackInSlot(i);
                    if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
                    addToTotals(totals, st);
                }
            }
            double cx = drone != null ? drone.posX : 0;
            double cy = drone != null ? drone.posY : 64;
            double cz = drone != null ? drone.posZ : 0;
            List<net.minecraft.tileentity.TileEntity> tiles = inventoryService.findAllInventoryTiles(world, cx, cy, cz, radius);
            for (net.minecraft.tileentity.TileEntity te : tiles) {
                if (!(te instanceof IInventory)) continue;
                IInventory inv = (IInventory) te;
                for (int i = 0; i < inv.getSizeInventory(); i++) {
                    ItemStack st = inv.getStackInSlot(i);
                    if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
                    addToTotals(totals, st);
                }
            }
        } catch (Throwable ignored) {
        }
        return totals;
    }

    static void addToTotals(java.util.Map<String, Integer> totals, ItemStack st) {
        String key = totalsKey(st);
        totals.put(key, (totals.getOrDefault(key, 0)) + st.stackSize);
        for (String tag : ItemClassifier.getOreDictNames(st)) {
            String tkey = "dict:" + tag.toLowerCase();
            totals.put(tkey, (totals.getOrDefault(tkey, 0)) + st.stackSize);
        }
        BlockLike bl = toBlockLike(st);
        if (bl != null) {
            for (String tag : ItemClassifier.getOreDictNames(bl.getBlock(), bl.getMeta())) {
                String tkey = "dict:" + tag.toLowerCase();
                totals.put(tkey, (totals.getOrDefault(tkey, 0)) + st.stackSize);
            }
        }
    }

    static String totalsKey(ItemStack st) {
        Item item = st.getItem();
        String reg = item != null ? Item.itemRegistry.getNameForObject(item) : null;
        int meta = st.getItemDamage();
        String name = reg != null ? reg.toLowerCase()
                : (item != null ? item.getClass().getName().toLowerCase() : "");
        return name + ":" + meta;
    }

    static BlockLike toBlockLike(ItemStack st) {
        try {
            net.minecraft.block.Block b = net.minecraft.block.Block.getBlockFromItem(st.getItem());
            if (b == null || b == net.minecraft.init.Blocks.air) return null;
            return new BlockLike(b, st.getItemDamage());
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Value
    private static class BlockLike {
        net.minecraft.block.Block block;
        int meta;
    }

    static IInventory findDroneInventory(World world, li.cil.oc.common.entity.Drone drone) {
        if (drone == null) return null;
        try {
            net.minecraft.inventory.IInventory inv = drone.mainInventory();
            return inv;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static MachineRecipeService.Availability assess(InventoryService inventoryService,
                                                    MachineRecipeService.NormalizedRecipe r,
                                                    World world, li.cil.oc.common.entity.Drone drone,
                                                    EntityPlayerMP player, int radius) {
        MachineRecipeService.Availability av = new MachineRecipeService.Availability();
        java.util.Map<String, Integer> totals = countAllNearby(inventoryService, world, drone, player, radius);
        int needed = 0;
        int have = 0;
        int miss = 0;
        for (JsonObject in : r.inputs) {
            int amt = in.has("amount") ? in.get("amount").getAsInt() : 1;
            String type = in.has("type") ? in.get("type").getAsString() : "item";
            int got = 0;
            if ("dict".equals(type)) {
                String key = "dict:" + in.get("dict").getAsString().toLowerCase();
                got = totals.getOrDefault(key, 0);
            } else if ("item".equals(type)) {
                String reg = in.has("registryName") ? in.get("registryName").getAsString() : "";
                int meta = in.has("meta") ? in.get("meta").getAsInt() : 0;
                String key = reg.toLowerCase() + ":" + meta;
                if (totals.containsKey(key)) {
                    got = totals.get(key);
                } else {
                    for (java.util.Map.Entry<String, Integer> e : totals.entrySet()) {
                        if (e.getKey().startsWith(reg.toLowerCase()) && e.getKey().endsWith(":" + meta)) {
                            got = Math.max(got, e.getValue());
                        }
                    }
                }
            }
            needed += amt;
            have += Math.min(got, amt);
            miss += Math.max(0, amt - got);
        }
        av.setNeeded(needed);
        av.setAvailable(have);
        av.setMissing(miss);
        av.setSufficient(miss == 0 && !r.inputs.isEmpty());
        return av;
    }

    static List<MachineRecipeService.NormalizedRecipe> rankAndFilter(List<MachineRecipeService.NormalizedRecipe> all, String filter, String wantMachine) {
        List<MachineRecipeService.NormalizedRecipe> scored = new ArrayList<>();
        String q = filter != null ? filter.toLowerCase() : "";
        for (MachineRecipeService.NormalizedRecipe r : all) {
            if (!wantMachine.isEmpty() && !r.machine.equals(wantMachine)) continue;
            if (q.isEmpty()) {
                scored.add(r);
                continue;
            }
            int best = 0;
            for (JsonObject out : r.outputs) {
                String name = out.has("displayName") ? out.get("displayName").getAsString().toLowerCase() : "";
                String reg = out.has("registryName") ? out.get("registryName").getAsString().toLowerCase() : "";
                String combined = name + " " + reg;
                int s = ItemClassifier.scoreBlockOrItemMatch(name, combined, out.has("meta") ? out.get("meta").getAsInt() : 0, q);
                if (s > best) best = s;
            }
            for (JsonObject out : r.outputFluids) {
                String fluid = out.has("fluid") ? out.get("fluid").getAsString().toLowerCase() : "";
                String combined = fluid + " fluid " + fluid;
                int s = ItemClassifier.scoreBlockOrItemMatch(fluid, combined, 0, q);
                if (s > best) best = s;
            }
            if (r.name != null && r.name.toLowerCase().contains(q)) best = Math.max(best, 50);
            if (r.displayName != null && r.displayName.toLowerCase().contains(q)) best = Math.max(best, 50);
            if (best > 0) scored.add(r);
        }
        scored.sort((a, b) -> {
            int doc = Integer.compare(b.duration, a.duration);
            if (doc != 0) return doc;
            int pin = Integer.compare(a.inputs.size(), b.inputs.size());
            if (pin != 0) return pin;
            return Long.compare(a.power, b.power);
        });
        return scored;
    }
}
