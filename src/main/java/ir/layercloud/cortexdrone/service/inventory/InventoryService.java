package ir.layercloud.cortexdrone.service.inventory;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.service.machine.MachineRecipeService;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.Getter;
import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Core inventory service handling slot transfers, multi-chest discovery,
 * smart slot-swapping withdrawals, deposits, and item vacuuming.
 * Delegates crafting and fluid scooping to focused helpers.
 */
public class InventoryService {
    private final DroneLogger logger;
    @Getter
    private final CraftingService craftingService;
    @Getter
    private final ContainerSearchService containerSearchService;
    private final PlayerInventoryOps playerInventoryOps;
    @Getter
    private final MachineRecipeService machineRecipeService;

    public InventoryService(DroneLogger logger) {
        this.logger = logger;
        this.craftingService = new CraftingService(logger, this);
        this.containerSearchService = new ContainerSearchService(this);
        this.playerInventoryOps = new PlayerInventoryOps(logger);
        this.machineRecipeService = new MachineRecipeService(this);
    }

    public static boolean isInventoryFull(IInventory inv) {
        if (inv == null) return true;
        int size = inv.getSizeInventory();
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st == null || st.stackSize < Math.min(inv.getInventoryStackLimit(), st.getMaxStackSize())) {
                return false;
            }
        }
        return true;
    }

    public static int countInventoryItems(IInventory inv) {
        if (inv == null) return 0;
        int total = 0;
        int size = inv.getSizeInventory();
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st != null && st.stackSize > 0) {
                total += st.stackSize;
            }
        }
        return total;
    }

    public static String formatInventorySummary(IInventory inv) {
        if (inv == null) return "Empty";
        StringBuilder sb = new StringBuilder();
        int size = inv.getSizeInventory();
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st != null && st.stackSize > 0) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("[").append(i + 1).append("] ").append(st.getDisplayName()).append(" x").append(st.stackSize);
            }
        }
        return sb.length() == 0 ? "Empty (0/" + size + " slots)" : sb.toString();
    }

    public static int insertIntoInventory(IInventory inv, ItemStack stack) {
        if (inv == null || stack == null || stack.stackSize <= 0) return 0;
        int inserted = 0;
        int size = inv.getSizeInventory();

        for (int i = 0; i < size && stack.stackSize > 0; i++) {
            ItemStack existing = inv.getStackInSlot(i);
            if (existing != null && existing.isItemEqual(stack) && ItemStack.areItemStackTagsEqual(existing, stack)) {
                int max = Math.min(inv.getInventoryStackLimit(), existing.getMaxStackSize());
                int space = max - existing.stackSize;
                if (space > 0) {
                    int add = Math.min(space, stack.stackSize);
                    ItemStack updated = existing.copy();
                    updated.stackSize += add;
                    inv.setInventorySlotContents(i, updated);
                    stack.stackSize -= add;
                    inserted += add;
                }
            }
        }

        for (int i = 0; i < size && stack.stackSize > 0; i++) {
            ItemStack existing = inv.getStackInSlot(i);
            if (existing == null) {
                int max = Math.min(inv.getInventoryStackLimit(), stack.getMaxStackSize());
                int add = Math.min(max, stack.stackSize);
                ItemStack copy = stack.copy();
                copy.stackSize = add;
                inv.setInventorySlotContents(i, copy);
                stack.stackSize -= add;
                inserted += add;
            }
        }

        if (inserted > 0) {
            inv.markDirty();
        }
        return inserted;
    }

    public static int findPlaceableItemSlot(IInventory inv, String filter) {
        if (inv == null) return -1;
        String f = (filter == null || filter.trim().isEmpty()) ? "any" : filter.trim().toLowerCase();
        boolean matchAnyBlock = f.equals("any") || f.equals("block") || f.equals("solid");
        boolean isBucketQuery = f.contains("bucket");
        int size = inv.getSizeInventory();
        int bestSlot = -1;
        int bestScore = 0;
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
            Item item = st.getItem();
            if (!isBucketQuery) {
                Block b = Block.getBlockFromItem(item);
                if (b == null || b == Blocks.air || ItemClassifier.isLiquidBlock(b)) {
                    continue;
                }
            }
            if (matchAnyBlock) {
                return i;
            }
            int score = ItemClassifier.scoreBlockOrItemMatch(st.getDisplayName(), ItemClassifier.getItemIdentifier(st), st.getItemDamage(), f);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    public static void syncPlayerInventory(EntityPlayerMP player) {
        if (player == null) return;
        try {
            if (player.inventory != null) {
                player.inventory.markDirty();
            }
            if (player.inventoryContainer != null) {
                player.inventoryContainer.detectAndSendChanges();
            }
            if (player.openContainer != null && player.openContainer != player.inventoryContainer) {
                player.openContainer.detectAndSendChanges();
            }
        } catch (Throwable ignored) {
        }
    }

    public List<TileEntity> findAllInventoryTiles(final World world, final double x, final double y, final double z, int radius) {
        List<TileEntity> tiles = new ArrayList<>();
        double r2 = (double) radius * radius;

        // Fast path: iterate already-loaded tile entities (world.loadedTileEntityList list) and distance-filter,
        // avoiding a dense block-probe cube via getTileEntity, which both costs
        // main-thread time AND loads chunks to materialize TileEntity. Loaded tile entities are the
        // only ones whose inventories are readable anyway — unloaded-chunk chests cannot be opened.
        if (world.loadedTileEntityList != null) {
            for (Object o : world.loadedTileEntityList) {
                if (!(o instanceof TileEntity)) continue;
                TileEntity te = (TileEntity) o;
                int bx = te.xCoord;
                int by = te.yCoord;
                int bz = te.zCoord;
                double dx = bx + 0.5 - x;
                double dy = by + 0.5 - y;
                double dz = bz + 0.5 - z;
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                if (ItemClassifier.isStorageChestTile(world, te)) {
                    tiles.add(te);
                }
            }
        }

        tiles.sort((a, b) -> {
            boolean ca = a instanceof TileEntityChest;
            boolean cb = b instanceof TileEntityChest;
            if (ca != cb) return ca ? -1 : 1;
            double da = (a.xCoord + 0.5 - x) * (a.xCoord + 0.5 - x)
                    + (a.yCoord + 0.5 - y) * (a.yCoord + 0.5 - y)
                    + (a.zCoord + 0.5 - z) * (a.zCoord + 0.5 - z);
            double db = (b.xCoord + 0.5 - x) * (b.xCoord + 0.5 - x)
                    + (b.yCoord + 0.5 - y) * (b.yCoord + 0.5 - y)
                    + (b.zCoord + 0.5 - z) * (b.zCoord + 0.5 - z);
            return Double.compare(da, db);
        });
        return tiles;
    }

    public static boolean canInventoryAcceptAny(IInventory targetInv, IInventory srcInv) {
        if (targetInv == null) return false;
        int tSize = targetInv.getSizeInventory();
        for (int i = 0; i < tSize; i++) {
            if (targetInv.getStackInSlot(i) == null) return true;
        }
        if (srcInv == null) return false;
        for (int s = 0; s < srcInv.getSizeInventory(); s++) {
            ItemStack st = srcInv.getStackInSlot(s);
            if (st == null || st.stackSize <= 0) continue;
            for (int i = 0; i < tSize; i++) {
                ItemStack existing = targetInv.getStackInSlot(i);
                if (existing != null && existing.isItemEqual(st) && ItemStack.areItemStackTagsEqual(existing, st)) {
                    int max = Math.min(targetInv.getInventoryStackLimit(), existing.getMaxStackSize());
                    if (existing.stackSize < max) return true;
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
        for (int i = 0; i < src.getSizeInventory() && moved < maxCount; i++) {
            ItemStack st = src.getStackInSlot(i);
            if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
            int meta = st.getItemDamage();
            String disp = st.getDisplayName();
            String combined = ItemClassifier.getItemIdentifier(st);
            boolean ok = matchAny
                    ? (f.equals("all") || f.equals("*") || st.getItem() instanceof ItemBlock)
                    : ItemClassifier.matchesBlockOrItemName(disp, combined, meta, f);
            if (!ok) continue;

            int take = Math.min(st.stackSize, maxCount - moved);
            ItemStack toInsert = st.copy();
            toInsert.stackSize = take;
            int ins = insertIntoInventory(dst, toInsert);
            if (ins > 0) {
                src.decrStackSize(i, ins);
                moved += ins;
            }
        }
        if (moved > 0) {
            src.markDirty();
            dst.markDirty();
        }
        return moved;
    }

    public int withdrawMatchingFromNearbyChests(World world, DroneController ctrl, EntityPlayerMP owner, String filter, int maxCount) {
        if (world == null || ctrl == null || ctrl.drone == null) return 0;
        String resolved = ItemClassifier.resolveHeldFilter(owner, filter);
        List<TileEntity> tiles = findAllInventoryTiles(world, ctrl.drone.posX, ctrl.drone.posY, ctrl.drone.posZ, 28);
        if (owner != null && owner.worldObj == world) {
            List<TileEntity> ownerTiles = findAllInventoryTiles(world, owner.posX, owner.posY, owner.posZ, 28);
            for (TileEntity ote : ownerTiles) {
                if (!tiles.contains(ote)) {
                    tiles.add(ote);
                }
            }
        }
        int totalMoved = 0;
        IInventory dst = ctrl.drone.mainInventory();

        boolean hasFreeSlot = false;
        for (int i = 0; i < dst.getSizeInventory(); i++) {
            ItemStack st = dst.getStackInSlot(i);
            if (st == null || st.stackSize <= 0) {
                hasFreeSlot = true;
                break;
            }
            if (ItemClassifier.matchesBlockOrItemName(st.getDisplayName(), ItemClassifier.getItemIdentifier(st), st.getItemDamage(), resolved)
                    && st.stackSize < st.getMaxStackSize()) {
                hasFreeSlot = true;
                break;
            }
        }
        if (!hasFreeSlot) {
            for (int i = dst.getSizeInventory() - 1; i >= 0; i--) {
                ItemStack st = dst.getStackInSlot(i);
                if (st == null) continue;
                if (!ItemClassifier.matchesBlockOrItemName(st.getDisplayName(), ItemClassifier.getItemIdentifier(st), st.getItemDamage(), resolved)) {
                    boolean swapped = false;
                    for (TileEntity te : tiles) {
                        if (te instanceof IInventory) {
                            ItemStack copy = st.copy();
                            int ins = insertIntoInventory((IInventory) te, copy);
                            if (ins == st.stackSize) {
                                dst.setInventorySlotContents(i, null);
                                dst.markDirty();
                                ((IInventory) te).markDirty();
                                swapped = true;
                                break;
                            }
                        }
                    }
                    if (!swapped && owner != null && owner.inventory != null && owner.inventory.addItemStackToInventory(st.copy())) {
                        dst.setInventorySlotContents(i, null);
                        dst.markDirty();
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
        int size = droneInv.getSizeInventory();
        for (int i = 0; i < size; i++) {
            ItemStack st = droneInv.getStackInSlot(i);
            if (st == null || st.stackSize <= 0) continue;
            ItemStack working = st.copy();
            totalDeposited += insertIntoInventory(targetInv, working);

            if (working.stackSize > 0 && te instanceof TileEntityChest) {
                TileEntityChest tc = (TileEntityChest) te;
                tc.checkForAdjacentChests();
                TileEntityChest[] adj = new TileEntityChest[]{
                        tc.adjacentChestZNeg, tc.adjacentChestXPos, tc.adjacentChestXNeg, tc.adjacentChestZPos
                };
                for (TileEntityChest a : adj) {
                    if (a != null && working.stackSize > 0) {
                        totalDeposited += insertIntoInventory(a, working);
                    }
                }
            }

            if (working.stackSize <= 0) {
                droneInv.setInventorySlotContents(i, null);
            } else {
                droneInv.setInventorySlotContents(i, working);
            }
        }
        droneInv.markDirty();
        targetInv.markDirty();

        if (countInventoryItems(droneInv) > 0 && d.worldObj != null) {
            List<TileEntity> nearby = findAllInventoryTiles(
                    d.worldObj,
                    te.xCoord + 0.5,
                    te.yCoord + 0.5,
                    te.zCoord + 0.5,
                    8
            );
            for (TileEntity other : nearby) {
                if (other == te || !(other instanceof IInventory)) continue;
                if (countInventoryItems(droneInv) <= 0) break;
                IInventory oInv = (IInventory) other;
                for (int i = 0; i < size; i++) {
                    ItemStack st = droneInv.getStackInSlot(i);
                    if (st == null || st.stackSize <= 0) continue;
                    ItemStack working = st.copy();
                    int ins = insertIntoInventory(oInv, working);
                    if (ins > 0) {
                        totalDeposited += ins;
                        droneInv.setInventorySlotContents(i, working.stackSize > 0 ? working : null);
                        oInv.markDirty();
                    }
                }
            }
            droneInv.markDirty();
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
        World world = d.worldObj;
        IInventory inv = d.mainInventory();
        if (world == null || inv == null) return;
        AxisAlignedBB box = AxisAlignedBB.getBoundingBox(
                x - radius, y - radius, z - radius,
                x + radius, y + radius, z + radius
        );
        List<?> items = world.getEntitiesWithinAABB(EntityItem.class, box);
        if (items == null || items.isEmpty()) return;

        boolean pickedAny = false;
        for (Object o : items) {
            if (o instanceof EntityItem) {
                EntityItem ei = (EntityItem) o;
                if (ei.isDead) continue;
                ItemStack st = ei.getEntityItem();
                if (st == null || st.stackSize <= 0) continue;
                int before = st.stackSize;
                insertIntoInventory(inv, st);
                if (st.stackSize <= 0) {
                    ei.setDead();
                    pickedAny = true;
                } else if (st.stackSize != before) {
                    ei.setEntityItemStack(st);
                    pickedAny = true;
                }
            }
        }
        if (pickedAny) {
            world.playSoundAtEntity(d, "random.pop", 0.25f, 1.6f);
        }
    }

    public static int dropAllDroneItemsAt(Drone d, World w, double x, double y, double z) {
        IInventory inv = d.mainInventory();
        if (inv == null) return 0;
        int totalCount = 0;
        int size = inv.getSizeInventory();
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st != null && st.stackSize > 0) {
                totalCount += st.stackSize;
                EntityItem ei = new EntityItem(w, x, y, z, st.copy());
                ei.delayBeforeCanPickup = 0;
                w.spawnEntityInWorld(ei);
                inv.setInventorySlotContents(i, null);
            }
        }
        inv.markDirty();
        return totalCount;
    }

    // ==================== Delegated Crafting Operations ====================

    public JsonObject getRecipesForFilter(String filter) {
        return this.craftingService.getRecipesForFilter(filter);
    }

    public CraftResult craftItem(World world, DroneController ctrl, EntityPlayerMP owner, String targetFilter, int wantedCount) {
        return this.craftingService.craftItem(world, ctrl, owner, targetFilter, wantedCount);
    }
}
