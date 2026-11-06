package ir.layercloud.cortexdrone.service.inventory;

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

    public static float computeAttackDamage(IInventory inv, EntityLivingBase target, float baseDamage) {
        if (inv == null) return baseDamage;
        float bestBonus = 0.0f;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
            float weaponBonus = 0.0f;
            if (st.getItem() instanceof ItemSword) {
                weaponBonus = 4.0f + ((ItemSword) st.getItem()).func_150931_i();
            } else if (st.getItem() instanceof ItemTool) {
                weaponBonus = 2.5f;
            }
            if (target != null) {
                try {
                    weaponBonus += EnchantmentHelper.func_152377_a(st, target.getCreatureAttribute());
                } catch (Throwable ignored) {
                }
            }
            if (weaponBonus > bestBonus) {
                bestBonus = weaponBonus;
            }
        }
        return baseDamage + bestBonus;
    }

    public static int getFireAspectSeconds(IInventory inv) {
        if (inv == null) return 0;
        int best = 0;
        int fireId = Enchantment.fireAspect.effectId;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st != null && st.stackSize > 0) {
                int lvl = EnchantmentHelper.getEnchantmentLevel(fireId, st);
                if (lvl > best) best = lvl;
            }
        }
        return best * 4;
    }
}
