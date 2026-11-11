package ir.layercloud.cortexdrone.service.world;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

import java.util.List;
import java.util.Locale;

/**
 * Entity-category filters for {@code get_entities}.
 *
 * <p>Extracted from {@code ItemClassifier}, which had grown to cover blocks,
 * items, entities and JSON. The filter language is the same "comma/or separated
 * clauses" shape the item filters use, so it reuses {@link LexicalItemMatcher}
 * for tokenizing and singularizing.</p>
 */
public final class EntityClassifier {
    /**
     * Default filter when the caller names none.
     */
    static final String DEFAULT_FILTER = "animal";

    private EntityClassifier() {
    }

    /**
     * True when the entity is hostile or a mutant variant.
     */
    public static boolean isHostile(EntityLivingBase entity) {
        return RegistryIntrospector.isHostileEntity(entity);
    }

    /**
     * True when the entity is passive (an animal or similar).
     */
    public static boolean isPassive(EntityLivingBase entity) {
        return RegistryIntrospector.isPassiveEntity(entity);
    }

    /**
     * True when {@code entity} matches the filter expression.
     *
     * <p>Accepted clauses: {@code all}/{@code any}, {@code animal}/{@code passive},
     * {@code player}, {@code hostile}/{@code monster}/{@code mutant}, or any
     * substring of the entity's identifier.</p>
     */
    public static boolean matches(EntityLivingBase entity, String filter) {
        if (entity == null || entity.isDead || entity.getHealth() <= 0.0f) return false;
        String expression = filter == null || filter.trim().isEmpty()
                ? DEFAULT_FILTER : filter.trim().toLowerCase(Locale.ROOT);
        String combined = RegistryIntrospector.getEntityIdentifier(entity);
        List<String> entityTokens = LexicalItemMatcher.tokenize(combined);

        for (String rawClause : expression.split("[,|/]+|\\s+or\\s+")) {
            String clause = rawClause.trim().replace('_', ' ');
            if (clause.isEmpty()) continue;
            if (isAnyClause(clause)) return true;
            if (isAnimalClause(clause)) {
                if (isPassive(entity)) return true;
                continue;
            }
            if (isPlayerClause(clause)) {
                if (entity instanceof EntityPlayer) return true;
                continue;
            }
            // Players only match explicit player clauses.
            if (entity instanceof EntityPlayer) continue;
            if (isHostileClause(clause) && isHostile(entity)) return true;
            String singular = LexicalItemMatcher.singularize(clause);
            if (combined.contains(clause) || combined.contains(singular)
                    || LexicalItemMatcher.tokenListContains(entityTokens, singular)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAnyClause(String clause) {
        return "all".equals(clause) || "mob".equals(clause) || "mobs".equals(clause)
                || "any".equals(clause) || "these".equals(clause) || "nearby".equals(clause);
    }

    private static boolean isAnimalClause(String clause) {
        return "animal".equals(clause) || "animals".equals(clause)
                || "passive".equals(clause) || "food".equals(clause);
    }

    private static boolean isPlayerClause(String clause) {
        return "player".equals(clause) || "players".equals(clause);
    }

    private static boolean isHostileClause(String clause) {
        return "hostile".equals(clause) || "hostiles".equals(clause)
                || "monster".equals(clause) || "monsters".equals(clause)
                || "enemy".equals(clause) || "enemies".equals(clause)
                || LexicalItemMatcher.fuzzyTokenMatches("mutant", clause);
    }
}
