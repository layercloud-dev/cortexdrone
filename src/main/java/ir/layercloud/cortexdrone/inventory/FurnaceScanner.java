package ir.layercloud.cortexdrone.inventory;

import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import lombok.experimental.UtilityClass;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Read-only furnace discovery. Kept separate from any smelting executor: the
 * harness exposes inspection, not a pre-made smelt task.
 */
@UtilityClass
public final class FurnaceScanner {

    public static List<TileEntity> findAllFurnaceTiles(final World world, final double x, final double y, final double z, int radius) {
        List<TileEntity> tiles = new ArrayList<TileEntity>();
        double r2 = (double) radius * radius;
        if (world.field_147482_g != null) {
            for (Object o : world.field_147482_g) {
                if (!(o instanceof TileEntity)) continue;
                TileEntity te = (TileEntity) o;
                double dx = te.field_145851_c + 0.5 - x;
                double dy = te.field_145848_d + 0.5 - y;
                double dz = te.field_145849_e + 0.5 - z;
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                if (ItemClassifier.isFurnaceTile(world, te)) {
                    tiles.add(te);
                }
            }
        }
        // Fallback only when the loaded-tile list is unavailable, so callers
        // never lose furnaces and the dense cube scan never runs twice.
        if (tiles.isEmpty() && world.field_147482_g == null) {
            int cx = MathHelper.func_76128_c(x);
            int cy = MathHelper.func_76128_c(y);
            int cz = MathHelper.func_76128_c(z);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int bx = cx + dx, by = cy + dy, bz = cz + dz;
                        if (by < 1 || by > 254) continue;
                        TileEntity te = world.func_147438_o(bx, by, bz);
                        if (ItemClassifier.isFurnaceTile(world, te)) {
                            tiles.add(te);
                        }
                    }
                }
            }
        }
        Collections.sort(tiles, new Comparator<TileEntity>() {
            @Override
            public int compare(TileEntity a, TileEntity b) {
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
}
