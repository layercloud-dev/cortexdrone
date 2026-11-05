package ir.layercloud.cortexdrone.behavior;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.FlightPathfinder;
import ir.layercloud.cortexdrone.navigation.TspRouteOptimizer;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import ir.layercloud.cortexdrone.world.RegistryIntrospector;
import ir.layercloud.cortexdrone.world.WorldScanner;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockCactus;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.BlockReed;
import net.minecraft.block.BlockSapling;
import net.minecraft.block.IGrowable;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.IPlantable;
import lombok.RequiredArgsConstructor;

/**
 * Dynamic agricultural harvesting, replanting, shoreline propagation, and fertilization service.
 * Uses Forge's IPlantable, Vanilla's IGrowable, and block placement rules (canPlaceBlockAt/canBlockStay)
 * to support all Vanilla and modded crops, reeds, cacti, and saplings without hardcoded item lists.
 */
@RequiredArgsConstructor
public class FarmingService {
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final WorldScanner worldScanner;
    private final InventoryService inventoryService;


    /**
     * Determines if a block is a vertical stem plant (e.g. Sugar Cane, Cactus, Bamboo, Reed)
     * where the bottom root block should be left intact so it regrows.
     */
    public static boolean isVerticalColumnPlant(World world, int x, int y, int z, Block b) {
        if (world == null || b == null || ItemClassifier.isLogBlock(b)) return false;
        if (b instanceof BlockReed || b instanceof BlockCactus || b == Blocks.field_150436_aH || b == Blocks.field_150434_aF) {
            return true;
        }
        if (b instanceof IPlantable && !(b instanceof BlockCrops) && !(b instanceof BlockSapling)) {
            return world.func_147439_a(x, y - 1, z) == b || world.func_147439_a(x, y + 1, z) == b;
        }
        return false;
    }

    /**
     * Checks if a crop block at (x, y, z) is mature and ready to harvest:
     * - For IGrowable crops (BlockCrops, modded crops): checks !canFertilize (fully grown).
     * - For vertical column plants (Sugar Cane, Cactus, Reed): checks that the block below is the same plant (preserves root).
     */
    public static boolean isMatureOrHarvestableCrop(World world, int x, int y, int z, Block b, int meta) {
        if (world == null || b == null || b == Blocks.field_150350_a) return false;
        if (isVerticalColumnPlant(world, x, y, z, b)) {
            return world.func_147439_a(x, y - 1, z) == b;
        }
        if (b instanceof BlockSapling) {
            return false;
        }
        if (b instanceof IGrowable) {
            try {
                // IGrowable.func_149851_a returns true if the crop is still growing (needs more growth)
                return !((IGrowable) b).func_149851_a(world, x, y, z, false);
            } catch (Throwable ignored) {
            }
        }
        if (b instanceof BlockCrops) {
            return meta >= 7;
        }
        return true;
    }

