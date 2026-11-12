package ir.layercloud.cortexdrone.command.impl;

import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.service.navigation.DroneSpeed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * Every editable key in {@code config.json}, with one owner for parsing.
 *
 * <p>{@code /drone config} used to know four keys and repeated the parsing
 * inline; a hand edit could set {@code maxTokens} but the command could not.
 * The catalog is the command's only source of truth: a new {@link ModConfig}
 * property without an entry here fails {@code ConfigCatalogTest}, so the
 * command cannot silently fall behind the file.</p>
 *
 * <p>Canonical keys are the JSON field names. Short aliases exist for chat
 * ergonomics ({@code url}, {@code turns}, {@code speed}).</p>
 */
final class ConfigCatalog {
    enum Kind {
        TEXT, SECRET, INT, DOUBLE, BOOLEAN, DRONE_LIST
    }

    /**
     * One editable setting.
     */
    static final class Field {
        private final String key;
        private final List<String> aliases;
        private final Kind kind;
        private final String bounds;
        private final String defaultValue;
        private final Function<ModConfig, String> read;
        private final BiConsumer<ModConfig, String> write;
        private final int min;
        private final int max;
        private final double minDouble;
        private final double maxDouble;

        private Field(String key, List<String> aliases, Kind kind, String bounds,
                      String defaultValue, Function<ModConfig, String> read,
                      BiConsumer<ModConfig, String> write, int min, int max,
                      double minDouble, double maxDouble) {
            this.key = key;
            this.aliases = aliases;
            this.kind = kind;
            this.bounds = bounds;
            this.defaultValue = defaultValue;
            this.read = read;
            this.write = write;
            this.min = min;
            this.max = max;
            this.minDouble = minDouble;
            this.maxDouble = maxDouble;
        }

        String key() {
            return this.key;
        }

        Kind kind() {
            return this.kind;
        }

        String bounds() {
            return this.bounds;
        }

        /**
         * The short key shown in tab completion.
         */
        String chatKey() {
            return this.aliases.isEmpty() ? this.key : this.aliases.get(0);
        }

        String defaultValue() {
            return this.defaultValue;
        }

        /**
         * Current value as chat text; secrets are masked.
         */
        String display(ModConfig config) {            if (config == null) return "";
            if (this.kind == Kind.DRONE_LIST) {
                int count = config.getSavedDrones() == null ? 0 : config.getSavedDrones().size();
                return count + " binding(s)";
            }
            String raw = this.read.apply(config);
            if (this.kind == Kind.SECRET) return AdminCommands.mask(raw);
            return raw;
        }

