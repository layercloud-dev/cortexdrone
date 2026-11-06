package ir.layercloud.cortexdrone.action.interact.impl;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.interact.InteractionRequest;
import ir.layercloud.cortexdrone.action.interact.InteractionResult;
import ir.layercloud.cortexdrone.action.interact.InteractionSupport;
import ir.layercloud.cortexdrone.context.interact.impl.InteractionContext;
import ir.layercloud.cortexdrone.inventory.ToolInspector;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;

/** Single-use command: attack one entity with the equipped drone weapon. */
@RequiredArgsConstructor
public final class AttackEntityAction implements Action<InteractionContext> {
    private final InteractionRequest request;

    @Override
    public void execute(InteractionContext context) {
        DroneController controller = context.controller();
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            context.result(InteractionResult.failure("No active drone or world"));
            return;
        }

        Drone drone = controller.drone;
        World world = drone.worldObj;
        EntityPlayer fakePlayer = drone.player();
        if (fakePlayer == null || fakePlayer.inventory == null) {
            context.result(InteractionResult.failure("No fake player on drone"));
            return;
        }

        int id = request.getEntityId();
        context.targetEntity(id);
        Entity target = world.getEntityByID(id);
        if (!(target instanceof EntityLivingBase) || target.isDead
            || ((EntityLivingBase) target).getHealth() <= 0.0f) {
            context.result(InteractionResult.failure("No living entity with id " + id));
            return;
        }
        if (target instanceof EntityPlayer) {
            context.result(InteractionResult.failure("Refusing to attack a player"));
            return;
        }

        double distance = drone.getDistance(target.posX, target.posY, target.posZ);
        if (distance > 4.5) {
            context.result(InteractionResult.movement(
                new double[]{target.posX, target.posY + 0.5, target.posZ},
                "needsMovement", true,
                "action", "attack",
                "entityId", id,
                "distance", Math.round(distance * 100.0) / 100.0));
            return;
        }
        if (controller.attackCooldown > 0) {
            context.result(InteractionResult.failure(
                "Attack cooling down", "retryTicks", controller.attackCooldown));
            return;
        }

        IInventory inventory = drone.mainInventory();
        int size = inventory != null ? inventory.getSizeInventory() : 0;
        int slot = request.hasSlot() ? request.getSlot() : fakePlayer.inventory.currentItem;
        String filter = request.getItem();
        if (filter != null) {
            int found = findWeaponSlot(inventory, size, filter);
            if (found < 0) {
                context.result(InteractionResult.failure(
                    "No matching weapon/item in drone inventory for filter: " + filter));
                return;
            }
            slot = found;
        }
        if (slot < 0 || slot >= size) {
            context.result(InteractionResult.failure("Invalid attack slot " + slot));
            return;
        }
        if (slot >= 9) {
            context.result(InteractionResult.failure(
                "Weapon must be in the drone's hotbar (slot 0-8)"));
            return;
        }

        fakePlayer.inventory.currentItem = slot;
        InteractionSupport.syncFakePlayerToDrone(fakePlayer, drone);
        double dx = target.posX - drone.posX;
        double dy = target.posY - drone.posY;
        double dz = target.posZ - drone.posZ;
        fakePlayer.rotationYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        fakePlayer.rotationPitch = (float) (-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);

        EntityLivingBase living = (EntityLivingBase) target;
        float before = living.getHealth();
        ItemStack weapon = inventory.getStackInSlot(slot);
        float damage = ToolInspector.computeAttackDamage(inventory, living, 20.0f);
        int fireSeconds = ToolInspector.getFireAspectSeconds(inventory);
        if (fireSeconds > 0) living.setFire(fireSeconds);
        try {
            living.attackEntityFrom(DamageSource.causePlayerDamage(fakePlayer), damage);
        } catch (Throwable failure) {
            context.result(InteractionResult.failure(
                "Attack failed: " + failure.getClass().getSimpleName()));
            return;
        }

        controller.attackCooldown = 4;
        float after = living.getHealth();
        context.result(InteractionResult.success(
            "attacked", true,
            "entityId", id,
            "name", target.getCommandSenderName(),
            "healthBefore", before,
            "healthAfter", after,
            "damage", Math.round(damage * 10.0) / 10.0,
            "killed", target.isDead || after <= 0.0f,
            "item", weapon != null && weapon.getItem() != null ? weapon.getDisplayName() : "Fist",
            "distance", Math.round(distance * 100.0) / 100.0));
    }

    private static int findWeaponSlot(IInventory inventory, int size, String filter) {
        for (int i = 0; i < size; i++) {
            ItemStack candidate = inventory.getStackInSlot(i);
            if (candidate == null || candidate.getItem() == null) continue;
            if (ItemClassifier.scoreBlockOrItemMatch(
                candidate.getDisplayName(), ItemClassifier.getItemIdentifier(candidate),
                candidate.getItemDamage(), filter) > 0) {
                return i;
            }
        }
        return -1;
    }
}
