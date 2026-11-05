package ir.layercloud.cortexdrone.inventory;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.FlightPathfinder;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import lombok.Getter;

/**
 * Core inventory service handling slot transfers, multi-chest discovery,
 * smart slot-swapping withdrawals, deposits, and item vacuuming.
 * Delegates crafting to CraftingService and fluid operations to FluidOpsService.
 */
public class InventoryService {
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final FlightPathfinder pathfinder;
    @Getter
    private final CraftingService craftingService;
    @Getter
    private final FluidOpsService fluidOpsService;
    @Getter
    private final ContainerSearchService containerSearchService;
    private final PlayerInventoryOps playerInventoryOps;
    @Getter
    private final MachineRecipeService machineRecipeService;
    @Getter
    private final MachineCraftExecutor machineCraftExecutor;
    @Getter
    private final AnvilService anvilService;

    public InventoryService(AIDroneManager manager, DroneLogger logger, FlightPathfinder pathfinder) {
        this.manager = manager;
        this.logger = logger;
        this.pathfinder = pathfinder;
        this.craftingService = new CraftingService(logger, this);
        this.fluidOpsService = new FluidOpsService(manager, logger, pathfinder, this);
        this.containerSearchService = new ContainerSearchService(this);
        this.playerInventoryOps = new PlayerInventoryOps(logger);
        this.machineRecipeService = new MachineRecipeService(this);
        this.machineCraftExecutor = new MachineCraftExecutor(manager, logger, pathfinder, this, this.machineRecipeService);
        this.anvilService = new AnvilService(manager, logger, this, this.machineRecipeService);
    }




