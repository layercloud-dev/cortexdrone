package ir.layercloud.cortexdrone.harness.skill;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects instance-specific literals in generated skill code.
 *
 * <p>A skill is a reusable workflow, not a recording of one run. Values such as
 * the player's username, a base coordinate, a runtime entity id, an inventory
 * slot or a dimension are valid only for the run that produced them. Copying
 * them into a skill makes the workflow silently wrong for every later call, so
 * the compiler and the auto-save path refuse code that contains them.</p>
 *
 * <p>The check is deliberately conservative and text based. It strips comments
 * first, then looks for the literal shapes that appear in generated code:</p>
 *
 * <pre>
 * bad:  if (entity.name === 'MHProDev') ...
 * good: var playerName = params.player || ctx.player || ctx.owner;
 *
 * bad:  drone.goto({ x: -1170, y: 65, z: -1030 });
 * good: drone.goto({ x: dest.x, y: dest.y, z: dest.z });
 *
 * bad:  drone.pickEntity({ entity_id: 4821 });
 * good: drone.pickEntity({ entity_id: target.id });
 * </pre>
 */
@SuppressWarnings("unused")
public final class SkillCodeGuard {
    public enum Kind {
        PLAYER_NAME("hardcoded player/owner name",
                "read it from params.player, ctx.player or ctx.owner"),
        POSITION("hardcoded world coordinate",
                "read it from params (x/y/z or destination) or derive it from live drone/player state"),
        ENTITY_ID("hardcoded runtime entity id",
                "resolve the entity with drone.getEntities(...) on every run"),
        SLOT("hardcoded inventory slot",
                "resolve the slot by item name or from drone state"),
        DIMENSION("hardcoded dimension",
                "read it from params.dimension or from the live player/drone");

        private final String label;
        private final String advice;

        Kind(String label, String advice) {
            this.label = label;
            this.advice = advice;
        }

        public String label() {
            return this.label;
        }

        public String advice() {
            return this.advice;
        }
    }

    /**
     * One literal that ties a skill to a single run.
     */
    public static final class Violation {
        private final Kind kind;
        private final int line;
        private final String snippet;

        private Violation(Kind kind, int line, String snippet) {
            this.kind = kind;
            this.line = line;
            this.snippet = snippet;
        }

        public Kind kind() {
            return this.kind;
        }

        public int line() {
            return this.line;
        }

        public String snippet() {
            return this.snippet;
        }

        /**
         * Sentence suitable for logs and compiler feedback.
         */
        public String feedback() {
            return this.kind.label() + " " + this.snippet + " on line " + this.line
                    + "; " + this.kind.advice();
        }
    }

    private static final String PLAYER_WORDS =
            "(?:player|playerName|player_name|owner|ownerName|username|targetPlayer|target_player|rider)";

    private static final Pattern PLAYER_ASSIGN = Pattern.compile(
            "(?i)\\b" + PLAYER_WORDS + "\\b\\s*(?:[:=]|\\|\\||===?|!==?)\\s*"
                    + "(['\"])([A-Za-z0-9_]{3,16})\\1");

    private static final Pattern PLAYER_NAME_OPTION = Pattern.compile(
            "(?i)\\b(?:getPlayer|getEntities|pickEntity|unpickEntity|attackEntity|getEntity"
                    + "|giveToPlayer|takeFromPlayer)\\s*\\([^)]{0,200}?\\bname\\s*:\\s*"
                    + "(['\"])([A-Za-z0-9_]{3,16})\\1");

    private static final Pattern ENTITY_NAME_COMPARE = Pattern.compile(
            "(?i)(?:\\.name|\\.getName\\s*\\(\\))\\s*(?:===|==|!==|!=)\\s*"
                    + "(['\"])([A-Za-z0-9_]{3,16})\\1");

    private static final Pattern ENTITY_NAME_COMPARE_REVERSE = Pattern.compile(
            "(?i)(['\"])([A-Za-z0-9_]{3,16})\\1\\s*(?:===|==|!==|!=)\\s*"
                    + "[A-Za-z_$][\\w$.]*\\.name\\b");

    private static final Pattern POSITION_OBJECT = Pattern.compile(
            "\\{(?=[^{}]*\\bx\\s*:\\s*(-?\\d+(?:\\.\\d+)?))"
                    + "(?=[^{}]*\\by\\s*:\\s*(-?\\d+(?:\\.\\d+)?))"
                    + "(?=[^{}]*\\bz\\s*:\\s*(-?\\d+(?:\\.\\d+)?))"
                    + "[^{}]*\\}");

    private static final Pattern POSITIONAL_CALL = Pattern.compile(
            "(?i)\\.(goto|getBlock|placeBlock|breakBlock|replaceBlock|interactBlock"
                    + "|getBlocksInBox)\\s*\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*"
                    + "(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)");

