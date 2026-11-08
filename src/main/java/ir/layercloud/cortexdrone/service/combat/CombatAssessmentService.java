package ir.layercloud.cortexdrone.service.combat;

import ir.layercloud.cortexdrone.service.inventory.ToolInspector;
import ir.layercloud.cortexdrone.service.world.RegistryIntrospector;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.world.World;

/**
 * Read-only combat reconnaissance for the harness.
 *
 * <p>It answers two questions about one entity id: how much health it has, and
 * how many landed melee hits the drone needs to finish it. The service never
 * attacks, moves or mutates state; it only reads the live entity, the drone's
 * inventory and the vanilla damage rules.</p>
 */
@RequiredArgsConstructor
public final class CombatAssessmentService {
    /** The interaction action refuses an attack beyond this distance. */
    public static final double ATTACK_REACH = 4.5;

    public CombatAssessment assess(Drone drone, int entityId, String itemFilter, Integer requestedSlot) {
        if (drone == null || drone.worldObj == null) return CombatAssessment.notFound(entityId);
        World world = drone.worldObj;
        Entity entity = world.getEntityByID(entityId);
        if (!(entity instanceof EntityLivingBase)) return CombatAssessment.notFound(entityId);

        EntityLivingBase target = (EntityLivingBase) entity;
        boolean player = target instanceof EntityPlayer;
        float health = target.getHealth();
        float maxHealth = target.getMaxHealth();
        float absorption = target.getAbsorptionAmount();
        boolean alive = !target.isDead && health > 0.0f;

        int armor = safeArmor(target);
        int resistanceLevel = resistanceLevel(target);
        int hurtResistantTime = Math.max(0, target.hurtResistantTime);
        int maxHurtResistantTime = Math.max(0, target.maxHurtResistantTime);
        boolean immune = hurtResistantTime > maxHurtResistantTime / 2;

        IInventory inventory = drone.mainInventory();
        int size = inventory != null ? inventory.getSizeInventory() : 0;
        int slot = resolveSlot(drone, inventory, size, itemFilter, requestedSlot);
        boolean weaponRequested = (itemFilter != null && !itemFilter.trim().isEmpty())
                || requestedSlot != null;
        boolean weaponMissing = weaponRequested && (slot < 0 || slot >= size);
        ItemStack weapon = slot >= 0 && slot < size ? inventory.getStackInSlot(slot) : null;
        String weaponName = weapon != null && weapon.getItem() != null
                ? weapon.getDisplayName() : (weaponMissing ? "missing requested weapon" : "Fist");
        // A missing explicit weapon must not be forecast as a fist: the attack
        // action refuses the swing, so the plan must report it as blocked.
        float rawDamage = weaponMissing ? 0.0f
                : ToolInspector.computeAttackDamage(inventory, slot, target);
        float damagePerHit = CombatMath.mitigatedDamage(rawDamage, armor, resistanceLevel);

        boolean invulnerable = isInvulnerable(target);
        boolean canDamage = alive && !player && !invulnerable && damagePerHit > 0.0f;
        float totalHealth = Math.max(0.0f, health + absorption);
        int attacksToKill = alive
                ? CombatMath.attacksToKill(totalHealth, canDamage ? damagePerHit : 0.0f)
                : 0;
        int nextAttackInMs = CombatMath.hurtWaitMs(hurtResistantTime,
                maxHurtResistantTime, CombatMath.ATTACK_COOLDOWN_TICKS);
        double estimatedSeconds = CombatMath.estimatedSeconds(attacksToKill, nextAttackInMs);

        double distance = drone.getDistance(target.posX, target.posY, target.posZ);
        String blocker = null;
        if (!alive) blocker = "target is already dead";
        else if (player) blocker = "players are never attacked";
        else if (invulnerable) blocker = "target is invulnerable";
        else if (weaponMissing) blocker = "requested weapon is not in the drone inventory";
        else if (damagePerHit <= 0.0f) blocker = "armor/resistance fully absorbs this weapon";

        return CombatAssessment.builder()
                .found(true)
                .entityId(entityId)
                .name(target.getCommandSenderName())
                .type(target.getClass().getSimpleName())
                .alive(alive)
                .hostile(RegistryIntrospector.isHostileEntity(target))
                .animal(target instanceof EntityAnimal)
                .player(player)
                .attackable(canDamage)
                .health(round2(health))
                .maxHealth(round2(maxHealth))
                .absorption(round2(absorption))
                .totalHealth(round2(totalHealth))
                .armor(armor)
                .resistanceLevel(resistanceLevel)
                .hurtResistantTime(hurtResistantTime)
                .immune(immune)
                .nextAttackInMs(nextAttackInMs)
                .x(round2(target.posX))
                .y(round2(target.posY))
                .z(round2(target.posZ))
                .distance(round2(distance))
                .inReach(distance <= ATTACK_REACH)
                .weaponAvailable(weapon != null)
                .weapon(weaponName)
                .weaponSlot(slot)
                .rawDamage(round2(rawDamage))
                .damagePerHit(round2(damagePerHit))
                .canDamage(canDamage)
                .attacksToKill(attacksToKill)
                .estimatedSeconds(round2(estimatedSeconds))
                .blocker(blocker)
                .build();
    }

    /**
     * Mirrors {@code AttackEntityAction} weapon selection: an explicit item
     * filter or slot wins, otherwise the best weapon, otherwise the held slot.
     */
    private static int resolveSlot(Drone drone, IInventory inventory, int size,
                                   String itemFilter, Integer requestedSlot) {
        if (itemFilter != null && !itemFilter.trim().isEmpty()) {
            int found = ToolInspector.findMatchingWeaponSlot(inventory, itemFilter);
            return found >= 0 ? found : -1;
        }
        int slot = requestedSlot != null ? requestedSlot : -1;
        if (slot < 0 && size > 0) {
            slot = ToolInspector.findBestWeaponSlot(inventory);
            if (slot < 0) {
                int held = 0;
                try {
                    if (drone.player() != null && drone.player().inventory != null) {
                        held = drone.player().inventory.currentItem;
                    }
                } catch (Throwable ignored) {
                }
                slot = Math.max(0, Math.min(size - 1, held));
            }
        }
        return slot;
    }

    private static int safeArmor(EntityLivingBase target) {
        try {
            return Math.max(0, target.getTotalArmorValue());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int resistanceLevel(EntityLivingBase target) {
        try {
            PotionEffect effect = target.getActivePotionEffect(Potion.resistance);
            return effect != null ? effect.getAmplifier() + 1 : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static boolean isInvulnerable(EntityLivingBase target) {
        try {
            return target.isEntityInvulnerable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static float round2(float value) {
        return Math.round(value * 100.0f) / 100.0f;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