    public static boolean isInventoryFull(IInventory inv) {
        if (inv == null) return true;
        int size = inv.func_70302_i_();
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st == null || st.field_77994_a < Math.min(inv.func_70297_j_(), st.func_77976_d())) {
                return false;
            }
        }
        return true;
    }

    public static int countInventoryItems(IInventory inv) {
        if (inv == null) return 0;
        int total = 0;
        int size = inv.func_70302_i_();
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st != null && st.field_77994_a > 0) {
                total += st.field_77994_a;
            }
        }
        return total;
    }

    public static String formatInventorySummary(IInventory inv) {
        if (inv == null) return "Empty";
        StringBuilder sb = new StringBuilder();
        int size = inv.func_70302_i_();
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st != null && st.field_77994_a > 0) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("[").append(i + 1).append("] ").append(st.func_82833_r()).append(" x").append(st.field_77994_a);
            }
        }
        return sb.length() == 0 ? "Empty (0/" + size + " slots)" : sb.toString();
    }

    public static int insertIntoInventory(IInventory inv, ItemStack stack) {
        if (inv == null || stack == null || stack.field_77994_a <= 0) return 0;
        int inserted = 0;
        int size = inv.func_70302_i_();

        for (int i = 0; i < size && stack.field_77994_a > 0; i++) {
            ItemStack existing = inv.func_70301_a(i);
            if (existing != null && existing.func_77969_a(stack) && ItemStack.func_77970_a(existing, stack)) {
                int max = Math.min(inv.func_70297_j_(), existing.func_77976_d());
                int space = max - existing.field_77994_a;
                if (space > 0) {
                    int add = Math.min(space, stack.field_77994_a);
                    ItemStack updated = existing.func_77946_l();
                    updated.field_77994_a += add;
                    inv.func_70299_a(i, updated);
                    stack.field_77994_a -= add;
                    inserted += add;
                }
            }
        }

        for (int i = 0; i < size && stack.field_77994_a > 0; i++) {
            ItemStack existing = inv.func_70301_a(i);
            if (existing == null) {
                int max = Math.min(inv.func_70297_j_(), stack.func_77976_d());
                int add = Math.min(max, stack.field_77994_a);
                ItemStack copy = stack.func_77946_l();
                copy.field_77994_a = add;
                inv.func_70299_a(i, copy);
                stack.field_77994_a -= add;
                inserted += add;
            }
        }

        if (inserted > 0) {
            inv.func_70296_d();
        }
        return inserted;
    }

    public static int findPlaceableItemSlot(IInventory inv, String filter) {
        if (inv == null) return -1;
        String f = (filter == null || filter.trim().isEmpty()) ? "any" : filter.trim().toLowerCase();
        boolean matchAnyBlock = f.equals("any") || f.equals("block") || f.equals("solid");
        boolean isBucketQuery = f.contains("bucket");
        int size = inv.func_70302_i_();
        int bestSlot = -1;
        int bestScore = 0;
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
            Item item = st.func_77973_b();
            if (!isBucketQuery) {
                Block b = Block.func_149634_a(item);
                if (b == null || b == Blocks.field_150350_a || ItemClassifier.isLiquidBlock(b)) {
                    continue;
                }
            }
            if (matchAnyBlock) {
                return i;
            }
            int score = ItemClassifier.scoreBlockOrItemMatch(st.func_82833_r(), ItemClassifier.getItemIdentifier(st), st.func_77960_j(), f);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    public static boolean consumeItemMatching(IInventory inv, String... keywords) {
        if (inv == null) return false;
        int size = inv.func_70302_i_();
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st != null && st.field_77994_a > 0 && st.func_77973_b() != null) {
                String combined = ItemClassifier.getItemIdentifier(st);
                for (String kw : keywords) {
                    if (combined.contains(kw.toLowerCase())) {
                        inv.func_70298_a(i, 1);
                        inv.func_70296_d();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static void syncPlayerInventory(EntityPlayerMP player) {
        if (player == null) return;
        try {
            if (player.field_71071_by != null) {
                player.field_71071_by.func_70296_d();
            }
            if (player.field_71069_bz != null) {
                player.field_71069_bz.func_75142_b();
            }
            if (player.field_71070_bA != null && player.field_71070_bA != player.field_71069_bz) {
                player.field_71070_bA.func_75142_b();
            }
        } catch (Throwable ignored) {
        }
    }

    public List<TileEntity> findAllInventoryTiles(final World world, final double x, final double y, final double z, int radius) {
        int cx = MathHelper.func_76128_c(x);
        int cy = MathHelper.func_76128_c(y);
        int cz = MathHelper.func_76128_c(z);
        List<TileEntity> tiles = new ArrayList<TileEntity>();
        double r2 = (double) radius * radius;

        // Fast path: iterate already-loaded tile entities (world.field_147482_g list) and distance-filter,
        // avoiding a dense block-probe cube via func_147438_o, which both costs
        // main-thread time AND loads chunks to materialize TileEntity. Loaded tile entities are the
        // only ones whose inventories are readable anyway — unloaded-chunk chests cannot be opened.
        if (world.field_147482_g != null) {
            for (Object o : world.field_147482_g) {
                if (!(o instanceof TileEntity)) continue;
                TileEntity te = (TileEntity) o;
                int bx = te.field_145851_c;
                int by = te.field_145848_d;
                int bz = te.field_145849_e;
                double dx = bx + 0.5 - x;
                double dy = by + 0.5 - y;
                double dz = bz + 0.5 - z;
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                if (ItemClassifier.isStorageChestTile(world, te)) {
                    tiles.add(te);
                }
            }
        }

        Collections.sort(tiles, new Comparator<TileEntity>() {
            @Override
            public int compare(TileEntity a, TileEntity b) {
                boolean ca = a instanceof TileEntityChest;
                boolean cb = b instanceof TileEntityChest;
                if (ca != cb) return ca ? -1 : 1;
                double da = (a.field_145851_c + 0.5 - x) * (a.field_145851_c + 0.5 - x)
                    + (a.field_145848_d + 0.5 - y) * (a.field_145848_d + 0.5 - y)
                    + (a.field_145849_e + 0.5 - z) * (a.field_145849_e + 0.5 - z);
                double db = (b.field_145851_c + 0.5 - x) * (b.field_145851_c + 0.5 - x)
                    + (b.field_145848_d + 0.5 - y) * (b.field_145848_d + 0.5 - y)
                    + (b.field_145849_e + 0.5 - z) * (b.field_145849_e + 0.5 - z);
                return Double.compare(da, db);
            }
        });
        return tiles;
    }

    public static boolean canInventoryAcceptAny(IInventory targetInv, IInventory srcInv) {
        if (targetInv == null) return false;
        int tSize = targetInv.func_70302_i_();
        for (int i = 0; i < tSize; i++) {
            if (targetInv.func_70301_a(i) == null) return true;
        }
        if (srcInv == null) return false;
        for (int s = 0; s < srcInv.func_70302_i_(); s++) {
            ItemStack st = srcInv.func_70301_a(s);
            if (st == null || st.field_77994_a <= 0) continue;
            for (int i = 0; i < tSize; i++) {
                ItemStack existing = targetInv.func_70301_a(i);
                if (existing != null && existing.func_77969_a(st) && ItemStack.func_77970_a(existing, st)) {
                    int max = Math.min(targetInv.func_70297_j_(), existing.func_77976_d());
                    if (existing.field_77994_a < max) return true;
                }
            }
        }
        return false;
    }

    public TileEntity findNearestInventoryWithSpace(World world, double x, double y, double z, int radius, IInventory srcInv) {
        List<TileEntity> tiles = findAllInventoryTiles(world, x, y, z, radius);
        for (TileEntity te : tiles) {
            if (te instanceof IInventory && canInventoryAcceptAny((IInventory) te, srcInv)) {
                return te;
            }
        }
        return null;
    }

    public TileEntity findNearestInventoryTile(World world, double x, double y, double z, int radius) {
        List<TileEntity> tiles = findAllInventoryTiles(world, x, y, z, radius);
        return tiles.isEmpty() ? null : tiles.get(0);
    }

    public static int withdrawFromInventory(IInventory src, IInventory dst, String filter, int maxCount) {
        if (src == null || dst == null) return 0;
        String f = (filter == null || filter.trim().isEmpty()) ? "any" : filter.trim().toLowerCase();
        boolean matchAny = f.equals("any") || f.equals("all") || f.equals("*") || f.equals("block") || f.equals("solid");
        int moved = 0;
        for (int i = 0; i < src.func_70302_i_() && moved < maxCount; i++) {
            ItemStack st = src.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
            int meta = st.func_77960_j();
            String disp = st.func_82833_r();
            String combined = ItemClassifier.getItemIdentifier(st);
            boolean ok = matchAny
                ? (f.equals("all") || f.equals("*") || st.func_77973_b() instanceof ItemBlock)
                : ItemClassifier.matchesBlockOrItemName(disp, combined, meta, f);
            if (!ok) continue;

            int take = Math.min(st.field_77994_a, maxCount - moved);
            ItemStack toInsert = st.func_77946_l();
            toInsert.field_77994_a = take;
            int ins = insertIntoInventory(dst, toInsert);
            if (ins > 0) {
                src.func_70298_a(i, ins);
                moved += ins;
            }
        }
        if (moved > 0) {
            src.func_70296_d();
            dst.func_70296_d();
        }
        return moved;
    }

    public int withdrawMatchingFromNearbyChests(World world, DroneController ctrl, EntityPlayerMP owner, String filter, int maxCount) {
        if (world == null || ctrl == null || ctrl.drone == null) return 0;
        String resolved = ItemClassifier.resolveHeldFilter(owner, filter);
        List<TileEntity> tiles = findAllInventoryTiles(world, ctrl.drone.field_70165_t, ctrl.drone.field_70163_u, ctrl.drone.field_70161_v, 28);
        if (owner != null && owner.field_70170_p == world) {
            List<TileEntity> ownerTiles = findAllInventoryTiles(world, owner.field_70165_t, owner.field_70163_u, owner.field_70161_v, 28);
            for (TileEntity ote : ownerTiles) {
                if (!tiles.contains(ote)) {
                    tiles.add(ote);
                }
            }
        }
        int totalMoved = 0;
        IInventory dst = ctrl.drone.mainInventory();

        boolean hasFreeSlot = false;
        for (int i = 0; i < dst.func_70302_i_(); i++) {
            ItemStack st = dst.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0) {
                hasFreeSlot = true;
                break;
            }
            if (ItemClassifier.matchesBlockOrItemName(st.func_82833_r(), ItemClassifier.getItemIdentifier(st), st.func_77960_j(), resolved)
                && st.field_77994_a < st.func_77976_d()) {
                hasFreeSlot = true;
                break;
            }
        }
        if (!hasFreeSlot) {
            for (int i = dst.func_70302_i_() - 1; i >= 0; i--) {
                ItemStack st = dst.func_70301_a(i);
                if (st == null) continue;
                if (!ItemClassifier.matchesBlockOrItemName(st.func_82833_r(), ItemClassifier.getItemIdentifier(st), st.func_77960_j(), resolved)) {
                    boolean swapped = false;
                    for (TileEntity te : tiles) {
                        if (te instanceof IInventory) {
                            ItemStack copy = st.func_77946_l();
                            int ins = insertIntoInventory((IInventory) te, copy);
                            if (ins == st.field_77994_a) {
                                dst.func_70299_a(i, null);
                                dst.func_70296_d();
                                ((IInventory) te).func_70296_d();
                                swapped = true;
                                break;
                            }
                        }
                    }
                    if (!swapped && owner != null && owner.field_71071_by != null && owner.field_71071_by.func_70441_a(st.func_77946_l())) {
                        dst.func_70299_a(i, null);
                        dst.func_70296_d();
                        syncPlayerInventory(owner);
                        swapped = true;
                    }
                    if (swapped) break;
                }
            }
        }

        for (TileEntity te : tiles) {
            if (totalMoved >= maxCount || isInventoryFull(dst)) break;
            if (te instanceof IInventory) {
                totalMoved += withdrawFromInventory((IInventory) te, dst, resolved, maxCount - totalMoved);
            }
        }
        if (totalMoved > 0) {
            this.logger.log("INV", ctrl, "Withdrew " + totalMoved + " item(s) matching '" + resolved + "' from nearby chest(s)", "");
        }
        return totalMoved;
    }

    public int depositDroneItemsIntoTile(Drone d, TileEntity te) {
        if (!(te instanceof IInventory)) return 0;
        IInventory droneInv = d.mainInventory();
        IInventory targetInv = (IInventory) te;
        if (droneInv == null) return 0;

        int totalDeposited = 0;
        int size = droneInv.func_70302_i_();
        for (int i = 0; i < size; i++) {
            ItemStack st = droneInv.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0) continue;
            ItemStack working = st.func_77946_l();
            totalDeposited += insertIntoInventory(targetInv, working);

            if (working.field_77994_a > 0 && te instanceof TileEntityChest) {
                TileEntityChest tc = (TileEntityChest) te;
                tc.func_145979_i();
                TileEntityChest[] adj = new TileEntityChest[]{
                    tc.field_145992_i, tc.field_145990_j, tc.field_145991_k, tc.field_145988_l
                };
                for (TileEntityChest a : adj) {
                    if (a != null && working.field_77994_a > 0) {
                        totalDeposited += insertIntoInventory(a, working);
                    }
                }
            }

            if (working.field_77994_a <= 0) {
                droneInv.func_70299_a(i, null);
            } else {
                droneInv.func_70299_a(i, working);
            }
        }
        droneInv.func_70296_d();
        targetInv.func_70296_d();

        if (countInventoryItems(droneInv) > 0 && d.field_70170_p != null) {
            List<TileEntity> nearby = findAllInventoryTiles(
                d.field_70170_p,
                te.field_145851_c + 0.5,
                te.field_145848_d + 0.5,
                te.field_145849_e + 0.5,
                8
            );
            for (TileEntity other : nearby) {
                if (other == te || !(other instanceof IInventory)) continue;
                if (countInventoryItems(droneInv) <= 0) break;
                IInventory oInv = (IInventory) other;
                for (int i = 0; i < size; i++) {
                    ItemStack st = droneInv.func_70301_a(i);
                    if (st == null || st.field_77994_a <= 0) continue;
                    ItemStack working = st.func_77946_l();
                    int ins = insertIntoInventory(oInv, working);
                    if (ins > 0) {
                        totalDeposited += ins;
                        droneInv.func_70299_a(i, working.field_77994_a > 0 ? working : null);
                        oInv.func_70296_d();
                    }
                }
            }
            droneInv.func_70296_d();
        }
        return totalDeposited;
    }

    public int takeFromPlayerInventory(DroneController ctrl, EntityPlayerMP player, String filter, int maxCount) {
        return this.playerInventoryOps.takeFromPlayerInventory(ctrl, player, filter, maxCount);
    }

    public int giveToPlayerInventory(DroneController ctrl, EntityPlayerMP player, String filter, int maxCount) {
        return this.playerInventoryOps.giveToPlayerInventory(ctrl, player, filter, maxCount);
    }

    public int moveInventorySlot(IInventory srcInv, int srcSlot, String itemFilter, IInventory dstInv, int dstSlot, int maxCount, EntityPlayerMP syncPlayer) {
        return this.playerInventoryOps.moveInventorySlot(srcInv, srcSlot, itemFilter, dstInv, dstSlot, maxCount, syncPlayer);
    }

    public static void suckNearbyItems(Drone d, double x, double y, double z, double radius) {
        World world = d.field_70170_p;
        IInventory inv = d.mainInventory();
        if (world == null || inv == null) return;
        AxisAlignedBB box = AxisAlignedBB.func_72330_a(
            x - radius, y - radius, z - radius,
            x + radius, y + radius, z + radius
        );
        List<?> items = world.func_72872_a(EntityItem.class, box);
        if (items == null || items.isEmpty()) return;

        boolean pickedAny = false;
        for (Object o : items) {
            if (o instanceof EntityItem) {
                EntityItem ei = (EntityItem) o;
                if (ei.field_70128_L) continue;
                ItemStack st = ei.func_92059_d();
                if (st == null || st.field_77994_a <= 0) continue;
                int before = st.field_77994_a;
                insertIntoInventory(inv, st);
                if (st.field_77994_a <= 0) {
                    ei.func_70106_y();
                    pickedAny = true;
                } else if (st.field_77994_a != before) {
                    ei.func_92058_a(st);
                    pickedAny = true;
                }
            }
        }
        if (pickedAny) {
            world.func_72956_a(d, "random.pop", 0.25f, 1.6f);
        }
    }

    public static int dropAllDroneItemsAt(Drone d, World w, double x, double y, double z) {
        IInventory inv = d.mainInventory();
        if (inv == null) return 0;
        int totalCount = 0;
        int size = inv.func_70302_i_();
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st != null && st.field_77994_a > 0) {
                totalCount += st.field_77994_a;
                EntityItem ei = new EntityItem(w, x, y, z, st.func_77946_l());
                ei.field_145804_b = 0;
                w.func_72838_d(ei);
                inv.func_70299_a(i, null);
            }
        }
        inv.func_70296_d();
        return totalCount;
    }

    // ==================== Delegated Fluid & Crafting Operations ====================

    public boolean scoopLiquidAt(World world, DroneController ctrl, EntityPlayerMP owner, int x, int y, int z) {
        return this.fluidOpsService.scoopLiquidAt(world, ctrl, owner, x, y, z);
    }

    public boolean placeLiquidFromBucket(World world, DroneController ctrl, EntityPlayerMP owner, int x, int y, int z, String type) {
        return this.fluidOpsService.placeLiquidFromBucket(world, ctrl, owner, x, y, z, type);
    }

    public int startScoopLiquidTask(DroneController ctrl, EntityPlayerMP player, String rawType, int maxCount) {
        return this.fluidOpsService.startScoopLiquidTask(ctrl, player, rawType, maxCount);
    }

    public void tickScoopBehavior(DroneController ctrl, EntityPlayerMP owner) {
        this.fluidOpsService.tickScoopBehavior(ctrl, owner);
    }

    public JsonObject getRecipesForFilter(String filter) {
        return this.craftingService.getRecipesForFilter(filter);
    }

    public JsonObject craftItem(World world, DroneController ctrl, EntityPlayerMP owner, String targetFilter, int wantedCount) {
        return this.craftingService.craftItem(world, ctrl, owner, targetFilter, wantedCount);
    }



}
