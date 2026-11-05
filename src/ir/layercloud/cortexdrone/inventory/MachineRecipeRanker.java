package ir.layercloud.cortexdrone.inventory;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * Ranking, filtering, and ingredient-availability assessment for HBM
 * machine recipes. Moved verbatim from MachineRecipeService
 * (rankAndFilter, assess, countAllNearby and helpers) so the core stays
 * under the line limit.
 */
final class MachineRecipeRanker {
    private MachineRecipeRanker() {
    }

    private static final long COUNT_CACHE_TTL_MS = 1000L;
    private static final java.util.Map<String, java.util.Map<String, Integer>> COUNT_CACHE = new java.util.HashMap<String, java.util.Map<String, Integer>>();
    private static final java.util.Map<String, Long> COUNT_CACHE_EXPIRY = new java.util.HashMap<String, Long>();

    static java.util.Map<String, Integer> countAllNearby(InventoryService inventoryService, World world, EntityPlayerMP player, int radius) {
        return countAllNearbyCached(inventoryService, world, player, radius);
    }

    private static java.util.Map<String, Integer> countAllNearbyCached(InventoryService inventoryService, World world, EntityPlayerMP player, int radius) {
        // Cache the expensive nearby-inventory sweep for ~1s so the
        // getBestMachineRecipe loop (which re-assesses every ranked candidate
        // plus recipeDuration lookups re-enter getBest) does not re-scan
        // every loaded TileEntity inventory for each candidate each tick.
        // Bucket every ~16 blocks: chest contents do not change on sub-block
        // movement, so a coarser key avoids an uncached scan per block step.
        long now = System.currentTimeMillis();
        long bucket = radius / 16;
        int droneX = player != null ? (int) player.field_70165_t : 0;
        int droneZ = player != null ? (int) player.field_70161_v : 0;
        int dimId = 0;
        if (world != null && world.field_73011_w != null) {
            try {
                dimId = world.field_73011_w.field_76574_g;
            } catch (Throwable ignored) {
                dimId = 0;
            }
        }
        String key = dimId + "|" + droneX / 16 + "|" + droneZ / 16 + "|" + bucket + "|" + (player != null ? player.func_70005_c_() : "null");
        java.util.Map<String, Integer> hitCache = COUNT_CACHE.get(key);
        Long expiry = COUNT_CACHE_EXPIRY.get(key);
        if (hitCache != null && expiry != null && expiry.longValue() > now) {
            return new java.util.LinkedHashMap<String, Integer>(hitCache);
        }
        java.util.Map<String, Integer> totals = countAllNearbyUncached(inventoryService, world, player, radius);
        COUNT_CACHE.put(key, new java.util.LinkedHashMap<String, Integer>(totals));
        COUNT_CACHE_EXPIRY.put(key, Long.valueOf(now + COUNT_CACHE_TTL_MS));
        if (COUNT_CACHE.size() > 16) {
            java.util.Iterator<String> it = COUNT_CACHE_EXPIRY.keySet().iterator();
            while (it.hasNext()) {
                String k = it.next();
                Long exp = COUNT_CACHE_EXPIRY.get(k);
                if (exp == null || exp.longValue() <= now) {
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

    static java.util.Map<String, Integer> countAllNearbyUncached(InventoryService inventoryService, World world, EntityPlayerMP player, int radius) {
        java.util.Map<String, Integer> totals = new java.util.LinkedHashMap<String, Integer>();
        try {
            if (world == null) return totals;
            IInventory droneInv = findDroneInventory(world, player);
            if (droneInv != null) {
                for (int i = 0; i < droneInv.func_70302_i_(); i++) {
                    ItemStack st = droneInv.func_70301_a(i);
                    if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
                    addToTotals(totals, st);
                }
            }
            if (player != null && player.field_71071_by != null) {
                for (int i = 0; i < Math.min(36, player.field_71071_by.func_70302_i_()); i++) {
                    ItemStack st = player.field_71071_by.func_70301_a(i);
                    if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
                    addToTotals(totals, st);
                }
            }
            double cx = player != null ? player.field_70165_t : 0;
            double cy = player != null ? player.field_70163_u : 64;
            double cz = player != null ? player.field_70161_v : 0;
            List<net.minecraft.tileentity.TileEntity> tiles = inventoryService.findAllInventoryTiles(world, cx, cy, cz, radius);
            for (net.minecraft.tileentity.TileEntity te : tiles) {
                if (!(te instanceof IInventory)) continue;
                IInventory inv = (IInventory) te;
                for (int i = 0; i < inv.func_70302_i_(); i++) {
                    ItemStack st = inv.func_70301_a(i);
                    if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
                    addToTotals(totals, st);
                }
            }
        } catch (Throwable ignored) {
        }
        return totals;
    }

    static void addToTotals(java.util.Map<String, Integer> totals, ItemStack st) {
        String key = totalsKey(st);
        totals.put(key, (totals.containsKey(key) ? totals.get(key) : 0) + st.field_77994_a);
        for (String tag : ItemClassifier.getOreDictNames(st)) {
            String tkey = "dict:" + tag.toLowerCase();
            totals.put(tkey, (totals.containsKey(tkey) ? totals.get(tkey) : 0) + st.field_77994_a);
        }
        BlockLike bl = toBlockLike(st);
        if (bl != null) {
            for (String tag : ItemClassifier.getOreDictNames(bl.block, bl.meta)) {
                String tkey = "dict:" + tag.toLowerCase();
                totals.put(tkey, (totals.containsKey(tkey) ? totals.get(tkey) : 0) + st.field_77994_a);
            }
        }
    }

    static String totalsKey(ItemStack st) {
        String reg = Item.field_150901_e.func_148750_c(st.func_77973_b());
        int meta = st.func_77960_j();
        return (reg != null ? reg.toLowerCase() : st.func_77973_b().getClass().getName().toLowerCase()) + ":" + meta;
    }

    static BlockLike toBlockLike(ItemStack st) {
        try {
            net.minecraft.block.Block b = net.minecraft.block.Block.func_149634_a(st.func_77973_b());
            if (b == null || b == net.minecraft.init.Blocks.field_150350_a) return null;
            return new BlockLike(b, st.func_77960_j());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static class BlockLike {
        final net.minecraft.block.Block block;
        final int meta;
        BlockLike(net.minecraft.block.Block block, int meta) {
            this.block = block;
            this.meta = meta;
        }
    }

    static IInventory findDroneInventory(World world, EntityPlayerMP player) {
        try {
            if (player != null && world != null) {
                String owner = player.func_70005_c_();
                for (Object o : world.field_147482_g) {
                    if (o == null) continue;
                    String cn = o.getClass().getName();
                    if (!cn.contains("Drone") && !cn.contains("drone")) continue;
                    try {
                        java.lang.reflect.Method m = o.getClass().getMethod("mainInventory");
                        Object inv = m.invoke(o);
                        if (inv instanceof IInventory) return (IInventory) inv;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static MachineRecipeService.Availability assess(InventoryService inventoryService, MachineRecipeService.NormalizedRecipe r, World world, EntityPlayerMP player, int radius) {
        MachineRecipeService.Availability av = new MachineRecipeService.Availability();
        java.util.Map<String, Integer> totals = countAllNearby(inventoryService, world, player, radius);
        int needed = 0;
        int have = 0;
        int miss = 0;
        for (JsonObject in : r.inputs) {
            int amt = in.has("amount") ? in.get("amount").getAsInt() : 1;
            String type = in.has("type") ? in.get("type").getAsString() : "item";
            int got = 0;
            if ("dict".equals(type)) {
                String key = "dict:" + in.get("dict").getAsString().toLowerCase();
                got = totals.containsKey(key) ? totals.get(key) : 0;
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
        av.needed = needed;
        av.available = have;
        av.missing = miss;
        av.sufficient = miss == 0 && !r.inputs.isEmpty();
        return av;
    }

    static List<MachineRecipeService.NormalizedRecipe> rankAndFilter(List<MachineRecipeService.NormalizedRecipe> all, String filter, String wantMachine) {
        List<MachineRecipeService.NormalizedRecipe> scored = new ArrayList<MachineRecipeService.NormalizedRecipe>();
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
        java.util.Collections.sort(scored, new Comparator<MachineRecipeService.NormalizedRecipe>() {
            @Override
            public int compare(MachineRecipeService.NormalizedRecipe a, MachineRecipeService.NormalizedRecipe b) {
                int doc = Integer.compare(b.duration, a.duration);
                if (doc != 0) return doc;
                int pin = Integer.compare(a.inputs.size(), b.inputs.size());
                if (pin != 0) return pin;
                return Long.compare(a.power, b.power);
            }
        });
        return scored;
    }
}
