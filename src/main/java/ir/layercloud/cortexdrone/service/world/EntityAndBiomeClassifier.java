package ir.layercloud.cortexdrone.service.world;

import lombok.experimental.UtilityClass;
import net.minecraft.entity.*;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.passive.IAnimals;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.BiomeDictionary;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Dynamic entity hostility/type classifier and Forge BiomeDictionary matcher.
 * Uses EnumCreatureType, EntityList, SharedMonsterAttributes, and BiomeGenBase registries.
 */
@UtilityClass
public final class EntityAndBiomeClassifier {
    private static volatile Set<String> cachedBiomeTokens = Collections.emptySet();
    private static Method entityIsCreatureTypeMethod = null;

    static {
        try {
            entityIsCreatureTypeMethod = Entity.class.getMethod("isCreatureType", EnumCreatureType.class, boolean.class);
        } catch (Throwable ignored) {
        }
    }

    public static boolean isCreatureOfType(EntityLivingBase el, EnumCreatureType type) {
        if (el == null || type == null) return false;
        if (entityIsCreatureTypeMethod != null) {
            try {
                Object r = entityIsCreatureTypeMethod.invoke(el, type, Boolean.FALSE);
                if (r instanceof Boolean) {
                    return (Boolean) r;
                }
            } catch (Throwable ignored) {
            }
        }
        Class<?> creatureClass = type.getCreatureClass();
        return creatureClass != null && creatureClass.isAssignableFrom(el.getClass());
    }

    public static boolean isHostileEntity(EntityLivingBase el) {
        if (el == null || el.isDead || el.getHealth() <= 0.0f) return false;
        if (el instanceof EntityPlayer) return false;
        if (el instanceof IMob || el instanceof EntityMob) return true;
        if (isCreatureOfType(el, EnumCreatureType.monster)) return true;

        if (el instanceof EntityTameable && ((EntityTameable) el).isTamed()) {
            return false;
        }
        if (el instanceof EntityLiving) {
            EntityLiving living = (EntityLiving) el;
            if (living.getAttackTarget() instanceof EntityPlayer || living.getAITarget() instanceof EntityPlayer) {
                return true;
            }
        }
        if (!(el instanceof EntityAnimal) && !(el instanceof IAnimals)) {
            try {
                if (el.getAttributeMap() != null && el.getAttributeMap().getAttributeInstance(SharedMonsterAttributes.attackDamage) != null) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static boolean isPassiveEntity(EntityLivingBase el) {
        if (el == null || el.isDead || el.getHealth() <= 0.0f) return false;
        if (el instanceof EntityPlayer || isHostileEntity(el)) return false;
        return (el instanceof EntityAnimal)
                || (el instanceof IAnimals)
                || isCreatureOfType(el, EnumCreatureType.creature)
                || isCreatureOfType(el, EnumCreatureType.waterCreature)
                || isCreatureOfType(el, EnumCreatureType.ambient);
    }

    public static String getEntityIdentifier(EntityLivingBase el) {
        if (el == null) return "";
        String cmdName = el.getCommandSenderName();
        String regName = null;
        try {
            regName = EntityList.getEntityString(el);
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

        String bName = biome.biomeName != null ? biome.biomeName.toLowerCase() : "";
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
            BiomeGenBase[] allBiomes = BiomeGenBase.getBiomeGenArray();
            if (allBiomes != null) {
                for (BiomeGenBase b : allBiomes) {
                    if (b != null && b.biomeName != null) {
                        for (String w : LexicalItemMatcher.tokenize(b.biomeName)) {
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
