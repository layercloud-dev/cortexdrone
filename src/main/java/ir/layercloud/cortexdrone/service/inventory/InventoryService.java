package ir.layercloud.cortexdrone.service.inventory;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.service.machine.MachineRecipeService;
import ir.layercloud.cortexdrone.service.navigation.DroneGeometry;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.Getter;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Core inventory service handling slot transfers, multi-chest discovery,
 * smart slot-swapping withdrawals, deposits, and item vacuuming.
 * Delegates crafting and fluid scooping to focused helpers.
 */
@SuppressWarnings("unused")
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
        this.craftingService = new CraftingService();
        this.containerSearchService = new ContainerSearchService(this);
        this.playerInventoryOps = new PlayerInventoryOps(logger);
        this.machineRecipeService = new MachineRecipeService(this);
    }

    public static boolean isInventoryFull(IInventory inv) {
        return InventoryTransfers.isInventoryFull(inv);
    }

    public static int countInventoryItems(IInventory inv) {
        return InventoryTransfers.countInventoryItems(inv);
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

    /**
     * Moves as much of one source slot as the targets will accept.
     *
     * @see InventoryTransfers#spreadStackAcross(IInventory, int, List)
     */
    public static int spreadStackAcross(IInventory source, int slot,
                                        List<? extends IInventory> targets) {
        return InventoryTransfers.spreadStackAcross(source, slot, targets);
    }

    public static int insertIntoInventory(IInventory inv, ItemStack stack) {
        return InventoryTransfers.insertIntoInventory(inv, stack);
    }

    /**
     * @see InventoryTransfers#findPlaceableSlot(IInventory, String)
     */
    public static int findPlaceableItemSlot(IInventory inv, String filter) {
        return InventoryTransfers.findPlaceableSlot(inv, filter);
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

    public List<TileEntity> findAllInventoryTiles(final World world, final double x,
                                                  final double y, final double z, int radius) {
        // Only loaded tiles are readable; probing unloaded chunks would force
        // generation on the main thread for a container nobody can open.
        return ir.layercloud.cortexdrone.service.world.LoadedTiles.near(world, x, y, z, radius,
                tile -> ItemClassifier.isStorageChestTile(world, tile), true);
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

    /**
     * Withdraws matching items from containers the drone can physically open.
     *
     * <p>Only containers within reach with a clear line of sight are eligible,
     * and each one is opened and closed through its real inventory hook so the
     * lid/feedback animates. A distant chest is never touched: the old version
     * also searched around the player, which let the drone take items from
     * dozens of blocks away.</p>
     */
    public int withdrawMatchingFromNearbyChests(World world, DroneController ctrl, EntityPlayerMP owner,
                                                String filter, int maxCount) {
        if (world == null || ctrl == null || ctrl.drone == null) return 0;
        String resolved = ItemClassifier.resolveHeldFilter(owner, filter);
        List<TileEntity> candidates = findAllInventoryTiles(
                world, ctrl.drone.posX, ctrl.drone.posY, ctrl.drone.posZ, 28);
        List<TileEntity> tiles = ContainerAccess.reachable(world, ctrl.drone, candidates);
        if (tiles.isEmpty()) return 0;

        int totalMoved = 0;
        IInventory dst = ctrl.drone.mainInventory();
        if (dst == null) return 0;

        if (!this.hasRoomFor(dst, resolved)) {
            this.makeRoom(dst, resolved, world, ctrl, tiles);
        }

        for (TileEntity te : tiles) {
            if (totalMoved >= maxCount || isInventoryFull(dst)) break;
            if (!(te instanceof IInventory)) continue;
            final IInventory source = (IInventory) te;
            final int budget = maxCount - totalMoved;
            totalMoved += ContainerAnimation.withOpen(world, ctrl.drone, te, () ->
                    withdrawFromInventory(source, dst, resolved, budget));
        }
        if (totalMoved > 0) {
            this.logger.log("INV", ctrl, "Withdrew " + totalMoved + " item(s) matching '"
                    + resolved + "' from a reachable container", "");
        }
        return totalMoved;
    }

    /**
     * True when the destination can take the filtered item (or any item).
     */
    private boolean hasRoomFor(IInventory dst, String resolved) {
        for (int i = 0; i < dst.getSizeInventory(); i++) {
            ItemStack st = dst.getStackInSlot(i);
            if (st == null || st.stackSize <= 0) return true;
            if (ItemClassifier.matchesBlockOrItemName(st.getDisplayName(),
                    ItemClassifier.getItemIdentifier(st), st.getItemDamage(), resolved)
                    && st.stackSize < st.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Frees a slot by moving an unrelated stack into a reachable container.
     * The player's inventory is deliberately not used: that would be a
     * teleport, not an interaction.
     */
    private void makeRoom(IInventory dst, String resolved, World world, DroneController ctrl,
                          List<TileEntity> tiles) {
        for (int i = dst.getSizeInventory() - 1; i >= 0; i--) {
            ItemStack st = dst.getStackInSlot(i);
            if (st == null) continue;
            if (ItemClassifier.matchesBlockOrItemName(st.getDisplayName(),
                    ItemClassifier.getItemIdentifier(st), st.getItemDamage(), resolved)) {
                continue;
            }
            // One working copy across every container: offering a fresh full
            // copy per container duplicated items whenever one accepted only
            // part of the stack.
            boolean drained = InventoryTransfers.drainSlotAcross(dst, i, inventoryTargets(tiles),
                    (target, working) -> ContainerAnimation.withOpen(world, ctrl.drone,
                            tileFor(tiles, target),
                            () -> InventoryTransfers.insertIntoInventory(target, working)));
            if (drained) return;
        }
    }

    /**
     * The tile list restricted to the IInventories of the candidate tiles.
     */
    private static List<IInventory> inventoryTargets(List<TileEntity> tiles) {
        List<IInventory> out = new ArrayList<>(tiles.size());
        for (TileEntity te : tiles) {
            if (te instanceof IInventory) out.add((IInventory) te);
        }
        return out;
    }

    private static TileEntity tileFor(List<TileEntity> tiles, IInventory inventory) {
        for (TileEntity te : tiles) {
            if (te == inventory) return te;
        }
        return null;
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
            List<TileEntity> nearby = ContainerAccess.reachable(d.worldObj, d,
                    findAllInventoryTiles(
                            d.worldObj,
                            te.xCoord + DroneGeometry.CELL_CENTER,
                            te.yCoord + DroneGeometry.CELL_CENTER,
                            te.zCoord + DroneGeometry.CELL_CENTER,
                            (int) Math.ceil(ContainerAccess.REACH)));
            for (TileEntity other : nearby) {
                if (other == te || !(other instanceof IInventory)) continue;
                if (InventoryTransfers.countInventoryItems(droneInv) <= 0) break;
                totalDeposited += depositAllSlots(droneInv, (IInventory) other);
            }
            droneInv.markDirty();
        }
        return totalDeposited;
    }

    /**
     * Empties every slot of {@code source} into {@code target}, conserving items.
     *
     * <p>A single working copy per slot is written back with the remainder, so a
     * partially-filled target can never duplicate or lose a stack.</p>
     *
     * @return the number of items moved
     */
    private static int depositAllSlots(IInventory source, IInventory target) {
        int moved = 0;
        for (int i = 0; i < source.getSizeInventory(); i++) {
            moved += InventoryTransfers.spreadStackAcross(source, i,
                    java.util.Collections.singletonList(target));
        }
        if (moved > 0) target.markDirty();
        return moved;
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

    /**
     * @see GroundItems#suck(Drone, double, double, double, double)
     */
    public static void suckNearbyItems(Drone d, double x, double y, double z, double radius) {
        GroundItems.suck(d, x, y, z, radius);
    }

    /**
     * @see GroundItems#dropAll(Drone, World, double, double, double)
     */
    public static int dropAllDroneItemsAt(Drone d, World w, double x, double y, double z) {
        return GroundItems.dropAll(d, w, x, y, z);
    }

    // ==================== Delegated Read-Only Recipe Queries ====================

    public JsonObject getRecipesForFilter(String filter) {
        return this.craftingService.getRecipesForFilter(filter);
    }

}
