package ir.layercloud.cortexdrone.world;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.passive.IAnimals;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.BiomeDictionary;

/**
 * Dynamic entity hostility/type classifier and Forge BiomeDictionary matcher.
 * Uses EnumCreatureType, EntityList, SharedMonsterAttributes, and BiomeGenBase registries.
 */
public final class EntityAndBiomeClassifier {
    private static volatile Set<String> cachedBiomeTokens = Collections.emptySet();
    private static Method entityIsCreatureTypeMethod = null;

    static {
        try {
            entityIsCreatureTypeMethod = Entity.class.getMethod("isCreatureType", EnumCreatureType.class, boolean.class);
        } catch (Throwable ignored) {
        }
    }

    private EntityAndBiomeClassifier() {
    }

    public static boolean isCreatureOfType(EntityLivingBase el, EnumCreatureType type) {
        if (el == null || type == null) return false;
        if (entityIsCreatureTypeMethod != null) {
            try {
                Object r = entityIsCreatureTypeMethod.invoke(el, type, Boolean.FALSE);
                if (r instanceof Boolean) {
                    return ((Boolean) r).booleanValue();
                }
            } catch (Throwable ignored) {
            }
        }
        Class<?> creatureClass = type.func_75598_a();
        return creatureClass != null && creatureClass.isAssignableFrom(el.getClass());
    }

    public static boolean isHostileEntity(EntityLivingBase el) {
        if (el == null || el.field_70128_L || el.func_110143_aJ() <= 0.0f) return false;
        if (el instanceof EntityPlayer) return false;
        if (el instanceof IMob || el instanceof EntityMob) return true;
        if (isCreatureOfType(el, EnumCreatureType.monster)) return true;

        if (el instanceof EntityTameable && ((EntityTameable) el).func_70909_n()) {
            return false;
        }
        if (el instanceof EntityLiving) {
            EntityLiving living = (EntityLiving) el;
            if (living.func_70638_az() instanceof EntityPlayer || living.func_70643_av() instanceof EntityPlayer) {
                return true;
            }
        }
        if (!(el instanceof EntityAnimal) && !(el instanceof IAnimals)) {
            try {
                if (el.func_110140_aT() != null && el.func_110140_aT().func_111151_a(SharedMonsterAttributes.field_111264_e) != null) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static boolean isPassiveEntity(EntityLivingBase el) {
        if (el == null || el.field_70128_L || el.func_110143_aJ() <= 0.0f) return false;
        if (el instanceof EntityPlayer || isHostileEntity(el)) return false;
        return (el instanceof EntityAnimal)
            || (el instanceof IAnimals)
            || isCreatureOfType(el, EnumCreatureType.creature)
            || isCreatureOfType(el, EnumCreatureType.waterCreature)
            || isCreatureOfType(el, EnumCreatureType.ambient);
    }

    public static String getEntityIdentifier(EntityLivingBase el) {
        if (el == null) return "";
        String cmdName = el.func_70005_c_();
        String regName = null;
        try {
            regName = EntityList.func_75621_b(el);
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder();
        if (cmdName != null) sb.append(cmdName).append(' ');
        if (regName != null) sb.append(regName).append(' ');
        Class<?> c = el.getClass();
        while (c != null && c != EntityLivingBase.class && c != Object.class) {
            sb.append(c.getSimpleName()).append(' ');
            c = c.getSuperclass();
        }
        return sb.toString().toLowerCase();
    }

    public static boolean matchesBiome(BiomeGenBase biome, String query) {
        if (biome == null || query == null) return false;
        List<String> qTokens = LexicalItemMatcher.tokenize(query);
        if (qTokens.isEmpty()) return false;

        String bName = biome.field_76791_y != null ? biome.field_76791_y.toLowerCase() : "";
        List<String> biomeTokens = new ArrayList<String>(LexicalItemMatcher.tokenize(bName));
        try {
            BiomeDictionary.Type[] types = BiomeDictionary.getTypesForBiome(biome);
            if (types != null) {
                for (BiomeDictionary.Type t : types) {
                    if (t != null) {
                        biomeTokens.add(t.name().toLowerCase());
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        for (String qt : qTokens) {
            if (qt.equals("log") || qt.equals("wood") || qt.equals("tree") || qt.equals("trees") || qt.equals("sapling")) {
                continue;
            }
            if (biomeTokens.contains(qt) || bName.contains(qt)) {
                return true;
            }
        }
        return false;
    }

    public static Set<String> getRegisteredBiomeTokens() {
        if (!cachedBiomeTokens.isEmpty()) return cachedBiomeTokens;
        Set<String> tokens = new HashSet<String>();
        try {
            BiomeGenBase[] allBiomes = BiomeGenBase.func_150565_n();
            if (allBiomes != null) {
                for (BiomeGenBase b : allBiomes) {
                    if (b != null && b.field_76791_y != null) {
                        for (String w : LexicalItemMatcher.tokenize(b.field_76791_y)) {
                            if (w.length() >= 3) tokens.add(w);
                        }
                    }
                }
            }
            for (BiomeDictionary.Type t : BiomeDictionary.Type.values()) {
                if (t != null) {
                    tokens.add(t.name().toLowerCase());
                }
            }
        } catch (Throwable ignored) {
        }
        cachedBiomeTokens = tokens;
        return cachedBiomeTokens;
    }

    public static boolean hasBiomeAffinity(String query) {
        if (query == null) return false;
        List<String> qTokens = LexicalItemMatcher.tokenize(query);
        Set<String> biomeTokens = getRegisteredBiomeTokens();
        for (String qt : qTokens) {
            if (qt.equals("log") || qt.equals("wood") || qt.equals("tree") || qt.equals("trees")
                || qt.equals("ore") || qt.equals("stone") || qt.equals("river") || qt.equals("ocean")) {
                continue;
            }
            if (biomeTokens.contains(qt)) {
                return true;
            }
        }
        return false;
    }
}