    /**
     * Dynamically searches an inventory for an ItemStack that plants `targetPlantBlock` at (x, y, z)
     * via Forge's IPlantable interface or ItemBlock conversion.
     */
    private static int findPlantableItemSlotForBlock(World world, IInventory inv, int x, int y, int z, Block targetPlantBlock) {
        if (inv == null || targetPlantBlock == null) return -1;
        for (int i = 0; i < inv.func_70302_i_(); i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
            Item item = st.func_77973_b();
            if (item instanceof IPlantable) {
                try {
                    Block planted = ((IPlantable) item).getPlant(world, x, y, z);
                    if (planted == targetPlantBlock) {
                        return i;
                    }
                } catch (Throwable ignored) {
                }
            }
            Block itemBlock = Block.func_149634_a(item);
            if (itemBlock == targetPlantBlock) {
                return i;
            }
            try {
                Item seedDrop = targetPlantBlock.func_149650_a(0, world.field_73012_v, 0);
                if (seedDrop != null && seedDrop == item) {
                    return i;
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static int findSaplingSlot(IInventory inv) {
        if (inv == null) return -1;
        for (int i = 0; i < inv.func_70302_i_(); i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
            Block b = Block.func_149634_a(st.func_77973_b());
            if (b instanceof BlockSapling || ItemClassifier.hasOreDictPrefix(st, "treeSapling")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Dynamically replants a harvested crop, reed, or tree sapling at (bx, by, bz) using
     * Forge's IPlantable and block placement checks without hardcoded item names.
     */
    public void tryReplantAt(World world, DroneController ctrl, int bx, int by, int bz, Block harvestedBlock, int harvestedMeta) {
        if (world == null || ctrl == null || ctrl.drone == null || !world.func_147437_c(bx, by, bz) || harvestedBlock == null) return;
        IInventory inv = ctrl.drone.mainInventory();
        Block below = world.func_147439_a(bx, by - 1, bz);
        if (below == null || below == Blocks.field_150350_a) return;

        // 1) Tree log harvested on soil/grass -> plant matching sapling
        if (ItemClassifier.isLogBlock(harvestedBlock)) {
            Block defaultSapling = Blocks.field_150345_g != null ? Blocks.field_150345_g : Block.func_149684_b("sapling");
            int sapSlot = findSaplingSlot(inv);
            if (sapSlot >= 0) {
                ItemStack sapStack = inv.func_70301_a(sapSlot);
                Block sapBlock = Block.func_149634_a(sapStack.func_77973_b());
                int sapMeta = sapStack.func_77973_b().func_77647_b(sapStack.func_77960_j());
                if (sapBlock != null && sapBlock.func_149742_c(world, bx, by, bz)) {
                    inv.func_70298_a(sapSlot, 1);
                    inv.func_70296_d();
                    world.func_147465_d(bx, by, bz, sapBlock, sapMeta, 3);
                    world.func_72908_a(bx + 0.5, by + 0.5, bz + 0.5, "dig.grass", 0.8f, 1.0f);
                    this.logger.log("FARM", ctrl, String.format("Planted %s at (%d,%d,%d)", sapStack.func_82833_r(), bx, by, bz), "");
                    return;
                }
            }
            if (defaultSapling != null && defaultSapling.func_149742_c(world, bx, by, bz)) {
                int normMeta = RegistryIntrospector.normalizeBlockMeta(harvestedBlock, harvestedMeta);
                int sapMeta = harvestedBlock == Blocks.field_150363_s ? ((normMeta & 3) + 4) : (normMeta & 3);
                world.func_147465_d(bx, by, bz, defaultSapling, sapMeta, 3);
                world.func_72908_a(bx + 0.5, by + 0.5, bz + 0.5, "dig.grass", 0.8f, 1.0f);
                this.logger.log("FARM", ctrl, String.format("Planted sapling at (%d,%d,%d)", bx, by, bz), "meta=" + sapMeta);
            }
            return;
        }

        // 2) Vertical stem plant (Sugar Cane / Reed / Cactus): do not replant on top of another stem
        if (isVerticalColumnPlant(world, bx, by, bz, harvestedBlock) && below == harvestedBlock) {
            return;
        }

        // 3) Crop or IPlantable block: find the matching seed/plant item in droneInv via IPlantable.getPlant
        int seedSlot = findPlantableItemSlotForBlock(world, inv, bx, by, bz, harvestedBlock);
        if (seedSlot >= 0) {
            ItemStack seedStack = inv.func_70301_a(seedSlot);
            Item item = seedStack.func_77973_b();
            int plantMeta = (item instanceof IPlantable) ? ((IPlantable) item).getPlantMetadata(world, bx, by, bz) : 0;
            if (harvestedBlock.func_149742_c(world, bx, by, bz) || harvestedBlock.func_149718_j(world, bx, by, bz)) {
                inv.func_70298_a(seedSlot, 1);
                inv.func_70296_d();
                world.func_147465_d(bx, by, bz, harvestedBlock, plantMeta, 3);
                world.func_72908_a(bx + 0.5, by + 0.5, bz + 0.5, "dig.grass", 0.8f, 1.0f);
                this.logger.log("FARM", ctrl, String.format("Replanted %s at (%d,%d,%d)", ItemClassifier.getBlockDisplayName(harvestedBlock, plantMeta), bx, by, bz), "");
            }
        }
    }

    public int startFarmingTask(DroneController ctrl, EntityPlayerMP player, String rawTarget, int radiusOverride) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) return 0;
        Drone d = ctrl.drone;
        final World world = d.field_70170_p;
        String target = (rawTarget == null || rawTarget.trim().isEmpty()) ? "crop" : rawTarget.trim().toLowerCase();
        if (ctrl.currentMineTarget != null) {
            world.func_147443_d(d.func_145782_y(), ctrl.currentMineTarget[0], ctrl.currentMineTarget[1], ctrl.currentMineTarget[2], -1);
        }

        ctrl.mineFilter = target;
        ctrl.mineExactQueueOnly = true;
        ctrl.replantAfterHarvest = true;
        ctrl.returnToOwnerAfterMine = radiusOverride > 64;
        ctrl.mineUntilFull = false;
        ctrl.mineTargetCount = 0;
        ctrl.mineQueue.clear();
        ctrl.currentMineTarget = null;
        ctrl.currentMineStandoff = null;
        ctrl.mineProgressTicks = 0;
        ctrl.minedCount = 0;

        final double cx = (ctrl.mode == Mode.GOTO && !Double.isNaN(ctrl.goalX))
            ? ctrl.goalX
            : (player != null && player.field_70170_p == world ? player.field_70165_t : d.field_70165_t);
        final double cy = (ctrl.mode == Mode.GOTO && !Double.isNaN(ctrl.goalY))
            ? ctrl.goalY
            : (player != null && player.field_70170_p == world ? player.field_70163_u : d.field_70163_u);
        final double cz = (ctrl.mode == Mode.GOTO && !Double.isNaN(ctrl.goalZ))
            ? ctrl.goalZ
            : (player != null && player.field_70170_p == world ? player.field_70161_v : d.field_70161_v);
        int ix = MathHelper.func_76128_c(cx);
        int iy = MathHelper.func_76128_c(cy);
        int iz = MathHelper.func_76128_c(cz);

        List<int[]> found = new ArrayList<int[]>();
        Set<Long> seen = new HashSet<Long>();
        int localRadius = Math.min(32, Math.max(16, Math.min(radiusOverride, 32)));
        int plantedRoots = 0;
        long scanDeadline = System.currentTimeMillis() + 30L;

        for (int x = ix - localRadius; x <= ix + localRadius && System.currentTimeMillis() <= scanDeadline; x++) {
            for (int z = iz - localRadius; z <= iz + localRadius; z++) {
                for (int y = Math.min(254, iy + 12); y >= Math.max(2, iy - 8); y--) {
                    if (!ItemClassifier.isMineableBlock(world, x, y, z)) continue;
                    Block b = world.func_147439_a(x, y, z);
                    int meta = world.func_72805_g(x, y, z);
                    if (ItemClassifier.matchesMineFilter(b, meta, target)) {
                        if (!isMatureOrHarvestableCrop(world, x, y, z, b, meta)) {
                            if (isVerticalColumnPlant(world, x, y, z, b)) {
                                plantedRoots++;
                            }
                            continue;
                        }
                        long k = FlightPathfinder.packBlockPos(x, y, z);
                        if (seen.add(k)) {
                            found.add(new int[]{x, y, z});
                        }
                    }
                }
            }
        }

        if (radiusOverride > 64 && world instanceof WorldServer) {
            try {
                List<?> loadedChunks = ((WorldServer) world).field_73059_b.func_152380_a();
                if (loadedChunks != null) {
                    int maxDistSq = radiusOverride * radiusOverride;
                    for (Object co : loadedChunks) {
                        if (!(co instanceof Chunk)) continue;
                        Chunk ch = (Chunk) co;
                        int baseX = ch.field_76635_g << 4;
                        int baseZ = ch.field_76647_h << 4;
                        int dx = (baseX + 8) - ix;
                        int dz = (baseZ + 8) - iz;
                        if (dx * dx + dz * dz > maxDistSq) continue;
                        for (int lx = 0; lx < 16; lx++) {
                            for (int lz = 0; lz < 16; lz++) {
                                int bx = baseX + lx;
                                int bz = baseZ + lz;
                                int topY = ch.func_76611_b(lx, lz);
                                for (int by = Math.min(254, topY + 5); by >= Math.max(45, topY - 3); by--) {
                                    if (!ItemClassifier.isMineableBlock(world, bx, by, bz)) continue;
                                    Block b = world.func_147439_a(bx, by, bz);
                                    int meta = world.func_72805_g(bx, by, bz);
                                    if (ItemClassifier.matchesMineFilter(b, meta, target) && isMatureOrHarvestableCrop(world, bx, by, bz, b, meta)) {
                                        long k = FlightPathfinder.packBlockPos(bx, by, bz);
                                        if (seen.add(k)) {
                                            found.add(new int[]{bx, by, bz});
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (radiusOverride > 64 || found.isEmpty()) {
            int wideR = Math.min(1200, Math.max(256, radiusOverride));
            List<int[]> distant = this.worldScanner.findDistantResourceOrBiome(world, ctrl, cx, cy, cz, target, wideR);
            for (int[] pt : distant) {
                for (int y = pt[1] + 4; y >= Math.max(2, pt[1] - 4); y--) {
                    if (!ItemClassifier.isMineableBlock(world, pt[0], y, pt[2])) continue;
                    Block b = world.func_147439_a(pt[0], y, pt[2]);
                    int meta = world.func_72805_g(pt[0], y, pt[2]);
                    if (ItemClassifier.matchesMineFilter(b, meta, target) && isMatureOrHarvestableCrop(world, pt[0], y, pt[2], b, meta)) {
                        long k = FlightPathfinder.packBlockPos(pt[0], y, pt[2]);
                        if (seen.add(k)) {
                            found.add(new int[]{pt[0], y, pt[2]});
                        }
                    }
                }
            }
        }

        // Propagate shoreline reeds/sugarcane dynamically using BlockReed.canPlaceBlockAt if roots are sparse
        int replantedNow = 0;
        if (plantedRoots < 24 && Blocks.field_150436_aH != null && ItemClassifier.matchesMineFilter(Blocks.field_150436_aH, 0, target)) {
            IInventory droneInv = d.mainInventory();
            for (int x = ix - 28; x <= ix + 28 && plantedRoots + replantedNow < 24; x++) {
                for (int z = iz - 28; z <= iz + 28 && plantedRoots + replantedNow < 24; z++) {
                    for (int y = Math.max(55, iy - 6); y <= Math.min(72, iy + 4); y++) {
                        if (!world.func_147437_c(x, y, z)) continue;
                        if (Blocks.field_150436_aH.func_149742_c(world, x, y, z)) {
                            int slot = findPlantableItemSlotForBlock(world, droneInv, x, y, z, Blocks.field_150436_aH);
                            if (slot >= 0) {
                                droneInv.func_70298_a(slot, 1);
                                droneInv.func_70296_d();
                                world.func_147465_d(x, y, z, Blocks.field_150436_aH, 0, 3);
                                replantedNow++;
                                break;
                            }
                        }
                    }
                }
            }
            if (replantedNow > 0) {
                this.logger.log("FARM", ctrl, "Propagated " + replantedNow + " Sugar Canes along nearby water shoreline", "");
            }
        }

        List<int[]> tour = TspRouteOptimizer.optimizeBlockTour(found, d.field_70165_t, d.field_70163_u, d.field_70161_v);
        for (int[] pos : tour) {
            ctrl.mineQueue.addLast(pos);
        }
        if (!ctrl.mineQueue.isEmpty()) {
            this.manager.setDroneMode(ctrl, Mode.MINE, "Farming & replanting '" + target + "' (" + found.size() + " blocks, replanted=" + replantedNow + ")");
            d.lightColor_$eq(0x00FF66);
            d.statusText_$eq("Farming\n" + AIDroneManager.trimStatus(target));
        } else {
            this.logger.log("FARM", ctrl, "No mature farmable '" + target + "' found within " + radiusOverride + "m (replanted=" + replantedNow + ")", "");
        }
        return found.size() + replantedNow;
    }

    /**
     * Dynamically fertilizes any IGrowable crops/saplings within `radius` using bonemeal from drone/chests/player.
     */
    public int fertilizeNearbyCrops(World world, DroneController ctrl, EntityPlayerMP player, int radius) {
        if (world == null || ctrl == null || ctrl.drone == null) return 0;
        Drone d = ctrl.drone;
        IInventory inv = d.mainInventory();
        int r = Math.max(4, Math.min(24, radius));
        int cx = MathHelper.func_76128_c(player != null ? player.field_70165_t : d.field_70165_t);
        int cy = MathHelper.func_76128_c(player != null ? player.field_70163_u : d.field_70163_u);
        int cz = MathHelper.func_76128_c(player != null ? player.field_70161_v : d.field_70161_v);

        int fertilized = 0;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int y = Math.max(2, cy - 6); y <= Math.min(254, cy + 6); y++) {
                    if (world.func_147437_c(x, y, z)) continue;
                    Block b = world.func_147439_a(x, y, z);
                    if (!(b instanceof IGrowable) || b == Blocks.field_150349_c || b == Blocks.field_150329_H) continue;
                    IGrowable growable = (IGrowable) b;
                    if (!growable.func_149851_a(world, x, y, z, false)) continue;

                    int boneSlot = findBonemealSlot(inv);
                    if (boneSlot < 0) {
                        this.inventoryService.withdrawMatchingFromNearbyChests(world, ctrl, player, "Bone Meal", 16);
                        boneSlot = findBonemealSlot(inv);
                        if (boneSlot < 0 && player != null) {
                            this.inventoryService.takeFromPlayerInventory(ctrl, player, "Bone Meal", 16);
                            boneSlot = findBonemealSlot(inv);
                        }
                    }
                    if (boneSlot < 0) return fertilized;

                    inv.func_70298_a(boneSlot, 1);
                    inv.func_70296_d();
                    if (growable.func_149852_a(world, world.field_73012_v, x, y, z)) {
                        growable.func_149853_b(world, world.field_73012_v, x, y, z);
                    }
                    world.func_72926_e(2005, x, y, z, 0);
                    fertilized++;
                }
            }
        }
        return fertilized;
    }

    private static int findBonemealSlot(IInventory inv) {
        if (inv == null) return -1;
        for (int i = 0; i < inv.func_70302_i_(); i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st != null && st.field_77994_a > 0 && st.func_77973_b() == Items.field_151100_aR && st.func_77960_j() == 15) {
                return i;
            }
        }
        return -1;
    }
}