    private static final Pattern LONE_COORD = Pattern.compile(
            "(?i)\\b(x|y|z|blockX|blockY|blockZ|posX|posY|posZ"
                    + "|from_x|from_y|from_z|to_x|to_y|to_z)\\s*[:=]\\s*(-?\\d+(?:\\.\\d+)?)");

    private static final Pattern SUFFIXED_COORD = Pattern.compile(
            "\\b([a-z][A-Za-z0-9_$]*)([XYZ])\\s*=\\s*(-?\\d+(?:\\.\\d+)?)");

    private static final Pattern ENTITY_ID = Pattern.compile(
            "(?i)\\b(?:entity_id|entityId|targetEntityId|target_entity_id|riderId|rider_id)"
                    + "\\s*[:=]\\s*(-?\\d+)");

    private static final Pattern SLOT = Pattern.compile(
            "(?i)\\b(?:slot|from_slot|to_slot|source_slot|target_slot|sourceSlot|targetSlot"
                    + "|fromSlot|toSlot)\\s*[:=]\\s*(-?\\d+)");

    private static final Pattern DIMENSION = Pattern.compile(
            "(?i)\\bdimension\\s*[:=]\\s*(-?\\d+)");

    private static final Set<String> GENERIC_NAMES = new HashSet<>(Arrays.asList(
            "player", "players", "owner", "self", "target", "entity", "any", "all",
            "nearest", "random", "none", "null", "true", "false", "undefined",
            "mob", "animal", "hostile"));

    private SkillCodeGuard() {
    }

    /**
     * All instance-specific literals found in {@code code}, in source order.
     */
    public static List<Violation> inspect(String code) {
        List<Violation> violations = new ArrayList<>();
        if (code == null || code.trim().isEmpty()) return violations;
        String source = stripComments(code);

        Matcher player = PLAYER_ASSIGN.matcher(source);
        while (player.find()) {
            addName(violations, source, player.start(2), player.group(2));
        }
        Matcher option = PLAYER_NAME_OPTION.matcher(source);
        while (option.find()) {
            addName(violations, source, option.start(2), option.group(2));
        }
        Matcher compare = ENTITY_NAME_COMPARE.matcher(source);
        while (compare.find()) {
            addName(violations, source, compare.start(2), compare.group(2));
        }
        Matcher reverse = ENTITY_NAME_COMPARE_REVERSE.matcher(source);
        while (reverse.find()) {
            addName(violations, source, reverse.start(2), reverse.group(2));
        }

        Matcher position = POSITION_OBJECT.matcher(source);
        while (position.find()) {
            double x = number(position.group(1));
            double y = number(position.group(2));
            double z = number(position.group(3));
            if (isSuspiciousPosition(source, position.start(), x, y, z)) {
                add(violations, Kind.POSITION, source, position.start(1),
                        "x: " + trim(position.group(1)) + ", y: " + trim(position.group(2))
                                + ", z: " + trim(position.group(3)));
            }
        }
        Matcher call = POSITIONAL_CALL.matcher(source);
        while (call.find()) {
            add(violations, Kind.POSITION, source, call.start(1),
                    call.group(1) + "(" + trim(call.group(2)) + ", " + trim(call.group(3))
                            + ", " + trim(call.group(4)) + ")");
        }
        Matcher lone = LONE_COORD.matcher(source);
        while (lone.find()) {
            if (isSuspiciousLoneCoordinate(lone.group(1), number(lone.group(2)))) {
                add(violations, Kind.POSITION, source, lone.start(1),
                        trim(lone.group(1)) + ": " + trim(lone.group(2)));
            }
        }
        Matcher suffixed = SUFFIXED_COORD.matcher(source);
        while (suffixed.find()) {
            String identifier = suffixed.group(1);
            String axis = suffixed.group(2);
            double value = number(suffixed.group(3));
            if (!isSuspiciousSuffixedCoordinate(identifier, axis, value)) continue;
            add(violations, Kind.POSITION, source, suffixed.start(1),
                    trim(identifier) + trim(axis) + ": " + trim(suffixed.group(3)));
        }

        Matcher entity = ENTITY_ID.matcher(source);
        while (entity.find()) {
            add(violations, Kind.ENTITY_ID, source, entity.start(1),
                    "entity_id: " + trim(entity.group(1)));
        }
        Matcher slot = SLOT.matcher(source);
        while (slot.find()) {
            int value = (int) number(slot.group(1));
            if (value >= 0) {
                add(violations, Kind.SLOT, source, slot.start(1), "slot: " + trim(slot.group(1)));
            }
        }
        Matcher dimension = DIMENSION.matcher(source);
        while (dimension.find()) {
            add(violations, Kind.DIMENSION, source, dimension.start(1),
                    "dimension: " + trim(dimension.group(1)));
        }

        return violations;
    }

