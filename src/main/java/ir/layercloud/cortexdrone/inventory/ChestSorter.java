package ir.layercloud.cortexdrone.inventory;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.World;

@RequiredArgsConstructor
public class ChestSorter {
    private final DroneLogger logger;
    private final InventoryService inventoryService;

    public JsonObject sortNearbyChests(World world, DroneController ctrl, EntityPlayerMP player, int radius, String mode) {
        int r = Math.max(4, Math.min(48, radius));
        double refX = player != null && player.field_70170_p == world ? player.field_70165_t : ctrl.drone.field_70165_t;
        double refY = player != null && player.field_70170_p == world ? player.field_70163_u : ctrl.drone.field_70163_u;
        double refZ = player != null && player.field_70170_p == world ? player.field_70161_v : ctrl.drone.field_70161_v;

        List<TileEntity> chests = this.inventoryService.findAllInventoryTiles(world, refX, refY, refZ, r);
        List<TileEntity> droneChests = this.inventoryService.findAllInventoryTiles(world, ctrl.drone.field_70165_t, ctrl.drone.field_70163_u, ctrl.drone.field_70161_v, r);
        for (TileEntity te : droneChests) {
            if (!chests.contains(te)) {
                chests.add(te);
            }
        }
        return sortContainerList(world, ctrl, chests, mode);
    }

    public JsonObject sortContainerList(World world, DroneController ctrl, List<TileEntity> chests, String mode) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        int stacksMerged = 0;
        boolean consolidateAcrossChests = mode == null || !mode.equalsIgnoreCase("local");

        if (consolidateAcrossChests && chests.size() > 1) {
            for (int c1 = 0; c1 < chests.size(); c1++) {
                IInventory inv1 = (IInventory) chests.get(c1);
                for (int s1 = 0; s1 < inv1.func_70302_i_(); s1++) {
                    ItemStack st1 = inv1.func_70301_a(s1);
                    if (st1 == null || st1.field_77994_a <= 0) continue;
                    int max1 = Math.min(inv1.func_70297_j_(), st1.func_77976_d());
                    if (st1.field_77994_a >= max1) continue;

                    for (int c2 = c1 + 1; c2 < chests.size() && st1.field_77994_a < max1; c2++) {
                        IInventory inv2 = (IInventory) chests.get(c2);
                        for (int s2 = 0; s2 < inv2.func_70302_i_() && st1.field_77994_a < max1; s2++) {
                            ItemStack st2 = inv2.func_70301_a(s2);
                            if (st2 == null || st2.field_77994_a <= 0) continue;
                            if (st1.func_77969_a(st2) && ItemStack.func_77970_a(st1, st2)) {
                                int add = Math.min(max1 - st1.field_77994_a, st2.field_77994_a);
                                if (add > 0) {
                                    st1.field_77994_a += add;
                                    st2.field_77994_a -= add;
                                    inv1.func_70299_a(s1, st1);
                                    inv2.func_70299_a(s2, st2.field_77994_a > 0 ? st2 : null);
                                    stacksMerged++;
                                }
                            }
                        }
                    }
                }
            }
        }

        int chestsSorted = 0;
        int totalStacks = 0;
        for (TileEntity te : chests) {
            if (!(te instanceof IInventory)) continue;
            IInventory inv = (IInventory) te;
            int size = inv.func_70302_i_();
            int maxSlotStack = inv.func_70297_j_();
            List<ItemStack> merged = new ArrayList<ItemStack>();

            for (int i = 0; i < size; i++) {
                ItemStack st = inv.func_70301_a(i);
                if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
                ItemStack working = st.func_77946_l();
                for (ItemStack ex : merged) {
                    if (working.field_77994_a <= 0) break;
                    if (ex.func_77969_a(working) && ItemStack.func_77970_a(ex, working)) {
                        int lim = Math.min(maxSlotStack, ex.func_77976_d());
                        int space = lim - ex.field_77994_a;
                        if (space > 0) {
                            int add = Math.min(space, working.field_77994_a);
                            ex.field_77994_a += add;
                            working.field_77994_a -= add;
                            stacksMerged++;
                        }
                    }
                }
                if (working.field_77994_a > 0) {
                    merged.add(working);
                }
            }

            if (merged.isEmpty()) continue;

            Collections.sort(merged, new Comparator<ItemStack>() {
                @Override
                public int compare(ItemStack a, ItemStack b) {
                    int catA = ItemClassifier.getItemCategoryOrder(a);
                    int catB = ItemClassifier.getItemCategoryOrder(b);
                    if (catA != catB) return Integer.compare(catA, catB);
                    String nameA = a.func_82833_r() != null ? a.func_82833_r() : "";
                    String nameB = b.func_82833_r() != null ? b.func_82833_r() : "";
                    int cmpName = nameA.compareToIgnoreCase(nameB);
                    if (cmpName != 0) return cmpName;
                    int metaCmp = Integer.compare(a.func_77960_j(), b.func_77960_j());
                    if (metaCmp != 0) return metaCmp;
                    return Integer.compare(b.field_77994_a, a.field_77994_a);
                }
            });

            for (int i = 0; i < size; i++) {
                inv.func_70299_a(i, i < merged.size() ? merged.get(i) : null);
            }
            inv.func_70296_d();
            chestsSorted++;
            totalStacks += merged.size();
        }

