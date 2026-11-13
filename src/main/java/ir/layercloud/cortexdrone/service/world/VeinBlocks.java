package ir.layercloud.cortexdrone.service.world;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import java.util.List;

/**
 * World-facing vein plan for {@code break_block(vein:true)}.
 *
 * <p>"Same vein" means same block and same metadata, connected face to face, and
 * mineable under the normal rules. A nearby block of the same ore that is not
 * connected is a different vein and stays untouched.</p>
 */
public final class VeinBlocks {
    private VeinBlocks() {
    }

    /**
     * The connected cluster around {@code (x,y,z)}, origin first then nearest
     * first, clamped to the documented radius and block cap.
     *
     * <p>Returns just the origin when the block has no matching neighbours, so
     * the caller can always treat the result as a normal single break.</p>
     */
    public static List<int[]> plan(World world, int x, int y, int z,
                                   int radius, int maxBlocks) {
        Block origin = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        return VeinFlood.connected(x, y, z,
                VeinLimits.clampRadius(radius), VeinLimits.clampBlocks(maxBlocks),
                (cx, cy, cz) -> world.getBlock(cx, cy, cz) == origin
                        && world.getBlockMetadata(cx, cy, cz) == meta
                        && ItemClassifier.isMineableBlock(world, cx, cy, cz));
    }
}
