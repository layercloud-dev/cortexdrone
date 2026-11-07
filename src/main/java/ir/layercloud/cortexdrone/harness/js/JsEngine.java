package ir.layercloud.cortexdrone.harness.js;

import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import ir.layercloud.cortexdrone.harness.tool.Tool;
import ir.layercloud.cortexdrone.harness.tool.ToolContext;
import ir.layercloud.cortexdrone.harness.tool.ToolInvoker;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ImporterTopLevel;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.WrappedException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * In-process JavaScript (and common TypeScript) execution on Mozilla Rhino.
 *
 * <p>A script runs on the agent thread inside the JVM; tool calls go straight
 * through {@link ToolInvoker} to the registry, so results and errors are native
 * objects and no serialization hop exists.</p>
 *
 * <p>Rhino 1.7.15 is used deliberately: it is the newest Rhino that still
 * produces Java 8 bytecode, which Minecraft 1.7.10's class loader requires.
 * {@link JsSource} normalizes modern syntax the engine does not parse.</p>
 */
public final class JsEngine {
    private static final ThreadLocal<JsBudget> BUDGET = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> RUNNING = new ThreadLocal<>();
    private static final int MAX_LOG_ENTRIES = 200;
    private static final int MAX_LOG_CHARS = 24_000;
    private static final String BOOTSTRAP = loadBootstrap();

    private final ToolInvoker invoker;
    private final ToolRegistry registry;
    private final ModConfig config;
    private final SkillLibrary skills;
    private final ContextFactory factory;

    public JsEngine(ToolInvoker invoker, ToolRegistry registry, ModConfig config, SkillLibrary skills) {
        this.invoker = invoker;
        this.registry = registry;
        this.config = config;
        this.skills = skills;
        this.factory = new ContextFactory() {
            @Override
            protected Context makeContext() {
                Context cx = super.makeContext();
                cx.setLanguageVersion(Context.VERSION_ES6);
                cx.setOptimizationLevel(-1);
                cx.setInstructionObserverThreshold(20_000);
                if (config.isAllowJavaReflection()) {
                    cx.setWrapFactory(JavaInterop.wrapFactory());
                }
                return cx;
            }

            @Override
            protected void observeInstructionCount(Context cx, int instructionCount) {
                JsBudget budget = BUDGET.get();
                if (budget != null) budget.check();
            }
        };
    }

    /** True while this thread is already inside a script (nested execute is unsafe). */
    public static boolean isExecuting() {
        return Boolean.TRUE.equals(RUNNING.get());
    }

    public JsonObject execute(ToolContext context, String code, long timeoutMs) {
        long started = System.currentTimeMillis();
        if (code == null || code.trim().isEmpty()) {
            return ToolJson.error("empty_script", "The execute tool received empty code.",
                    "Provide a JavaScript body; use 'return' to return a value.", true);
        }
        String prepared = JsSource.prepare(code);
        List<String> logs = Collections.synchronizedList(new ArrayList<String>());
        JsBudget budget = new JsBudget(started + Math.max(1_000L, timeoutMs),
                context != null ? context::cancelled : null);
        JsBudget previous = BUDGET.get();
        Boolean previousRunning = RUNNING.get();
        BUDGET.set(budget);
        RUNNING.set(Boolean.TRUE);
        try {
            return this.factory.call(new ContextAction<JsonObject>() {
                @Override
                public JsonObject run(Context cx) {
                    return runScript(cx, context, prepared, logs, budget, started);
                }
            });
        } catch (Throwable t) {
            return error(t, logs, started, budget);
        } finally {
            if (previous != null) BUDGET.set(previous);
            else BUDGET.remove();
            if (previousRunning != null) RUNNING.set(previousRunning);
            else RUNNING.remove();
        }
    }

