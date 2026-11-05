package ir.layercloud.cortexdrone.harness;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * Master place primitive: exactly one explicit block from drone/chest/player
 * inventory. No blueprint, no build queue, no hidden substitution.
 */
public final class BlockPlacePrimitive {
    private BlockPlacePrimitive() {
    }

    public static boolean placeFromInventory(AIDroneManager manager, World world, DroneController ctrl,
                                             EntityPlayerMP owner, int x, int y, int z,
                                             String filter, int metaOverride) {
        if (!ItemClassifier.isReplaceableBlock(world, x, y, z)) return false;
        IInventory inv = ctrl.drone.mainInventory();
        int slot = InventoryService.findPlaceableItemSlot(inv, filter);
        if (slot < 0) {
            int pulled = manager.getInventoryService().withdrawMatchingFromNearbyChests(world, ctrl, owner, filter, 64);
            if (pulled > 0) slot = InventoryService.findPlaceableItemSlot(inv, filter);
            if (slot < 0 && owner != null) {
                int taken = manager.getInventoryService().takeFromPlayerInventory(ctrl, owner, filter, 64);
                if (taken > 0) slot = InventoryService.findPlaceableItemSlot(inv, filter);
            }
        }
        if (slot < 0) return false;

        ItemStack st = inv.func_70301_a(slot);
        if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) return false;
        Item item = st.func_77973_b();
        Block block = Block.func_149634_a(item);
        if (block == null || block == Blocks.field_150350_a) return false;
        int meta = metaOverride >= 0 ? metaOverride : item.func_77647_b(st.func_77960_j());

        boolean placed = world.func_147465_d(x, y, z, block, meta, 3);
        if (placed) {
            world.func_72926_e(2001, x, y, z, Block.func_149682_b(block) + (meta << 12));
            inv.func_70298_a(slot, 1);
            inv.func_70296_d();
            ctrl.builtCount++;
            manager.getLogger().log("BUILD", ctrl,
                String.format("Placed %s at (%d,%d,%d)", block.func_149732_F(), x, y, z), "");
        }
        return placed;
    }
}
