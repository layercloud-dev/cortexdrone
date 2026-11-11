package ir.layercloud.cortexdrone.action.interact.impl;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.interact.InteractionRequest;
import ir.layercloud.cortexdrone.action.interact.InteractionResult;
import ir.layercloud.cortexdrone.action.interact.InteractionState;
import ir.layercloud.cortexdrone.action.interact.InteractionSupport;
import ir.layercloud.cortexdrone.context.impl.InteractionContext;
import ir.layercloud.cortexdrone.service.combat.CombatMath;
import ir.layercloud.cortexdrone.service.inventory.ToolInspector;
import ir.layercloud.cortexdrone.service.world.BlockSight;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;

/**
 * Single-use command: attack one entity with the equipped drone weapon.
 */
@RequiredArgsConstructor
public final class AttackEntityAction implements Action<InteractionContext> {
    private final InteractionState state;
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
        if (distance > BlockSight.REACH) {
            context.result(InteractionResult.movement(
                    new double[]{target.posX, target.posY + 0.5, target.posZ},
                    "needsMovement", true,
                    "action", "attack",
                    "entityId", id,
                    "distance", Math.round(distance * 100.0) / 100.0));
            return;
        }
        InteractionState state = this.state;
        if (state.attackCooldown > 0) {
            int waitMs = state.attackCooldown * 50;
            context.result(InteractionResult.failure(
                    "Attack cooling down", "retryTicks", state.attackCooldown,
                    "retryAfterMs", waitMs));
            return;
        }

        IInventory inventory = drone.mainInventory();
        int size = inventory != null ? inventory.getSizeInventory() : 0;
        // A drone with no inventory slots can still punch. Only an explicitly
        // requested slot is an error when it does not exist.
        int slot = request.hasSlot() ? request.getSlot() : -1;
        String filter = request.getItem();
        if (filter != null) {
            int found = ToolInspector.findMatchingWeaponSlot(inventory, filter);
            if (found < 0) {
                context.result(InteractionResult.failure(
                        "No matching weapon/item in drone inventory for filter: " + filter));
                return;
            }
            slot = found;
        } else if (slot < 0 && size > 0) {
            // Select the best weapon, like a player would. Falls back to the
            // held slot and then to an unarmed strike.
            slot = ToolInspector.findBestWeaponSlot(inventory);
            if (slot < 0) {
                int held = fakePlayer.inventory != null ? fakePlayer.inventory.currentItem : 0;
                slot = Math.max(0, Math.min(size - 1, held));
            }
        }
        if (slot >= size) {
            context.result(InteractionResult.failure(
                    "Invalid attack slot " + slot + " (drone inventory has " + size + " slot(s))"));
            return;
        }

        if (fakePlayer.inventory != null) {
            fakePlayer.inventory.currentItem = slot >= 0 ? Math.min(8, slot) : 0;
        }
        InteractionSupport.syncFakePlayerToDrone(fakePlayer, drone);
        double dx = target.posX - drone.posX;
        double dy = target.posY - drone.posY;
        double dz = target.posZ - drone.posZ;
        fakePlayer.rotationYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        fakePlayer.rotationPitch = (float) (-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);

        EntityLivingBase living = (EntityLivingBase) target;
        float before = living.getHealth();
        ItemStack weapon = inventory == null || slot < 0 ? null : inventory.getStackInSlot(slot);
        float damage = ToolInspector.computeAttackDamage(inventory, slot, living);
        int fireSeconds = ToolInspector.getFireAspectSeconds(inventory, slot);
        if (fireSeconds > 0) living.setFire(fireSeconds);
        try {
            living.attackEntityFrom(DamageSource.causePlayerDamage(fakePlayer), damage);
        } catch (Throwable failure) {
            context.result(InteractionResult.failure(
                    "Attack failed: " + failure.getClass().getSimpleName()));
            return;
        }

        state.attackCooldown = 4;
        float after = living.getHealth();
        float damageDealt = Math.max(0.0f, before - after);
        boolean killed = target.isDead || after <= 0.0f;
        int hurtWaitMs = CombatMath.hurtWaitMs(
                Math.max(0, living.hurtResistantTime), Math.max(0, living.maxHurtResistantTime),
                state.attackCooldown);
        int attacksRemaining = killed || damageDealt <= 0.0f ? (killed ? 0 : -1)
                : (int) Math.ceil((after + Math.max(0.0f, living.getAbsorptionAmount())) / damageDealt);
        context.result(InteractionResult.success(
                "attacked", true,
                "entityId", id,
                "name", target.getCommandSenderName(),
                "healthBefore", before,
                "healthAfter", after,
                "maxHealth", living.getMaxHealth(),
                "absorption", living.getAbsorptionAmount(),
                "damage", Math.round(damage * 10.0) / 10.0,
                "damageDealt", Math.round(damageDealt * 10.0) / 10.0,
                "attacksRemaining", attacksRemaining,
                "nextAttackInMs", hurtWaitMs,
                "killed", killed,
                "item", weapon != null && weapon.getItem() != null ? weapon.getDisplayName() : "Fist",
                "distance", Math.round(distance * 100.0) / 100.0));
    }
}
