package ir.layercloud.cortexdrone.service.inventory;

import li.cil.oc.common.entity.Drone;
import lombok.experimental.UtilityClass;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
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
 * <p>A {@link Token} closes through {@link ContainerCloser}, so the container
 * stays open for {@link ContainerCloser#MIN_OPEN_TICKS} server ticks. Closing
 * in the same tick produced no animation at all: both block events landed in
 * one batch and the client only saw the final closed value.</p>
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
        if (tile instanceof TileEntityChest) {
            TileEntityChest chest =
                    (TileEntityChest) tile;
            try {
                chest.checkForAdjacentChests();
            } catch (Throwable ignored) {
            }
            for (TileEntityChest half : new TileEntityChest[]{
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
     * Opens a container, runs {@code body}, and schedules the close.
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
        private boolean closed;

        private Token(List<IInventory> opened) {
            this.opened = opened;
        }

        private static Token none() {
            return new Token(Collections.<IInventory>emptyList());
        }

        /**
         * Schedules the close. Call from a {@code finally}; the actual
         * {@code closeInventory()} happens a few ticks later so the lid and the
         * latch sound are visible.
         */
        public void close() {
            if (this.closed || this.opened.isEmpty()) return;
            this.closed = true;
            ContainerCloser.schedule(this.opened);
        }
    }
}
