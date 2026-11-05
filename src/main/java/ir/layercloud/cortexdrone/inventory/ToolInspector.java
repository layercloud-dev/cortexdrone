package ir.layercloud.cortexdrone.inventory;

import lombok.experimental.UtilityClass;
import net.minecraft.block.Block;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;

/**
 * Dynamically inspects tools and weapons in the drone's inventory for dig speed,
 * Efficiency, Fortune, Silk Touch, Sharpness/Smite, and Fire Aspect enchantments.
 */
@UtilityClass
public final class ToolInspector {

    public static int getBestFortuneLevel(IInventory inv) {
        if (inv == null) return 0;
        int best = 0;
        int fortuneId = Enchantment.fortune != null ? Enchantment.fortune.effectId : 35;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
            int lvl = EnchantmentHelper.getEnchantmentLevel(fortuneId, st);
            if (lvl > best) best = lvl;
        }
        return Math.min(5, best);
    }

    public static int computeRequiredMiningTicks(IInventory inv, Block block, float hardness) {
        float baseTicks = Math.max(4.0f, Math.min(16.0f, (float) Math.ceil(hardness * 3.0f)));
        if (inv == null || block == null) return (int) baseTicks;

        float bestSpeedBonus = 1.0f;
        int effId = Enchantment.efficiency != null ? Enchantment.efficiency.effectId : 32;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
            try {
                float digSpeed = st.func_150997_a(block);
                int effLvl = EnchantmentHelper.getEnchantmentLevel(effId, st);
                if (effLvl > 0) {
                    digSpeed += effLvl * effLvl + 1.0f;
                }
                if (digSpeed > bestSpeedBonus) {
                    bestSpeedBonus = digSpeed;
                }
            } catch (Throwable ignored) {
            }
        }
        if (bestSpeedBonus > 1.5f) {
            return Math.max(2, Math.round(baseTicks / Math.min(4.0f, (float) Math.sqrt(bestSpeedBonus))));
        }
        return (int) baseTicks;
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
        int fireId = Enchantment.fireAspect != null ? Enchantment.fireAspect.effectId : 20;
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
