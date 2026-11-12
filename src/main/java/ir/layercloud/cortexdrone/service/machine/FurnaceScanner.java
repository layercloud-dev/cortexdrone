package ir.layercloud.cortexdrone.service.machine;

import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.LoadedTiles;
import ir.layercloud.cortexdrone.service.world.WorldBounds;
import lombok.experimental.UtilityClass;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.Comparator;
import java.util.List;

/**
 * Read-only furnace discovery. Kept separate from any smelting executor: the
 * harness exposes inspection, not a pre-made smelt task.
 */
@UtilityClass
public final class FurnaceScanner {

    public static List<TileEntity> findAllFurnaceTiles(final World world, final double x,
                                                       final double y, final double z, int radius) {
        List<TileEntity> tiles = LoadedTiles.near(
                world, x, y, z, radius, tile -> ItemClassifier.isFurnaceTile(world, tile), false);
        // Fallback only when the loaded-tile list is unavailable, so callers
        // never lose furnaces and the dense cube scan never runs twice.
        if (tiles.isEmpty() && world.loadedTileEntityList == null) {
            int cx = MathHelper.floor_double(x);
            int cy = MathHelper.floor_double(y);
            int cz = MathHelper.floor_double(z);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int bx = cx + dx, by = cy + dy, bz = cz + dz;
                        if (!WorldBounds.inWorld(by)) continue;
                        TileEntity te = world.getTileEntity(bx, by, bz);
                        if (ItemClassifier.isFurnaceTile(world, te)) {
                            tiles.add(te);
                        }
                    }
                }
            }
        }
        tiles.sort(Comparator.comparingDouble(
                tile -> LoadedTiles.distanceSq(tile, x, y, z)));
        return tiles;
    }
}
