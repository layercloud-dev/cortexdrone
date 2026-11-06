package ir.layercloud.cortexdrone.action.interact.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.interact.InteractionRequest;
import ir.layercloud.cortexdrone.action.interact.InteractionResult;
import ir.layercloud.cortexdrone.action.interact.InteractionSupport;
import ir.layercloud.cortexdrone.context.action.interact.impl.InteractionContext;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/** Single-use command: use one held item. */
@RequiredArgsConstructor
public final class UseItemAction implements Action<InteractionContext> {
    private final InteractionRequest request;

    @Override
    public void execute(InteractionContext context) {
        DroneController controller = context.controller();
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            context.result(InteractionResult.failure("No active drone or world", "used", false));
            return;
        }

        Drone drone = controller.drone;
        World world = drone.worldObj;
        EntityPlayer fakePlayer = drone.player();
        if (fakePlayer == null || fakePlayer.inventory == null) {
            context.result(InteractionResult.failure("No fake player on drone", "used", false));
            return;
        }

        int slot = request.hasSlot()
            ? request.getSlot()
            : fakePlayer.inventory.currentItem;
        String filter = request.getItem();
        if (filter != null) {
            int found = findItemSlot(fakePlayer, filter);
            if (found < 0) {
                context.result(InteractionResult.failure(
                    "No matching item in drone inventory for filter: " + filter, "used", false));
                return;
            }
            slot = found;
        }

        ItemStack stack = slot >= 0 && slot < fakePlayer.inventory.getSizeInventory()
            ? fakePlayer.inventory.getStackInSlot(slot) : null;
        if (stack == null || stack.getItem() == null) {
            context.result(InteractionResult.failure("No item in slot " + slot, "used", false));
            return;
        }

        if (slot < 9) fakePlayer.inventory.currentItem = slot;
        InteractionSupport.syncFakePlayerToDrone(fakePlayer, drone);

        int x = request.isCoordinates() ? request.getX() : MathHelper.floor_double(drone.posX);
        int y = request.isCoordinates() ? request.getY() : MathHelper.floor_double(drone.posY) - 1;
        int z = request.isCoordinates() ? request.getZ() : MathHelper.floor_double(drone.posZ);

        Item item = stack.getItem();
        int beforeCount = stack.stackSize;
        int beforeDamage = stack.getItemDamage();
        boolean used = false;
        String method = "none";
        if (request.isCoordinates()) {
            try {
                used = item.onItemUse(stack, fakePlayer, world, x, y, z,
                    request.getSide(), 0.5f, 0.5f, 0.5f);
                method = "onItemUse";
            } catch (Throwable failure) {
                method = "onItemUse:error:" + failure.getClass().getSimpleName();
            }
        } else {
            try {
                ItemStack after = item.onItemRightClick(stack, world, fakePlayer);
                used = after != null && (after != stack
                    || after.stackSize != beforeCount
                    || after.getItemDamage() != beforeDamage);
                method = "onItemRightClick";
            } catch (Throwable failure) {
                method = "onItemRightClick:error:" + failure.getClass().getSimpleName();
            }
        }

        Block targetBlock = world.getBlock(x, y, z);
        context.result(InteractionResult.success(
            "used", used,
            "method", method,
            "slot", slot,
            "item", stack.getDisplayName(),
            "x", x,
            "y", y,
            "z", z,
            "side", request.getSide(),
            "block", ItemClassifier.getBlockDisplayName(
                targetBlock, world.getBlockMetadata(x, y, z))));
    }

    private static int findItemSlot(EntityPlayer fakePlayer, String filter) {
        int size = fakePlayer.inventory.getSizeInventory();
        for (int pass = 0; pass < 2; pass++) {
            int from = pass == 0 ? 0 : 9;
            int to = pass == 0 ? Math.min(9, size) : size;
            for (int i = from; i < to; i++) {
                ItemStack candidate = fakePlayer.inventory.getStackInSlot(i);
                if (candidate == null || candidate.getItem() == null) continue;
                if (filter.isEmpty() || ItemClassifier.scoreBlockOrItemMatch(
                    candidate.getDisplayName(), ItemClassifier.getItemIdentifier(candidate),
                    candidate.getItemDamage(), filter) > 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
