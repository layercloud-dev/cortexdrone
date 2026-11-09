package ir.layercloud.cortexdrone.harness.js;

import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.harness.tool.ToolContext;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.List;

/**
 * The non-tool globals a script sees: {@code console}/{@code log},
 * {@code sleep}, and the read-only {@code ctx} metadata object.
 *
 * <p>Extracted from {@code JsEngine}. These are deliberately small: the script
 * surface is the {@code drone} and {@code skills} objects, and everything here
 * exists so a script can log and pause without reaching for Java.</p>
 */
final class ScriptGlobals {
    /** Longest a script may sleep in one call. */
    static final long MAX_SLEEP_MS = 10_000L;
    /** Longest log line kept in the result envelope. */
    private static final int MAX_LOG_CHARS = 24_000;
    /** Most log lines kept in the result envelope. */
    private static final int MAX_LOG_ENTRIES = 200;

    private final ToolRegistry registry;

    ScriptGlobals(ToolRegistry registry) {
        this.registry = registry;
    }

    /** Installs {@code console}, {@code log}, {@code sleep} and {@code ctx}. */
    void bind(Context cx, Scriptable scope, ToolContext context, List<String> logs, JsBudget budget) {
        bindConsole(cx, scope, logs);
        bindSleep(cx, scope, budget);
        bindContext(cx, scope, context);
    }

    private static void bindConsole(Context cx, Scriptable scope, final List<String> logs) {
        BaseFunction log = new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                appendLog(logs, inner, innerScope, args);
                return Undefined.instance;
            }
        };
        ScriptableObject console = (ScriptableObject) cx.newObject(scope);
        ScriptableObject.putProperty(console, "log", log);
        ScriptableObject.putProperty(console, "info", log);
        ScriptableObject.putProperty(console, "warn", log);
        ScriptableObject.putProperty(console, "error", log);
        ScriptableObject.putProperty(scope, "console", console);
        ScriptableObject.putProperty(scope, "log", log);
    }

    private static void bindSleep(Context cx, Scriptable scope, final JsBudget budget) {
        BaseFunction sleep = new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                budget.check();
                long ms = args.length > 0 && args[0] instanceof Number
                        ? ((Number) args[0]).longValue() : 0L;
                sleepQuietly(Math.max(0L, Math.min(MAX_SLEEP_MS, ms)));
                budget.check();
                return Undefined.instance;
            }
        };
        ScriptableObject.putProperty(scope, "sleep", sleep);
    }

    /** Sleeps, restoring the interrupt flag rather than swallowing it. */
    static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void bindContext(Context cx, Scriptable scope, ToolContext context) {
        ScriptableObject meta = (ScriptableObject) cx.newObject(scope);
        if (context != null && context.controller() != null) {
            ScriptableObject.putProperty(meta, "droneId", context.controller().id);
            ScriptableObject.putProperty(meta, "owner", context.controller().ownerName);
            ScriptableObject.putProperty(meta, "droneName", context.controller().name);
        }
        if (context != null && context.player() != null) {
            ScriptableObject.putProperty(meta, "player", context.player().getCommandSenderName());
        }
        ScriptableObject.putProperty(meta, "loopId", context != null ? context.loopId() : 0);
        ScriptableObject.putProperty(meta, "toolCount", this.registry.modelTools().size());
        ScriptableObject.putProperty(meta, "mode", "in-process-rhino");
        ScriptableObject.putProperty(scope, "ctx", meta);
    }

    /** Appends one formatted log line, bounded in count and size. */
    static void appendLog(List<String> logs, Context cx, Scriptable scope, Object[] args) {
        StringBuilder line = new StringBuilder();
        int total = 0;
        if (args != null) {
            for (Object arg : args) {
                if (line.length() > 0) line.append(' ');
                String text = Context.toString(arg);
                line.append(text);
                total += text.length();
                if (total > MAX_LOG_CHARS) {
                    line.append("...(truncated)");
                    break;
                }
            }
        }
        synchronized (logs) {
            if (logs.size() < MAX_LOG_ENTRIES) logs.add(line.toString());
        }
    }

    /** Logs as a JSON array, copied under the list's lock. */
    static JsonArray logsJson(List<String> logs) {
        JsonArray array = new JsonArray();
        synchronized (logs) {
            for (String line : logs) array.add(new JsonPrimitive(line));
        }
        return array;
    }
}