    private JsonObject runScript(Context cx, ToolContext context, String code,
                                 List<String> logs, JsBudget budget, long started) {
        boolean java = this.config.isAllowJavaReflection();
        // The importer scope provides Packages, java.*, javax.*, importClass and
        // importPackage. Without it scripts only ever see the help, ctx, drone,
        // tools and skills objects bound below.
        Scriptable scope = java ? javaScope(cx) : cx.initStandardObjects();
        if (!java) {
            cx.setClassShutter(JavaInterop.restrictedShutter());
            JavaInterop.removePackageGlobals(scope);
        }
        bindConsole(cx, scope, logs);
        bindUtilities(cx, scope, budget);
        bindContext(cx, scope, context);
        bindTools(cx, scope, context);
        bindJava(cx, scope, context);
        bindSkills(cx, scope, context);
        if (!BOOTSTRAP.isEmpty()) {
            cx.evaluateString(scope, BOOTSTRAP, "bootstrap.js", 1, null);
        }

        Object result;
        try {
            String wrapped = "(function() {\n" + code + "\n})();";
            result = cx.evaluateString(scope, wrapped, "drone-script.js", 1, null);
        } catch (Throwable t) {
            return error(t, logs, started, budget);
        }

        JsonObject out = ToolJson.ok();
        out.add("result", JsConvert.toJson(cx, scope, result));
        out.add("logs", logsJson(logs));
        out.addProperty("duration_ms", System.currentTimeMillis() - started);
        if (budget.tripped()) out.addProperty("superseded", true);
        return out;
    }

    private void bindConsole(Context cx, final Scriptable scope, final List<String> logs) {
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

    private void bindUtilities(Context cx, final Scriptable scope, final JsBudget budget) {
        BaseFunction sleep = new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                budget.check();
                long ms = 0L;
                if (args.length > 0 && args[0] instanceof Number) ms = ((Number) args[0]).longValue();
                ms = Math.max(0L, Math.min(10_000L, ms));
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                budget.check();
                return Undefined.instance;
            }
        };
        ScriptableObject.putProperty(scope, "sleep", sleep);
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

