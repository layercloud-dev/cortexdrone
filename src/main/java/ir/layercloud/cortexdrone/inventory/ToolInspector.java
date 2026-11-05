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
        int fortuneId = Enchantment.field_77346_s != null ? Enchantment.field_77346_s.field_77352_x : 35;
        for (int i = 0; i < inv.func_70302_i_(); i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
            int lvl = EnchantmentHelper.func_77506_a(fortuneId, st);
            if (lvl > best) best = lvl;
        }
        return Math.min(5, best);
    }

    public static int computeRequiredMiningTicks(IInventory inv, Block block, float hardness) {
        float baseTicks = Math.max(4.0f, Math.min(16.0f, (float) Math.ceil(hardness * 3.0f)));
        if (inv == null || block == null) return (int) baseTicks;

        float bestSpeedBonus = 1.0f;
        int effId = Enchantment.field_77349_p != null ? Enchantment.field_77349_p.field_77352_x : 32;
        for (int i = 0; i < inv.func_70302_i_(); i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
            try {
                float digSpeed = st.func_150997_a(block);
                int effLvl = EnchantmentHelper.func_77506_a(effId, st);
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
        for (int i = 0; i < inv.func_70302_i_(); i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) continue;
            float weaponBonus = 0.0f;
            if (st.func_77973_b() instanceof ItemSword) {
                weaponBonus = 4.0f + ((ItemSword) st.func_77973_b()).func_150931_i();
            } else if (st.func_77973_b() instanceof ItemTool) {
                weaponBonus = 2.5f;
            }
            if (target != null) {
                try {
                    weaponBonus += EnchantmentHelper.func_152377_a(st, target.func_70668_bt());
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
        int fireId = Enchantment.field_77334_n != null ? Enchantment.field_77334_n.field_77352_x : 20;
        for (int i = 0; i < inv.func_70302_i_(); i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st != null && st.field_77994_a > 0) {
                int lvl = EnchantmentHelper.func_77506_a(fireId, st);
                if (lvl > best) best = lvl;
            }
        }
        return best * 4;
    }
}