        if (!chests.isEmpty()) {
            TileEntity first = chests.get(0);
            world.func_72908_a(first.field_145851_c + 0.5, first.field_145848_d + 0.5, first.field_145849_e + 0.5, "random.chestclosed", 0.6f, 1.15f);
        }
        this.logger.log("INV", ctrl, String.format("Sorted %d container(s) (merged %d partial stacks, %d total stacks)",
            chestsSorted, stacksMerged, totalStacks), "");
        res.addProperty("chestsSorted", chestsSorted);
        res.addProperty("stacksMerged", stacksMerged);
        res.addProperty("totalStacks", totalStacks);
        return res;
    }

    public JsonObject transferContainers(
        World world,
        DroneController ctrl,
        EntityPlayerMP player,
        String fromFilter,
        String toFilter,
        int radius,
        boolean autoCreateChests,
        boolean sortAfter
    ) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        int r = Math.max(4, Math.min(48, radius));
        String srcF = (fromFilter == null || fromFilter.trim().isEmpty()) ? "crate" : fromFilter.trim().toLowerCase();
        String dstF = (toFilter == null || toFilter.trim().isEmpty()) ? "chest" : toFilter.trim().toLowerCase();

        double refX = player != null && player.field_70170_p == world ? player.field_70165_t : ctrl.drone.field_70165_t;
        double refY = player != null && player.field_70170_p == world ? player.field_70163_u : ctrl.drone.field_70163_u;
        double refZ = player != null && player.field_70170_p == world ? player.field_70161_v : ctrl.drone.field_70161_v;

        List<TileEntity> allTiles = this.inventoryService.findAllInventoryTiles(world, refX, refY, refZ, r);
        List<TileEntity> sources = new ArrayList<TileEntity>();
        List<TileEntity> targets = new ArrayList<TileEntity>();

        for (TileEntity te : allTiles) {
            Block b = world.func_147439_a(te.field_145851_c, te.field_145848_d, te.field_145849_e);
            int meta = world.func_72805_g(te.field_145851_c, te.field_145848_d, te.field_145849_e);
            String disp = ItemClassifier.getBlockDisplayName(b, meta);
            String id = ItemClassifier.getBlockIdentifier(b, meta);
            boolean isCrate = id.contains("crate");
            boolean isChest = (te instanceof TileEntityChest) || (id.contains("chest") && !isCrate);

            if ((srcF.contains("crate") && isCrate) || (!srcF.contains("crate") && ItemClassifier.matchesBlockOrItemName(disp, id, meta, srcF))) {
                sources.add(te);
            } else if ((dstF.contains("chest") && isChest) || (!dstF.contains("chest") && ItemClassifier.matchesBlockOrItemName(disp, id, meta, dstF))) {
                targets.add(te);
            }
        }

        int itemsMoved = 0;
        int stacksMoved = 0;
        int chestsCreated = 0;

        for (TileEntity srcTe : sources) {
            IInventory srcInv = (IInventory) srcTe;
            for (int slot = 0; slot < srcInv.func_70302_i_(); slot++) {
                ItemStack st = srcInv.func_70301_a(slot);
                if (st == null || st.field_77994_a <= 0) continue;

                ItemStack working = st.func_77946_l();
                for (TileEntity dstTe : targets) {
                    if (working.field_77994_a <= 0) break;
                    int ins = InventoryService.insertIntoInventory((IInventory) dstTe, working);
                    if (ins > 0) {
                        itemsMoved += ins;
                    }
                }

                // If target chests are full and autoCreateChests is enabled, craft/place a new chest!
                while (working.field_77994_a > 0 && autoCreateChests) {
                    TileEntity newChest = tryCreateAndPlaceChestNearCluster(world, ctrl, player, allTiles, targets);
                    if (newChest == null) break;
                    chestsCreated++;
                    targets.add(newChest);
                    allTiles.add(newChest);
                    int ins = InventoryService.insertIntoInventory((IInventory) newChest, working);
                    if (ins > 0) {
                        itemsMoved += ins;
                    } else {
                        break;
                    }
                }

                if (working.field_77994_a < st.field_77994_a) {
                    stacksMoved++;
                    srcInv.func_70299_a(slot, working.field_77994_a > 0 ? working : null);
                }
            }
            srcInv.func_70296_d();
        }

        res.addProperty("sourceContainers", sources.size());
        res.addProperty("targetContainers", targets.size());
        res.addProperty("itemsMoved", itemsMoved);
        res.addProperty("stacksMoved", stacksMoved);
        res.addProperty("chestsCreated", chestsCreated);

        if (sortAfter && !targets.isEmpty()) {
            JsonObject sortRes = sortContainerList(world, ctrl, targets, "all");
            res.addProperty("chestsSorted", sortRes.get("chestsSorted").getAsInt());
            res.addProperty("stacksMerged", sortRes.get("stacksMerged").getAsInt());
        }

        this.logger.log("INV", ctrl,
            String.format("Transferred %d item(s) (%d stacks) from '%s' to '%s' (created %d chest(s))",
                itemsMoved, stacksMoved, srcF, dstF, chestsCreated),
            "");
        return res;
    }

    private TileEntity tryCreateAndPlaceChestNearCluster(
        World world,
        DroneController ctrl,
        EntityPlayerMP player,
        List<TileEntity> allContainers,
        List<TileEntity> targetChests
    ) {
        if (Blocks.field_150486_ae == null) return null;

        // 1) Check if we have a Chest item or 8 Planks or 2 Wood logs across drone, player, or containers
        boolean consumedMaterial = consumeChestOrCraftMaterial(ctrl.drone.mainInventory(), null);
        if (!consumedMaterial && player != null && player.field_71071_by != null) {
            consumedMaterial = consumeChestOrCraftMaterial(player.field_71071_by, player);
        }
        if (!consumedMaterial) {
            for (TileEntity te : allContainers) {
                if (te instanceof IInventory && consumeChestOrCraftMaterial((IInventory) te, null)) {
                    consumedMaterial = true;
                    break;
                }
            }
        }
        if (!consumedMaterial) return null;

        // 2) Find an open replaceable spot next to existing targetChests
        List<TileEntity> anchors = !targetChests.isEmpty() ? targetChests : allContainers;
        for (TileEntity anchor : anchors) {
            int ax = anchor.field_145851_c;
            int ay = anchor.field_145848_d;
            int az = anchor.field_145849_e;
            for (int dy = 0; dy <= 2; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        int px = ax + dx;
                        int py = ay + dy;
                        int pz = az + dz;
                        if (!world.func_147437_c(px, py, pz)) continue;
                        if (world.func_147437_c(px, py - 1, pz)) continue;
                        // Avoid placing directly adjacent to an already-double chest
                        if (wouldCreateTripleChest(world, px, py, pz)) continue;
                        if (world.func_147465_d(px, py, pz, Blocks.field_150486_ae, 0, 3)) {
                            TileEntity created = world.func_147438_o(px, py, pz);
                            if (created instanceof IInventory) {
                                return created;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean wouldCreateTripleChest(World world, int x, int y, int z) {
        int adjChests = 0;
        int[][] dirs = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            Block b = world.func_147439_a(x + d[0], y, z + d[1]);
            if (b == Blocks.field_150486_ae) {
                adjChests++;
                for (int[] d2 : dirs) {
                    if (world.func_147439_a(x + d[0] + d2[0], y, z + d[1] + d2[1]) == Blocks.field_150486_ae) {
                        return true;
                    }
                }
            }
        }
        return adjChests > 1;
    }

    private static boolean consumeChestOrCraftMaterial(IInventory inv, EntityPlayerMP syncPlayer) {
        if (inv == null) return false;
        int size = syncPlayer != null ? Math.min(36, inv.func_70302_i_()) : inv.func_70302_i_();

        // 1) Look for an existing Chest item
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
            Block b = Block.func_149634_a(st.func_77973_b());
            if (b == Blocks.field_150486_ae) {
                inv.func_70298_a(i, 1);
                inv.func_70296_d();
                InventoryService.syncPlayerInventory(syncPlayer);
                return true;
            }
        }

        // 2) Look for >= 8 Planks or >= 2 Wood logs to craft 1 Chest
        int planksAvail = 0;
        int logsAvail = 0;
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0) continue;
            if (ItemClassifier.hasOreDictPrefix(st, "plankWood")) {
                planksAvail += st.field_77994_a;
            } else if (ItemClassifier.hasOreDictPrefix(st, "logWood") || ItemClassifier.isLogBlock(Block.func_149634_a(st.func_77973_b()))) {
                logsAvail += st.field_77994_a;
            }
        }

        if (planksAvail >= 8) {
            int rem = 8;
            for (int i = 0; i < size && rem > 0; i++) {
                ItemStack st = inv.func_70301_a(i);
                if (st != null && st.field_77994_a > 0 && ItemClassifier.hasOreDictPrefix(st, "plankWood")) {
                    int take = Math.min(rem, st.field_77994_a);
                    inv.func_70298_a(i, take);
                    rem -= take;
                }
            }
            inv.func_70296_d();
            InventoryService.syncPlayerInventory(syncPlayer);
            return true;
        }

        if (logsAvail >= 2) {
            int rem = 2;
            for (int i = 0; i < size && rem > 0; i++) {
                ItemStack st = inv.func_70301_a(i);
                if (st != null && st.field_77994_a > 0
                    && (ItemClassifier.hasOreDictPrefix(st, "logWood") || ItemClassifier.isLogBlock(Block.func_149634_a(st.func_77973_b())))) {
                    int take = Math.min(rem, st.field_77994_a);
                    inv.func_70298_a(i, take);
                    rem -= take;
                }
            }
            inv.func_70296_d();
            InventoryService.syncPlayerInventory(syncPlayer);
            return true;
        }

        return false;
    }
}