    private void bindTools(Context cx, Scriptable scope, ToolContext context) {
        ToolNamespace drone = new ToolNamespace();
        drone.setParentScope(scope);
        drone.setPrototype(ScriptableObject.getObjectPrototype(scope));
        for (Tool tool : this.registry.tools()) {
            ToolSpec spec = tool.getSpec();
            if (spec.isInternal()) continue;
            Function function = new ToolFunction(this.invoker, context, spec);
            drone.bind(spec.getName(), camelCase(spec.getName()), function);
        }
        ScriptableObject.putProperty(scope, "drone", drone);

        ScriptableObject tools = (ScriptableObject) cx.newObject(scope);
        ScriptableObject.putProperty(tools, "call", new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                String name = args.length > 0 && args[0] != null ? Context.toString(args[0]) : "";
                JsonObject arguments = new JsonObject();
                if (args.length > 1) {
                    JsonElement element = JsConvert.toJson(inner, innerScope, args[1]);
                    if (element != null && element.isJsonObject()) arguments = element.getAsJsonObject();
                }
                JsonObject result = ToolFunction.invokeChecked(inner, innerScope, invoker, context, name, arguments);
                return JsConvert.toJs(result, inner, innerScope);
            }
        });
        ScriptableObject.putProperty(tools, "list", new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                JsonArray array = new JsonArray();
                for (Tool tool : registry.tools()) {
                    if (tool.getSpec().isInternal()) continue;
                    array.add(tool.getSpec().toPromptEntry());
                }
                return JsConvert.toJs(array, inner, innerScope);
            }
        });
        ScriptableObject.putProperty(scope, "tools", tools);
    }

    private void bindSkills(Context cx, final Scriptable scope, final ToolContext context) {
        final SkillLibrary library = context != null && context.skills() != null
                ? context.skills() : this.skills;
        ScriptableObject skillsObject = (ScriptableObject) cx.newObject(scope);
        ScriptableObject.putProperty(skillsObject, "search", new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                JsonObject arguments = new JsonObject();
                arguments.addProperty("action", "search");
                arguments.addProperty("query", args.length > 0 ? Context.toString(args[0]) : "");
                if (args.length > 1 && args[1] instanceof Number) {
                    arguments.addProperty("limit", ((Number) args[1]).intValue());
                }
                JsonObject result = ToolFunction.invokeChecked(inner, innerScope, invoker, context, "skill", arguments);
                return JsConvert.toJs(result, inner, innerScope);
            }
        });
        ScriptableObject.putProperty(skillsObject, "load", new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                JsonObject arguments = new JsonObject();
                arguments.addProperty("action", "load");
                arguments.addProperty("name", args.length > 0 ? Context.toString(args[0]) : "");
                JsonObject result = ToolFunction.invokeChecked(inner, innerScope, invoker, context, "skill", arguments);
                return JsConvert.toJs(result, inner, innerScope);
            }
        });
        // Skill composition: run another skill in this same runtime. The called
        // skill sees the same tools and helpers but not the caller's locals.
        ScriptableObject.putProperty(skillsObject, "call", new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                String name = args.length > 0 ? Context.toString(args[0]) : "";
                return SkillRuntime.call(inner, innerScope, library, name,
                        args.length > 1 ? args[1] : null);
            }
        });
        ScriptableObject.putProperty(skillsObject, "run", ScriptableObject.getProperty(skillsObject, "call"));
        ScriptableObject.putProperty(scope, "skills", skillsObject);
    }

    private void bindJava(Context cx, final Scriptable scope, final ToolContext context) {
        if (!this.config.isAllowJavaReflection()) return;
        JavaInterop.bind(cx, scope, JavaInterop.classLoader());
        ScriptableObject.putProperty(scope, "main", new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                if (args.length == 0 || !(args[0] instanceof Function)) {
                    throw Context.reportRuntimeError("main(fn) expects a function.");
                }
                final Function fn = (Function) args[0];
                final Object[] callArgs = new Object[Math.max(0, args.length - 1)];
                System.arraycopy(args, 1, callArgs, 0, callArgs.length);
                if (!onServerThread()) return fn.call(inner, innerScope, innerScope, callArgs);
                AIDroneManager manager = context != null ? context.manager() : null;
                if (manager == null) return fn.call(inner, innerScope, innerScope, callArgs);
                long timeout = context.config() != null
                        ? Math.max(1_000L, context.config().getExecuteTimeoutMs()) : 30_000L;
                try {
                    return manager.callOnMainThread(
                            (java.util.concurrent.Callable<Object>) () -> fn.call(inner, innerScope, innerScope, callArgs),
                            timeout);
                } catch (Exception e) {
                    throw Context.reportRuntimeError("main(fn) failed: " + e);
                }
            }
        });
    }

    private static boolean onServerThread() {
        Thread thread = Thread.currentThread();
        return thread.getName() != null && thread.getName().contains("Server thread");
    }

    /** Importer scope so Packages, java.* and importClass work like Rhino's shell. */
    private static Scriptable javaScope(Context cx) {
        // The constructor already initializes the standard objects and the
        // importer globals; calling initStandardObjects again throws.
        return new ImporterTopLevel(cx, false);
    }

    private static JsonObject error(Throwable throwable, List<String> logs, long started, JsBudget budget) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof JavaScriptException) {
            Object value = ((JavaScriptException) cause).getValue();
            if (value instanceof Scriptable) {
                Object toolResult = ScriptableObject.getProperty((Scriptable) value, "toolResult");
                if (toolResult instanceof CharSequence) {
                    try {
                        JsonElement parsed = new com.google.gson.JsonParser().parse(toolResult.toString());
                        if (parsed != null && parsed.isJsonObject()) {
                            JsonObject result = parsed.getAsJsonObject();
                            Object tool = ScriptableObject.getProperty((Scriptable) value, "tool");
                            if (tool instanceof CharSequence) result.addProperty("js_tool", tool.toString());
                            result.add("logs", logsJson(logs));
                            result.addProperty("duration_ms", System.currentTimeMillis() - started);
                            return result;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        String code = "js_error";
        String message;
        String hint = "Fix the JavaScript and retry. Use 'return' to return a value; "
                + "tools are synchronous so await is optional.";
        if (budget != null && budget.superseded()) {
            code = "js_cancelled";
            message = budget.reason();
            hint = "This script was stopped because a newer task replaced it. Do not retry it.";
        } else if (budget != null && budget.tripped()) {
            code = "js_budget";
            message = budget.reason();
            hint = "Reduce work per execute call (smaller loops/scans) or split it into several calls.";
        } else if (cause instanceof RhinoException) {
            RhinoException rhino = (RhinoException) cause;
            boolean syntax = cause instanceof EvaluatorException
                    && String.valueOf(rhino.details()).toLowerCase().contains("syntax");
            code = syntax ? "js_syntax" : "js_runtime";
            message = rhino.details() + " (line " + rhino.lineNumber()
                    + (rhino.columnNumber() > 0 ? ", column " + rhino.columnNumber() : "") + ")";
            if (syntax) {
                hint = "The runtime accepts ES6 plus the compatibility subset. Avoid classes, generators "
                        + "with delegation, optional chaining chains and TypeScript-only syntax; "
                        + "use function/var or arrow functions and plain property access.";
            }
        } else {
            message = cause.getClass().getSimpleName()
                    + (cause.getMessage() != null ? ": " + cause.getMessage() : "");
        }
        JsonObject result = ToolJson.error(code, message, hint, true);
        result.add("logs", logsJson(logs));
        result.addProperty("duration_ms", System.currentTimeMillis() - started);
        return result;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof WrappedException && ((WrappedException) current).getWrappedException() != null) {
            current = ((WrappedException) current).getWrappedException();
        }
        return current;
    }

    private static void appendLog(List<String> logs, Context cx, Scriptable scope, Object[] args) {
        StringBuilder line = new StringBuilder();
        int total = 0;
        if (args != null) {
            for (Object arg : args) {
                if (line.length() > 0) line.append(' ');
                JsonElement element = JsConvert.toJson(cx, scope, arg);
                String text = element != null && element.isJsonPrimitive()
                        ? element.getAsString() : String.valueOf(element);
                line.append(text);
                total += text.length();
                if (total > 2_000) break;
            }
        }
        if (logs.size() >= MAX_LOG_ENTRIES) return;
        if (line.length() > 1_500) line.setLength(1_500);
        int chars = 0;
        for (String existing : logs) chars += existing.length();
        if (chars > MAX_LOG_CHARS) return;
        logs.add(line.toString());
    }

    private static JsonArray logsJson(List<String> logs) {
        JsonArray array = new JsonArray();
        synchronized (logs) {
            for (String line : logs) array.add(new com.google.gson.JsonPrimitive(line));
        }
        return array;
    }

    private static ClassShutter dangerousClassShutter() {
        return JavaInterop.restrictedShutter();
    }

    public static String camelCase(String snake) {
        if (snake == null || snake.isEmpty()) return "";
        StringBuilder out = new StringBuilder(snake.length());
        boolean upper = false;
        for (int i = 0; i < snake.length(); i++) {
            char c = snake.charAt(i);
            if (c == '_' || c == '-') {
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }

    private static String loadBootstrap() {
        try (java.io.InputStream stream = JsEngine.class.getResourceAsStream("/js/bootstrap.js")) {
            if (stream == null) return "";
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) >= 0) buffer.write(chunk, 0, read);
            return new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The {@code drone} object, with tolerant tool-name lookup.
     *
     * <p>Models mix snake_case and camelCase and occasionally write
     * {@code getTop_y}. Both {@code get_top_y} and {@code getTopY} are bound
     * explicitly; this resolver additionally folds case and separators so a
     * typo still reaches the intended tool instead of throwing.</p>
     */
    private static final class ToolNamespace extends ScriptableObject {
        private final java.util.Map<String, Object> folded = new java.util.HashMap<>();

        @Override
        public String getClassName() {
            return "ToolNamespace";
        }

        void bind(String snakeName, String camelName, Object function) {
            ScriptableObject.putProperty(this, snakeName, function);
            if (!camelName.equals(snakeName)) {
                ScriptableObject.putProperty(this, camelName, function);
            }
            this.folded.put(fold(snakeName), function);
            this.folded.put(fold(camelName), function);
        }

        @Override
        public Object get(String name, Scriptable start) {
            Object direct = super.get(name, start);
            if (direct != Scriptable.NOT_FOUND) return direct;
            Object foldedMatch = this.folded.get(fold(name));
            return foldedMatch != null ? foldedMatch : Scriptable.NOT_FOUND;
        }

        @Override
        public boolean has(String name, Scriptable start) {
            return super.has(name, start) || this.folded.containsKey(fold(name));
        }

        private static String fold(String name) {
            if (name == null) return "";
            StringBuilder out = new StringBuilder(name.length());
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c == '_' || c == '-' || Character.isWhitespace(c)) continue;
                out.append(Character.toLowerCase(c));
            }
            return out.toString();
        }
    }

    /** One JS-callable tool. */
    private static final class ToolFunction extends BaseFunction {
        private final transient ToolInvoker invoker;
        private final transient ToolContext context;
        private final transient ToolSpec spec;

        ToolFunction(ToolInvoker invoker, ToolContext context, ToolSpec spec) {
            this.invoker = invoker;
            this.context = context;
            this.spec = spec;
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            JsonObject arguments = JsConvert.argumentsToJson(cx, scope, args, this.spec);
            JsonObject result = invokeChecked(cx, scope, this.invoker, this.context, this.spec.getName(), arguments);
            result = retryReach(cx, scope, this.spec.getName(), arguments, result);
            result = autoPollScan(cx, scope, this.spec.getName(), result);
            return JsConvert.toJs(result, cx, scope);
        }

        static JsonObject invokeChecked(Context cx, Scriptable scope, ToolInvoker invoker,
                                        ToolContext context, String name, JsonObject arguments) {
            JsBudget budget = BUDGET.get();
            if (budget != null) budget.check();
            JsonObject result = invoker.invoke(context, name, arguments);
            if (budget != null) budget.check();
            boolean ok = !result.has("ok") || result.get("ok").getAsBoolean();
            if (!ok) throw toolError(cx, scope, name, result);
            return result;
        }

        /** Moves to the returned standoff and retries the same operation. */
        private JsonObject retryReach(Context cx, Scriptable scope, String name,
                                      JsonObject arguments, JsonObject result) {
            if (!result.has("needsMovement") || !result.get("needsMovement").getAsBoolean()
                    || !result.has("standoff")) return result;
            JsonObject current = result;
            for (int attempt = 0; attempt < 3; attempt++) {
                JsonObject standoff = current.getAsJsonObject("standoff");
                JsonObject gotoArgs = new JsonObject();
                gotoArgs.add("x", standoff.get("x"));
                gotoArgs.add("y", standoff.get("y"));
                gotoArgs.add("z", standoff.get("z"));
                JsonObject moved = invokeChecked(cx, scope, this.invoker, this.context, "goto", gotoArgs);
                if (moved.has("arrived") && !moved.get("arrived").getAsBoolean()) return moved;
                current = invokeChecked(cx, scope, this.invoker, this.context, name, arguments);
                if (!current.has("needsMovement") || !current.get("needsMovement").getAsBoolean()) return current;
            }
            return current;
        }

        /** scan_blocks may be time-sliced; poll it to completion for the script. */
        private JsonObject autoPollScan(Context cx, Scriptable scope, String name, JsonObject result) {
            if (!"scan_blocks".equals(name) || !result.has("job_id")
                    || !result.has("pending") || !result.get("pending").getAsBoolean()) return result;
            String jobId = result.get("job_id").getAsString();
            long deadline = System.currentTimeMillis() + 45_000L;
            JsonObject latest = result;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return result;
                }
                JsonObject pollArgs = new JsonObject();
                pollArgs.addProperty("job_id", jobId);
                latest = invokeChecked(cx, scope, this.invoker, this.context, "poll_scan", pollArgs);
                if (latest.has("done") && latest.get("done").getAsBoolean()) return latest;
            }
            latest.addProperty("hint", "Scan still pending after 45s; call poll_scan with job_id.");
            return latest;
        }

        private static JavaScriptException toolError(Context cx, Scriptable scope,
                                                     String name, JsonObject result) {
            String message = result.has("error") ? result.get("error").getAsString() : "tool failed";
            Scriptable error = cx.newObject(scope);
            ScriptableObject.putProperty(error, "name", "ToolError");
            ScriptableObject.putProperty(error, "message", message);
            ScriptableObject.putProperty(error, "tool", name);
            ScriptableObject.putProperty(error, "toolResult", result.toString());
            return new JavaScriptException(error, "drone:" + name, 0);
        }
    }
}
