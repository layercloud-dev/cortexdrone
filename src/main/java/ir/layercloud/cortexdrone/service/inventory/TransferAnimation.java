package ir.layercloud.cortexdrone.service.inventory;

import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Shows items travelling between a container and the drone.
 *
 * <p>A direct slot write is invisible: items appear in the other inventory with
 * no movement, which reads as telekinesis. Vanilla has no item-flight entity,
 * so this walks a short trail of {@code iconcrack_<itemId>_<damage>} particles
 * along the line between the two points over {@link #TRAIL_TICKS} server ticks,
 * using the real item texture, plus the drone's arm swing.</p>
 *
 * <p>One trail per transfer call, never one per item, and a hard cap on active
 * trails: a 64-item transfer must not spawn 64 trails (or 64 packets per tick).</p>
 */
public final class TransferAnimation {
    /**
     * Server ticks a trail lives. Short enough not to lag behind the item.
     */
    public static final int TRAIL_TICKS = 8;
    /**
     * Particles emitted per trail per tick.
     */
    private static final int PARTICLES_PER_TICK = 1;
    /**
     * Active trails across the server. Transfers are rare, so this is generous
     * headroom rather than a tuning knob.
     */
    public static final int MAX_TRAILS = 8;

    private static final List<Trail> ACTIVE =
            Collections.synchronizedList(new ArrayList<>());

    private TransferAnimation() {
    }

    /**
     * Animates items moving from {@code from} to {@code to}.
     *
     * @param sample a representative stack; its item icon is the particle
     */
    public static void move(World world, Drone drone,
                            double fromX, double fromY, double fromZ,
                            double toX, double toY, double toZ,
                            ItemStack sample) {
        if (drone != null) ContainerAnimation.swing(drone);
        if (world == null || world.isRemote || sample == null || sample.getItem() == null) return;
        if (!(world instanceof WorldServer)) return;
        if (ACTIVE.size() >= MAX_TRAILS) return;
        ACTIVE.add(new Trail(world, fromX, fromY, fromZ, toX, toY, toZ,
                Item.getIdFromItem(sample.getItem()), sample.getItemDamage()));
    }

    /**
     * Animates a transfer between a container tile and the drone.
     *
     * @param intoDrone true when items move container -> drone
     */
    public static void containerTransfer(World world, Drone drone,
                                         double tileX, double tileY, double tileZ,
                                         ItemStack sample, boolean intoDrone) {
        if (drone == null) return;
        double droneX = drone.posX;
        double droneY = drone.posY + 0.4D;
        double droneZ = drone.posZ;
        double centerX = tileX + 0.5D;
        double centerY = tileY + 0.75D;
        double centerZ = tileZ + 0.5D;
        if (intoDrone) {
            move(world, drone, centerX, centerY, centerZ, droneX, droneY, droneZ, sample);
        } else {
            move(world, drone, droneX, droneY, droneZ, centerX, centerY, centerZ, sample);
        }
    }

    /**
     * Advances every trail. Server thread, once per tick.
     */
    public static void tick() {
        if (ACTIVE.isEmpty()) return;
        synchronized (ACTIVE) {
            Iterator<Trail> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Trail trail = it.next();
                if (!trail.step()) it.remove();
            }
        }
    }

    /**
     * Trail for a finished container -> drone withdrawal.
     *
     * @param dst    the drone inventory that now holds the items
     * @param filter the filter that was requested, used to pick the icon
     */
    public static void afterWithdraw(World world, Drone drone, TileEntity tile,
                                     IInventory dst, String filter) {
        if (world == null || drone == null || tile == null) return;
        containerTransfer(world, drone, tile.xCoord, tile.yCoord, tile.zCoord,
                representative(dst, filter), true);
    }

    /**
     * Trail for a finished drone -> container deposit.
     */
    public static void afterDeposit(World world, Drone drone, TileEntity tile, IInventory target) {
        if (world == null || drone == null || tile == null) return;
        containerTransfer(world, drone, tile.xCoord, tile.yCoord, tile.zCoord,
                representative(target, null), false);
    }

    /**
     * A representative stack from {@code inv}, preferring {@code filter}.
     *
     * <p>Matching is done through {@link ItemClassifier}, which already maps
     * MCP names to the reobfuscated server names; calling
     * {@code Item.itemRegistry.getNameForObject} directly would fail there.</p>
     */
    public static ItemStack representative(IInventory inv, String filter) {
        if (inv == null) return null;
        ItemStack fallback = null;
        String wanted = filter != null ? filter.trim().toLowerCase(Locale.ROOT) : "";
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
            if (fallback == null) fallback = st;
            if (wanted.isEmpty()) return st;
            if (ItemClassifier.matchesBlockOrItemName(st.getDisplayName(),
                    ItemClassifier.getItemIdentifier(st), st.getItemDamage(), wanted)) {
                return st;
            }
        }
        return fallback;
    }

    /**
     * Drops all trails (world unload / server stop).
     */
    public static void clear() {
        ACTIVE.clear();
    }

    private static final class Trail {
        private final World world;
        private final double fromX;
        private final double fromY;
        private final double fromZ;
        private final double toX;
        private final double toY;
        private final double toZ;
        private final String particle;
        private int age;

        private Trail(World world, double fromX, double fromY, double fromZ,
                      double toX, double toY, double toZ, int itemId, int damage) {
            this.world = world;
            this.fromX = fromX;
            this.fromY = fromY;
            this.fromZ = fromZ;
            this.toX = toX;
            this.toY = toY;
            this.toZ = toZ;
            this.particle = "iconcrack_" + itemId + "_" + damage;
        }

        /**
         * @return false once the trail is finished
         */
        private boolean step() {
            if (this.age >= TRAIL_TICKS) return false;
            double t = (this.age + 1.0D) / TRAIL_TICKS;
            double x = this.fromX + (this.toX - this.fromX) * t;
            double y = this.fromY + (this.toY - this.fromY) * t;
            double z = this.fromZ + (this.toZ - this.fromZ) * t;
            try {
                ((WorldServer) this.world).func_147487_a(
                        this.particle, x, y, z,
                        PARTICLES_PER_TICK, 0.02D, 0.02D, 0.02D, 0.0D);
            } catch (Throwable ignored) {
                return false;
            }
            this.age++;
            return true;
        }
    }
}
