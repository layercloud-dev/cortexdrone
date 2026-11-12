package ir.layercloud.cortexdrone.service.inventory;

import li.cil.oc.common.entity.Drone;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import ir.layercloud.cortexdrone.service.world.LootValue;

import java.util.List;

/**
 * Picking items up off the ground and dropping them back.
 *
 * <p>Extracted from {@code InventoryService}. Both operations move real item
 * entities, so they are kept together: pickup must account for a partial insert
 * (the stack stays on the ground with the remainder), and a drop must clear the
 * slot only after the entity exists.</p>
 *
 * <p>Pickup takes a filter. Mining leaves cobblestone in the same spot as ore,
 * and the drone has eight slots, so {@code valuable} absorption is what makes
 * "pick up the good drops" affordable. Everything not matching stays on the
 * ground untouched.</p>
 */
public final class GroundItems {
    /**
     * Pickup sound played when at least one item is absorbed.
     */
    private static final String PICKUP_SOUND = "random.pop";

    private GroundItems() {
    }

    /**
     * Absorbs nearby dropped items into the drone's inventory.
     */
    public static void suck(Drone drone, double x, double y, double z, double radius) {
        suck(drone, x, y, z, radius, "all");
    }

    /**
     * Absorbs nearby drops matching {@code filter} into the drone inventory.
     *
     * @param filter {@code all}, {@code valuable}, {@code junk} or a name/tag
     * @return number of items absorbed
     */
    public static int suck(Drone drone, double x, double y, double z, double radius, String filter) {
        if (drone == null) return 0;
        World world = drone.worldObj;
        IInventory inventory = drone.mainInventory();
        if (world == null || inventory == null) return 0;

        AxisAlignedBB box = AxisAlignedBB.getBoundingBox(
                x - radius, y - radius, z - radius,
                x + radius, y + radius, z + radius);
        List<?> entities = world.getEntitiesWithinAABB(EntityItem.class, box);
        if (entities == null || entities.isEmpty()) return 0;

        int picked = 0;
        for (Object candidate : entities) {
            if (!(candidate instanceof EntityItem)) continue;
            EntityItem entity = (EntityItem) candidate;
            if (entity.isDead) continue;
            ItemStack stack = entity.getEntityItem();
            if (stack == null || stack.stackSize <= 0) continue;
            if (!LootValue.matchesFilter(stack, filter)) continue;
            int before = stack.stackSize;
            InventoryTransfers.insertIntoInventory(inventory, stack);
            picked += before - Math.max(0, stack.stackSize);
            if (stack.stackSize <= 0) {
                entity.setDead();
            } else if (stack.stackSize != before) {
                // A partial insert leaves the remainder on the ground.
                entity.setEntityItemStack(stack);
            }
        }
        if (picked > 0) world.playSoundAtEntity(drone, PICKUP_SOUND, 0.25f, 1.6f);
        return picked;
    }

    /**
     * Drops every item in the drone's inventory at a point.
     *
     * @return the number of items dropped
     */
    public static int dropAll(Drone drone, World world, double x, double y, double z) {
        if (drone == null) return 0;
        IInventory inventory = drone.mainInventory();
        if (inventory == null || world == null) return 0;
        int dropped = 0;
        int size = inventory.getSizeInventory();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack == null || stack.stackSize <= 0) continue;
            dropped += stack.stackSize;
            EntityItem entity = new EntityItem(world, x, y, z, stack.copy());
            entity.delayBeforeCanPickup = 0;
            world.spawnEntityInWorld(entity);
            inventory.setInventorySlotContents(slot, null);
        }
        inventory.markDirty();
        return dropped;
    }
}
