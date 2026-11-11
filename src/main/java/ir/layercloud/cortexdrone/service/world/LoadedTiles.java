package ir.layercloud.cortexdrone.service.world;

import ir.layercloud.cortexdrone.service.navigation.DroneGeometry;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Distance-filtered sweeps over already-loaded tile entities.
 *
 * <p>Three inventories-related scanners ({@code InventoryService},
 * {@code MachineTileInspector}, {@code FurnaceScanner}) each re-implemented the
 * same loop. It must only visit <em>loaded</em> tiles: an unloaded chunk's chest
 * cannot be opened anyway, and probing it would force chunk generation on the
 * main thread.</p>
 */
public final class LoadedTiles {
    private LoadedTiles() {
    }

    /**
     * Every loaded tile within {@code radius} of the point that matches {@code filter}.
     *
     * @param chestsFirst sort storage chests ahead of other containers
     */
    public static List<TileEntity> near(World world, double x, double y, double z,
                                        int radius, Predicate<TileEntity> filter,
                                        boolean chestsFirst) {
        List<TileEntity> found = new ArrayList<>();
        if (world == null || world.loadedTileEntityList == null) return found;
        double radiusSq = (double) radius * radius;
        for (Object candidate : world.loadedTileEntityList) {
            if (!(candidate instanceof TileEntity)) continue;
            TileEntity tile = (TileEntity) candidate;
            if (distanceSq(tile, x, y, z) > radiusSq) continue;
            if (filter != null && !filter.test(tile)) continue;
            found.add(tile);
        }
        found.sort(chestsFirst
                ? CHESTS_FIRST.thenComparingDouble(tile -> distanceSq(tile, x, y, z))
                : Comparator.comparingDouble(tile -> distanceSq(tile, x, y, z)));
        return found;
    }

    /**
     * Squared distance from the point to the tile's centre.
     */
    public static double distanceSq(TileEntity tile, double x, double y, double z) {
        double dx = tile.xCoord + DroneGeometry.CELL_CENTER - x;
        double dy = tile.yCoord + DroneGeometry.CELL_CENTER - y;
        double dz = tile.zCoord + DroneGeometry.CELL_CENTER - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Chests sort before other containers at equal convenience.
     */
    private static final Comparator<TileEntity> CHESTS_FIRST =
            Comparator.comparingInt(tile -> tile instanceof TileEntityChest ? 0 : 1);
}
