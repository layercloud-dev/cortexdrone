package ir.layercloud.cortexdrone.service.inventory;

import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import lombok.experimental.UtilityClass;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;

/**
 * Dynamically inspects tools and weapons in the drone inventory for Fortune,
 * Sharpness/Smite and Fire Aspect enchantments.
 */
@UtilityClass
public final class ToolInspector {

    public static int getBestFortuneLevel(IInventory inv) {
        if (inv == null) return 0;
        int best = 0;
        int fortuneId = Enchantment.fortune.effectId;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
            int lvl = EnchantmentHelper.getEnchantmentLevel(fortuneId, st);
            if (lvl > best) best = lvl;
        }
        return Math.min(5, best);
    }

    /**
     * Vanilla-style attack damage for one selected inventory slot.
     *
     * <p>A fist is 1.0. A sword adds its own damage value; a tool adds a
     * conservative 2.0. Enchantments are added on top. The old code passed a
     * 20.0 base and scanned every slot for the best weapon, which killed most
     * mobs in one hit regardless of what the drone held.</p>
     *
     * @param slot selected slot, or negative for an unarmed strike
     */
    public static float computeAttackDamage(IInventory inv, int slot, EntityLivingBase target) {
        float damage = 1.0f;
        ItemStack weapon = stackIn(inv, slot);
        if (weapon != null && weapon.getItem() != null) {
            if (weapon.getItem() instanceof ItemSword) {
                damage += ((ItemSword) weapon.getItem()).func_150931_i();
            } else if (weapon.getItem() instanceof ItemTool) {
                damage += 2.0f;
            }
            if (target != null) {
                try {
                    damage += EnchantmentHelper.func_152377_a(weapon, target.getCreatureAttribute());
                } catch (Throwable ignored) {
                }
            }
        }
        return damage;
    }

    /**
     * Best weapon slot in the inventory, or -1 when there is nothing useful.
     * Choosing the sword is what a player does; it is not a damage bonus.
     */
    public static int findBestWeaponSlot(IInventory inv) {
        if (inv == null) return -1;
        int bestSlot = -1;
        float best = 0.0f;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) continue;
            float score = 0.0f;
            if (stack.getItem() instanceof ItemSword) {
                score = 3.0f + ((ItemSword) stack.getItem()).func_150931_i();
            } else if (stack.getItem() instanceof ItemTool) {
                score = 1.0f;
            }
            if (score > best) {
                best = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private static ItemStack stackIn(IInventory inv, int slot) {
        if (inv == null || slot < 0 || slot >= inv.getSizeInventory()) return null;
        return inv.getStackInSlot(slot);
    }

    /**
     * Slot of the first inventory item matching an item/ore/name filter, or -1.
     * Shared by {@code attack_entity} and the combat assessment service so a
     * forecast always uses the weapon the action would actually swing.
     */
    public static int findMatchingWeaponSlot(IInventory inv, String filter) {
        if (inv == null || filter == null || filter.trim().isEmpty()) return -1;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack candidate = inv.getStackInSlot(i);
            if (candidate == null || candidate.getItem() == null) continue;
            if (ItemClassifier.scoreBlockOrItemMatch(
                    candidate.getDisplayName(), ItemClassifier.getItemIdentifier(candidate),
                    candidate.getItemDamage(), filter) > 0) {
                return i;
            }
        }
        return -1;
    }

    /** Fire aspect on the selected weapon, in seconds. */
    public static int getFireAspectSeconds(IInventory inv, int slot) {
        ItemStack held = stackIn(inv, slot);
        if (held == null) return 0;
        try {
            int level = EnchantmentHelper.getEnchantmentLevel(Enchantment.fireAspect.effectId, held);
            return Math.max(0, level * 4);
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