        /**
         * Applies one chat value.
         *
         * @return the value as applied (after clamping), for the confirmation
         * @throws IllegalArgumentException with a chat-ready reason on bad input
         */
        String apply(ModConfig config, String value) {
            String text = value == null ? "" : value.trim();
            switch (this.kind) {
                case SECRET:
                    this.write.accept(config, isClear(text) ? "" : text);
                    return AdminCommands.mask(this.read.apply(config));
                case TEXT:
                    if (text.isEmpty() || isClear(text)) {
                        throw new IllegalArgumentException("value cannot be empty");
                    }
                    this.write.accept(config, text);
                    return text;
                case INT: {
                    int parsed;
                    try {
                        parsed = Integer.parseInt(text);
                    } catch (NumberFormatException notWhole) {
                        throw new IllegalArgumentException("expected a whole number " + this.bounds);
                    }
                    int applied = Math.max(this.min, Math.min(this.max, parsed));
                    this.write.accept(config, String.valueOf(applied));
                    return String.valueOf(applied);
                }
                case DOUBLE: {
                    double parsed;
                    try {
                        parsed = Double.parseDouble(text);
                    } catch (NumberFormatException notNumber) {
                        throw new IllegalArgumentException("expected a number " + this.bounds);
                    }
                    if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                        throw new IllegalArgumentException("expected a number " + this.bounds);
                    }
                    double applied = Math.max(this.minDouble, Math.min(this.maxDouble, parsed));
                    this.write.accept(config, String.valueOf(applied));
                    return trimNumber(applied);
                }
                case BOOLEAN: {
                    Boolean parsed = parseBoolean(text);
                    if (parsed == null) {
                        throw new IllegalArgumentException("expected true or false");
                    }
                    this.write.accept(config, String.valueOf(parsed));
                    return String.valueOf(parsed);
                }
                case DRONE_LIST:
                    if ("clear".equalsIgnoreCase(text) || "reset".equalsIgnoreCase(text)) {
                        this.write.accept(config, "");
                        return "cleared";
                    }
                    throw new IllegalArgumentException("only 'clear' is supported");
                default:
                    throw new IllegalArgumentException("unsupported setting");
            }
        }
    }

    private static final List<Field> FIELDS = new ArrayList<>();

    static {
        text("apiUrl", Arrays.asList("url", "api_url", "endpoint"), "gateway URL",
                ModConfig.DEFAULT_API_URL, ModConfig::getApiUrl, ModConfig::setApiUrl);
        text("model", Collections.singletonList("model_id"), "model id",
                ModConfig.DEFAULT_MODEL, ModConfig::getModel, ModConfig::setModel);
        secret("apiKey", Arrays.asList("key", "api_key", "token"),
                ModConfig::getApiKey, ModConfig::setApiKey);

        integer("maxTurns", Arrays.asList("turns", "max_turns"), "task loop turns",
                ModConfig.DEFAULT_MAX_TURNS, ModConfig.MIN_MAX_TURNS, ModConfig.MAX_MAX_TURNS,
                ModConfig::getMaxTurns, ModConfig::setMaxTurns);
        integer("taskDeadlineSeconds", Arrays.asList("deadline", "deadline_s", "task_deadline_seconds"),
                "generation deadline (seconds)",
                ModConfig.DEFAULT_TASK_DEADLINE_SECONDS,
                ModConfig.MIN_TASK_DEADLINE_SECONDS, ModConfig.MAX_TASK_DEADLINE_SECONDS,
                ModConfig::getTaskDeadlineSeconds, ModConfig::setTaskDeadlineSeconds);
        integer("maxTokens", Arrays.asList("tokens", "max_tokens", "output_tokens"),
                "per-turn output tokens",
                ModConfig.DEFAULT_MAX_TOKENS,
                ModConfig.MIN_MAX_TOKENS, ModConfig.MAX_MAX_TOKENS,
                ModConfig::getMaxTokens, ModConfig::setMaxTokens);
        decimal("temperature", Collections.singletonList("temp"), "sampling temperature",
                ModConfig.DEFAULT_TEMPERATURE,
                ModConfig.MIN_TEMPERATURE, ModConfig.MAX_TEMPERATURE,
                ModConfig::getTemperature, ModConfig::setTemperature);
        integer("executeTimeoutMs", Arrays.asList("timeout", "execute_timeout_ms"),
                "script/action timeout (ms)",
                ModConfig.DEFAULT_EXECUTE_TIMEOUT_MS,
                ModConfig.MIN_EXECUTE_TIMEOUT_MS, ModConfig.MAX_EXECUTE_TIMEOUT_MS,
                ModConfig::getExecuteTimeoutMs, ModConfig::setExecuteTimeoutMs);
        integer("maxToolResultChars", Arrays.asList("toolchars", "max_tool_result_chars"),
                "tool result chars",
                ModConfig.DEFAULT_MAX_TOOL_RESULT_CHARS,
                ModConfig.MIN_MAX_TOOL_RESULT_CHARS, ModConfig.MAX_MAX_TOOL_RESULT_CHARS,
                ModConfig::getMaxToolResultChars, ModConfig::setMaxToolResultChars);
        integer("maxContextChars", Arrays.asList("contextchars", "max_context_chars"),
                "prompt context chars",
                ModConfig.DEFAULT_MAX_CONTEXT_CHARS,
                ModConfig.MIN_MAX_CONTEXT_CHARS, ModConfig.MAX_MAX_CONTEXT_CHARS,
                ModConfig::getMaxContextChars, ModConfig::setMaxContextChars);

        flag("allowJavaReflection", Arrays.asList("reflection", "allow_java_reflection"),
                ModConfig::isAllowJavaReflection, ModConfig::setAllowJavaReflection);
        flag("webSearchEnabled", Arrays.asList("websearch", "web_search_enabled"),
                ModConfig::isWebSearchEnabled, ModConfig::setWebSearchEnabled);

        decimal("droneSpeedMultiplier", Arrays.asList("speed", "drone_speed_multiplier"),
                "drone speed multiplier",
                ModConfig.DEFAULT_DRONE_SPEED_MULTIPLIER,
                DroneSpeed.MIN_MULTIPLIER, DroneSpeed.MAX_MULTIPLIER,
                ModConfig::getDroneSpeedMultiplier, ModConfig::setDroneSpeedMultiplier);

        // Auto-managed by the server, but still part of config.json: the one
        // meaningful edit is clearing the saved bindings.
        FIELDS.add(new Field("savedDrones", Arrays.asList("drones", "saved_drones"),
                Kind.DRONE_LIST, "(clear)", "clear",
                config -> String.valueOf(config.getSavedDrones() == null
                        ? 0 : config.getSavedDrones().size()),
                (config, value) -> {
                    if (config.getSavedDrones() != null) config.getSavedDrones().clear();
                },
                0, 0, 0.0D, 0.0D));
    }

    private ConfigCatalog() {
    }

    static List<Field> all() {
        return Collections.unmodifiableList(FIELDS);
    }

    /**
     * Resolves a canonical key or alias, case-insensitively.
     */
    static Field find(String key) {
        if (key == null) return null;
        String wanted = key.trim().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) return null;
        for (Field field : FIELDS) {
            if (field.key.toLowerCase(Locale.ROOT).equals(wanted)) return field;
            for (String alias : field.aliases) {
                if (alias.equalsIgnoreCase(wanted)) return field;
            }
        }
        return null;
    }

    /**
     * Every key as chat text: {@code key (aliases) [bounds]}.
     */
    static String keys() {
        StringBuilder out = new StringBuilder();
        for (Field field : FIELDS) {
            if (out.length() > 0) out.append(", ");
            out.append(field.key);
            if (!field.aliases.isEmpty() && !field.aliases.get(0).equals(field.key)) {
                out.append('/').append(field.aliases.get(0));
            }
        }
        return out.toString();
    }

    /**
     * Chat keys for tab completion: the short alias when one exists.
     */
    static List<String> chatKeys() {
        List<String> keys = new ArrayList<>(FIELDS.size());
        for (Field field : FIELDS) {
            keys.add(field.chatKey());
        }
        return keys;
    }

    /**
     * True when the value resets a key instead of setting it.
     */
    static boolean isResetValue(String value) {
        return value != null && ("default".equalsIgnoreCase(value.trim())
                || "reset".equalsIgnoreCase(value.trim()));
    }

    private static void text(String key, List<String> aliases, String label, String defaultValue,
                             Function<ModConfig, String> read, BiConsumer<ModConfig, String> write) {
        FIELDS.add(new Field(key, aliases, Kind.TEXT, "(" + label + ")", defaultValue,
                read, write, 0, 0, 0.0D, 0.0D));
    }

    private static void secret(String key, List<String> aliases,
                               Function<ModConfig, String> read, BiConsumer<ModConfig, String> write) {
        FIELDS.add(new Field(key, aliases, Kind.SECRET, "(clear to unset)", "",
                read, write, 0, 0, 0.0D, 0.0D));
    }

    private static void integer(String key, List<String> aliases, String label, int defaultValue,
                                int min, int max,
                                ToIntFunction<ModConfig> read, ObjIntConsumer<ModConfig> write) {
        FIELDS.add(new Field(key, aliases, Kind.INT, "(" + min + ".." + max + ")",
                String.valueOf(defaultValue), config -> String.valueOf(read.applyAsInt(config)),
                (config, value) -> write.accept(config, Integer.parseInt(value)),
                min, max, 0.0D, 0.0D));
    }

    private static void decimal(String key, List<String> aliases, String label, double defaultValue,
                                double min, double max,
                                ToDoubleFunction<ModConfig> read, ObjDoubleConsumer<ModConfig> write) {
        FIELDS.add(new Field(key, aliases, Kind.DOUBLE,
                "(" + trimNumber(min) + ".." + trimNumber(max) + ")",
                trimNumber(defaultValue),
                config -> trimNumber(read.applyAsDouble(config)),
                (config, value) -> write.accept(config, Double.parseDouble(value)),
                0, 0, min, max));
    }

    private static void flag(String key, List<String> aliases,
                             Predicate<ModConfig> read, BiConsumer<ModConfig, Boolean> write) {
        FIELDS.add(new Field(key, aliases, Kind.BOOLEAN, "(true/false)", "true",
                config -> String.valueOf(read.test(config)),
                (config, value) -> write.accept(config, Boolean.parseBoolean(value)),
                0, 0, 0.0D, 0.0D));
    }

    private static boolean isClear(String value) {
        return "clear".equalsIgnoreCase(value) || "-".equalsIgnoreCase(value);
    }

    private static Boolean parseBoolean(String value) {
        if (value == null) return null;
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.equals("true") || text.equals("on") || text.equals("yes") || text.equals("1")) {
            return Boolean.TRUE;
        }
        if (text.equals("false") || text.equals("off") || text.equals("no") || text.equals("0")) {
            return Boolean.FALSE;
        }
        return null;
    }

    static String trimNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
