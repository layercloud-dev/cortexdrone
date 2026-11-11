package ir.layercloud.cortexdrone.harness.js;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.harness.tool.ToolCapability;
import ir.layercloud.cortexdrone.harness.tool.ToolContext;
import ir.layercloud.cortexdrone.harness.tool.ToolInvoker;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import org.mozilla.javascript.*;

/**
 * One tool exposed as a callable JavaScript function.
 *
 * <p>Extracted from {@link JsEngine} so the engine keeps only context/budget
 * ownership. This class owns the JS-facing tool semantics: argument coercion,
 * error envelopes as catchable exceptions, the standoff retry, and the
 * time-sliced scan poll.</p>
 */
final class DroneToolFunction extends BaseFunction {
    /**
     * How long a script waits for a time-sliced scan before giving up.
     */
    static final long SCAN_POLL_BUDGET_MS = 45_000L;
    /**
     * Pause between scan polls.
     */
    private static final long SCAN_POLL_INTERVAL_MS = 200L;
    /**
     * Standoff retries before reporting the original result.
     */
    private static final int REACH_RETRIES = 3;

    private final transient ToolInvoker invoker;
    private final transient ToolContext context;
    private final transient ToolSpec spec;

    DroneToolFunction(ToolInvoker invoker, ToolContext context, ToolSpec spec) {
        this.invoker = invoker;
        this.context = context;
        this.spec = spec;
    }

    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        JsonObject arguments = JsConvert.argumentsToJson(cx, scope, args, this.spec);
        JsonObject result = invokeChecked(cx, scope, this.invoker, this.context,
                this.spec.getName(), arguments);
        result = retryReach(cx, scope, this.spec.getName(), arguments, result);
        result = autoPollScan(cx, scope, result);
        // A scan that never finished was turned into a failure envelope above.
        // It must be thrown like any other tool failure, otherwise the script
        // sees data and concludes the scan succeeded with zero results.
        if (result.has("ok") && !result.get("ok").getAsBoolean()) {
            throw toolError(cx, scope, this.spec.getName(), result);
        }
        return JsConvert.toJs(result, cx, scope);
    }

    /**
     * Invokes a tool and turns a failure envelope into a catchable JS error.
     */
    static JsonObject invokeChecked(Context cx, Scriptable scope, ToolInvoker invoker,
                                    ToolContext context, String name, JsonObject arguments) {
        JsBudget budget = JsEngine.currentBudget();
        if (budget != null) budget.check();
        JsonObject result = invoker.invoke(context, name, arguments);
        if (budget != null) budget.check();
        boolean ok = !result.has("ok") || result.get("ok").getAsBoolean();
        if (!ok) throw toolError(cx, scope, name, result);
        return result;
    }

    /**
     * Moves to the returned standoff and retries the same operation.
     */
    private JsonObject retryReach(Context cx, Scriptable scope, String name,
                                  JsonObject arguments, JsonObject result) {
        if (!result.has("needsMovement") || !result.get("needsMovement").getAsBoolean()
                || !result.has("standoff")) return result;
        JsonObject current = result;
        for (int attempt = 0; attempt < REACH_RETRIES; attempt++) {
            JsonObject standoff = current.getAsJsonObject("standoff");
            JsonObject gotoArgs = new JsonObject();
            gotoArgs.add("x", standoff.get("x"));
            gotoArgs.add("y", standoff.get("y"));
            gotoArgs.add("z", standoff.get("z"));
            JsonObject moved = invokeChecked(cx, scope, this.invoker, this.context, "goto", gotoArgs);
            if (moved.has("arrived") && !moved.get("arrived").getAsBoolean()) return moved;
            current = invokeChecked(cx, scope, this.invoker, this.context, name, arguments);
            if (!current.has("needsMovement") || !current.get("needsMovement").getAsBoolean()) {
                return current;
            }
        }
        return current;
    }

    /**
     * Polls a time-sliced scan to completion for the script.
     *
     * <p>When the budget runs out the result is still {@code pending}, so it is
     * converted into a failure. Returning {@code ok:true, count:0} made
     * {@code mine_blocks} conclude "no ore here" while the scan was merely slow.</p>
     */
    /**
     * True when a result is a pending time-sliced job that the script should poll.
     */
    static boolean shouldAutoPoll(ToolSpec spec, JsonObject result) {
        if (spec == null || !spec.has(ToolCapability.TIME_SLICED)) return false;
        return result != null && result.has("job_id")
                && result.has("pending") && result.get("pending").getAsBoolean();
    }

    @SuppressWarnings("BusyWait")
    private JsonObject autoPollScan(Context cx, Scriptable scope, JsonObject result) {
        if (!shouldAutoPoll(this.spec, result)) {
            return result;
        }
        String jobId = result.get("job_id").getAsString();
        long deadline = System.currentTimeMillis() + SCAN_POLL_BUDGET_MS;
        JsonObject latest = result;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(SCAN_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            JsonObject pollArgs = new JsonObject();
            pollArgs.addProperty("job_id", jobId);
            latest = invokeChecked(cx, scope, this.invoker, this.context, "poll_scan", pollArgs);
            if (latest.has("done") && latest.get("done").getAsBoolean()) return latest;
        }
        latest.addProperty("ok", false);
        latest.addProperty("code", "scan_pending");
        latest.addProperty("error", "The block scan did not finish within "
                + (SCAN_POLL_BUDGET_MS / 1000L) + "s.");
        latest.addProperty("hint", "Retry with a smaller radius, or call poll_scan with job_id "
                + jobId + " and read the result yourself.");
        latest.addProperty("retryable", true);
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