    /**
     * True when the code contains no instance-specific literals.
     */
    public static boolean isClean(String code) {
        return inspect(code).isEmpty();
    }

    /**
     * Compact list for logs and tool errors.
     */
    public static String describe(List<Violation> violations) {
        if (violations == null || violations.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int limit = Math.min(violations.size(), 6);
        for (int i = 0; i < limit; i++) {
            if (out.length() > 0) out.append("; ");
            out.append(violations.get(i).feedback());
        }
        if (violations.size() > limit) {
            out.append("; +").append(violations.size() - limit).append(" more");
        }
        return out.toString();
    }

    private static void addName(List<Violation> out, String source, int index, String value) {
        if (value == null || isGenericName(value)) return;
        add(out, Kind.PLAYER_NAME, source, index, "'" + value + "'");
    }

    private static boolean isGenericName(String value) {
        return GENERIC_NAMES.contains(value.toLowerCase(Locale.ROOT));
    }

    private static boolean isSuspiciousPosition(String source, int start,
                                                double x, double y, double z) {
        if (Math.abs(x) >= 16.0 || Math.abs(z) >= 16.0) return true;
        String prefix = source.substring(Math.max(0, start - 72), start).toLowerCase(Locale.ROOT);
        return prefix.matches("(?s).*(?:destination|dest|home|base|site|targetpos"
                + "|targetposition|chestpos|location)\\s*[:=]\\s*$")
                || prefix.matches("(?s).*\\.(?:goto|getblock|placeblock|breakblock|replaceblock"
                + "|interactblock|getblocksinbox|scanblocks|scanroom|withdrawchest"
                + "|depositchest|getchest|memory)\\s*\\(\\s*$");
    }

    private static boolean isSuspiciousLoneCoordinate(String key, double value) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.startsWith("block") || lower.startsWith("pos")
                || lower.startsWith("from_") || lower.startsWith("to_")) {
            return true;
        }
        if ("x".equals(lower) || "z".equals(lower)) {
            return Math.abs(value) >= 64.0;
        }
        // A bare Y is too ambiguous to judge outside a full position object.
        return false;
    }

    /**
     * A variable whose name ends in X/Y/Z is only a position when the value is
     * world-scale and, for Y, the name reads like a place.
     *
     * <p>Without this, {@code var chunkX = 0}, {@code var maxY = 255} and
     * {@code var sizeZ = 7} were rejected as "hardcoded world coordinates", so
     * a valid parameterized workflow could not be saved at all.</p>
     */
    private static boolean isSuspiciousSuffixedCoordinate(String identifier, String axis,
                                                          double value) {
        String lower = identifier.toLowerCase(Locale.ROOT);
        if (Math.abs(value) < 64.0D) return false;
        if ("Y".equalsIgnoreCase(axis)) {
            return lower.matches(".*(?:base|home|spawn|pos|position|target|dest|chest"
                    + "|site|center|centre|mid|mine|farm|waypoint|shaft|portal|outpost).*");
        }
        return true;
    }

    private static void add(List<Violation> out, Kind kind, String source,
                            int index, String snippet) {
        int line = lineOf(source, index);
        for (Violation existing : out) {
            if (existing.kind == kind && existing.line == line) return;
        }
        out.add(new Violation(kind, line, trim(snippet)));
    }

    private static int lineOf(String source, int index) {
        int line = 1;
        int end = Math.min(index, source.length());
        for (int i = 0; i < end; i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static double number(String value) {
        try {
            return Double.parseDouble(value);
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private static String trim(String value) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').trim();
        return clean.length() <= 60 ? clean : clean.substring(0, 60) + "...";
    }

    /**
     * Blanks out comments while preserving string literals and line numbers.
     * A username or coordinate inside a comment is documentation, not code.
     */
    private static String stripComments(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        int length = code.length();
        char quote = 0;
        while (i < length) {
            char current = code.charAt(i);
            char next = i + 1 < length ? code.charAt(i + 1) : 0;
            if (quote != 0) {
                out.append(current);
                if (current == '\\' && i + 1 < length) {
                    out.append(next);
                    i += 2;
                    continue;
                }
                if (current == quote) quote = 0;
                i++;
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                out.append(current);
                i++;
                continue;
            }
            if (current == '/' && next == '/') {
                while (i < length && code.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                continue;
            }
            if (current == '/' && next == '*') {
                out.append(' ').append(' ');
                i += 2;
                while (i < length) {
                    char inner = code.charAt(i);
                    if (inner == '*' && i + 1 < length && code.charAt(i + 1) == '/') {
                        out.append(' ').append(' ');
                        i += 2;
                        break;
                    }
                    out.append(inner == '\n' ? '\n' : ' ');
                    i++;
                }
                continue;
            }
            out.append(current);
            i++;
        }
        return out.toString();
    }
}
