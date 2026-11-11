package ir.layercloud.cortexdrone.service.inventory;

import li.cil.oc.common.entity.Drone;
import lombok.experimental.UtilityClass;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plays the container open/close feedback a real player would produce.
 *
 * <p>{@code IInventory.openInventory()} is the vanilla hook a
 * {@code ContainerChest} calls when a player opens something. For a chest it
 * increments {@code numPlayersUsing}, which {@code TileEntityChest.updateEntity}
 * turns into the lid animation and the {@code random.chestopen} sound. For every
 * other container it is the mod's own open hook.</p>
 *
 * <p>The drone manipulates slots directly, so without this the lid never moved
 * and nothing played — which looked like telekinesis. {@link #open} returns a
 * token that must be closed in a {@code finally} block.</p>
 */
@UtilityClass
@SuppressWarnings("unused")
public final class ContainerAnimation {
    /**
     * Opens a container and returns a token for {@link Token#close()}.
     */
    public static Token open(World world, Drone drone, TileEntity tile) {
        if (tile == null) return Token.none();
        List<IInventory> opened = new ArrayList<>();
        openInventory(tile, opened);
        if (opened.isEmpty()) {
            // Not an IInventory: still show the drone reaching for it.
            swing(drone);
        }
        return opened.isEmpty() ? Token.none() : new Token(opened);
    }

    /**
     * Opens every container in a list and returns one combined token.
     */
    public static Token openAll(World world, Drone drone, Iterable<TileEntity> tiles) {
        List<IInventory> opened = new ArrayList<>();
        if (tiles != null) {
            for (TileEntity tile : tiles) {
                openInventory(tile, opened);
            }
        }
        if (opened.isEmpty() && drone != null) swing(drone);
        return opened.isEmpty() ? Token.none() : new Token(opened);
    }

    /**
     * One fake-player arm swing, for targets that expose no inventory hook.
     */
    public static void swing(Drone drone) {
        if (drone == null) return;
        try {
            EntityPlayer fake = drone.player();
            if (fake != null) fake.swingItem();
        } catch (Throwable ignored) {
        }
    }

    private static void openInventory(TileEntity tile, List<IInventory> opened) {
        if (!(tile instanceof IInventory)) return;
        IInventory inventory = (IInventory) tile;
        try {
            inventory.openInventory();
            opened.add(inventory);
        } catch (Throwable ignored) {
        }
        // A double chest has two lids; vanilla opens both halves.
        if (tile instanceof net.minecraft.tileentity.TileEntityChest) {
            net.minecraft.tileentity.TileEntityChest chest =
                    (net.minecraft.tileentity.TileEntityChest) tile;
            try {
                chest.checkForAdjacentChests();
            } catch (Throwable ignored) {
            }
            for (net.minecraft.tileentity.TileEntityChest half : new net.minecraft.tileentity.TileEntityChest[]{
                    chest.adjacentChestZNeg, chest.adjacentChestXPos,
                    chest.adjacentChestXNeg, chest.adjacentChestZPos}) {
                if (half == null) continue;
                try {
                    half.openInventory();
                    opened.add(half);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Opens a container, runs {@code body}, and always closes it again.
     */
    public static <T> T withOpen(World world, Drone drone, TileEntity tile, Body<T> body) {
        Token token = open(world, drone, tile);
        try {
            return body.run();
        } finally {
            token.close();
        }
    }

    /**
     * The work done while a container is "open".
     */
    public interface Body<T> {
        T run();
    }

    /**
     * Closes whatever {@link #open} opened. Safe to call when nothing opened.
     */
    public static final class Token {
        private final List<IInventory> opened;

        private Token(List<IInventory> opened) {
            this.opened = opened;
        }

        private static Token none() {
            return new Token(Collections.<IInventory>emptyList());
        }

        /**
         * Closes every container this token opened. Call from a {@code finally}.
         */
        public void close() {
            for (IInventory inventory : this.opened) {
                try {
                    inventory.closeInventory();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
