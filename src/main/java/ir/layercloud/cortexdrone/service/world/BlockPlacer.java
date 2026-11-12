package ir.layercloud.cortexdrone.service.world;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * Places a block the way a player does, and forces the client update.
 *
 * <p>{@code world.setBlock(..., 3)} is not enough on its own:</p>
 * <ul>
 *   <li>vanilla skips {@code markBlockForUpdate} unless the chunk is fully
 *       light-populated, so a block placed into a freshly loaded chunk stayed
 *       invisible for players until the chunk was resent;</li>
 *   <li>{@code onBlockPlacedBy}/{@code onPostBlockPlaced} never ran, so modded
 *       blocks lost their facing, owner or tile-entity setup;</li>
 *   <li>{@code playAuxSFX(2001)} is the <em>break</em> effect, so placing
 *       played dig particles and a break sound.</li>
 * </ul>
 *
 * <p>This helper routes through {@link ItemBlock#placeBlockAt} when the item is
 * a block item (exactly what a player's right click does) and always refreshes
 * the client and the neighbors afterwards.</p>
 */
public final class BlockPlacer {
    private BlockPlacer() {
    }

    /**
     * Places {@code stack}'s block at the target, without consuming the stack.
     *
     * @param player the acting player/fake player, or {@code null}
     * @param side   the face the block is placed against
     * @return true when the block is now in the world
     */
    public static boolean place(World world, EntityPlayer player, int x, int y, int z,
                                ItemStack stack, Block block, int meta, int side) {
        if (world == null || block == null || stack == null) return false;

        boolean placed;
        if (stack.getItem() instanceof ItemBlock) {
            ItemBlock itemBlock = (ItemBlock) stack.getItem();
            try {
                placed = itemBlock.placeBlockAt(stack, player, world, x, y, z, side,
                        0.5f, 0.5f, 0.5f, meta);
            } catch (Throwable modFailure) {
                // A mod's placement hook may assume a real player; fall back to
                // the raw write so the build does not silently stop.
                placed = world.setBlock(x, y, z, block, meta, 3);
            }
        } else {
            placed = world.setBlock(x, y, z, block, meta, 3);
            if (placed) {
                try {
                    block.onBlockPlacedBy(world, x, y, z, player, stack);
                    block.onPostBlockPlaced(world, x, y, z, meta);
                } catch (Throwable ignored) {
                }
            }
        }
        if (!placed) return false;

        Block actual = world.getBlock(x, y, z);
        refresh(world, x, y, z, actual != null ? actual : block);
        playPlaceSound(world, x, y, z, actual != null ? actual : block);
        return true;
    }

    /**
     * Marks the block for a client resend and notifies its neighbors.
     *
     * <p>Safe to call after any world mutation this mod performs; a redundant
     * notify is cheap compared with an invisible or stale block.</p>
     */
    public static void refresh(World world, int x, int y, int z, Block block) {
        if (world == null) return;
        try {
            world.markBlockForUpdate(x, y, z);
        } catch (Throwable ignored) {
        }
        try {
            world.notifyBlocksOfNeighborChange(x, y, z, block);
        } catch (Throwable ignored) {
        }
    }

    /**
     * The vanilla "block placed" sound, instead of the break effect.
     */
    public static void playPlaceSound(World world, int x, int y, int z, Block block) {
        if (world == null || block == null || block.stepSound == null) return;
        try {
            world.playSoundEffect(x + 0.5D, y + 0.5D, z + 0.5D,
                    block.stepSound.func_150496_b(),
                    (block.stepSound.getVolume() + 1.0F) / 2.0F,
                    block.stepSound.getPitch() * 0.8F);
        } catch (Throwable ignored) {
        }
    }
}
