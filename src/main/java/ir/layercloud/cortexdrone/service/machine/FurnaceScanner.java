package ir.layercloud.cortexdrone.service.machine;

import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import lombok.experimental.UtilityClass;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Read-only furnace discovery. Kept separate from any smelting executor: the
 * harness exposes inspection, not a pre-made smelt task.
 */
@UtilityClass
public final class FurnaceScanner {

    public static List<TileEntity> findAllFurnaceTiles(final World world, final double x, final double y, final double z, int radius) {
        List<TileEntity> tiles = new ArrayList<>();
        double r2 = (double) radius * radius;
        if (world.loadedTileEntityList != null) {
            for (Object o : world.loadedTileEntityList) {
                if (!(o instanceof TileEntity)) continue;
                TileEntity te = (TileEntity) o;
                double dx = te.xCoord + 0.5 - x;
                double dy = te.yCoord + 0.5 - y;
                double dz = te.zCoord + 0.5 - z;
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                if (ItemClassifier.isFurnaceTile(world, te)) {
                    tiles.add(te);
                }
            }
        }
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
                        if (by < 1 || by > 254) continue;
                        TileEntity te = world.getTileEntity(bx, by, bz);
                        if (ItemClassifier.isFurnaceTile(world, te)) {
                            tiles.add(te);
                        }
                    }
                }
            }
        }
        tiles.sort((a, b) -> {
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
}
