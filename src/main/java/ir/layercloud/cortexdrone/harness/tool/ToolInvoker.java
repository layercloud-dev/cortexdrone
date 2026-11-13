package ir.layercloud.cortexdrone.harness.tool;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.service.navigation.NavigationLimits;
import ir.layercloud.cortexdrone.service.navigation.NavigationState;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Executes a validated tool call on the correct thread and normalizes every
 * outcome into one envelope.
 *
 * <p>This is the only entry point used by the agent loop and the JavaScript
 * runtime. Calls are in-process, typed by the registry schema, and errors are
 * returned to the model as data.</p>
 */
@SuppressWarnings("unused")
public final class ToolInvoker {
    private static final long DEFAULT_MAIN_TIMEOUT_MS = ToolTimeouts.DEFAULT_TOOL_MS;
    /**
     * How long a transfer may keep the agent thread waiting.
     */
    private static final long TRANSFER_WAIT_MS = ToolTimeouts.LONG_MS;
    private static final int LARGE_SCAN_RADIUS = ScanLimits.TIME_SLICE_RADIUS;

    private final AIDroneManager manager;
    private final ToolRegistry registry;
    private final ScanJobs scans;

    public ToolInvoker(AIDroneManager manager, ToolRegistry registry, ScanJobs scans) {
        this.manager = manager;
        this.registry = registry;
        this.scans = scans;
    }

    public ToolRegistry registry() {
        return this.registry;
    }

    public ScanJobs scans() {
        return this.scans;
    }

    public void onServerTickEnd() {
        this.scans.onServerTickEnd();
    }

    public JsonObject invoke(ToolContext context, String name, JsonObject arguments) {
        String method = name != null ? name.trim().toLowerCase() : "";
        Tool tool = this.registry.find(method);
        if (tool == null) {
            return ToolJson.error("unknown_tool",
                    "Unknown tool '" + method + "'.",
                    "Use one of the registered tools; call tools.list() inside execute to see them.", false);
        }
        JsonObject args = ToolArgs.aliasesForSchema(arguments != null ? arguments : new JsonObject(),
                tool.getSpec().getParameters());
        JsonObject invalid = ToolValidator.validate(tool.getSpec(), args);
        if (invalid != null) return invalid;
        // Domain views read camelCase; the public schema is snake_case. Add
        // aliases after validation so both styles work for the model and JS.
        final JsonObject work = ToolArgs.camelAliases(args);

        if (context != null && context.cancelled()) {
            return ToolJson.error("cancelled", "The task generation was cancelled.", null, false);
        }

        // Large world scans must stay on the server thread but cannot monopolize
        // a tick, so they become a pollable job. The JS helper auto-polls; the
        // model can call poll_scan if it invoked scan_blocks directly.
        if (isTimeSliced(tool.getSpec(), work) && !isServerThread()) {
            return this.scans.submit(context != null ? context.controller() : null,
                    context != null ? context.player() : null, work);
        }

        long timeout = tool.getSpec().getTimeoutMs() > 0
                ? tool.getSpec().getTimeoutMs() : DEFAULT_MAIN_TIMEOUT_MS;
        try {
            if (tool.getSpec().getAffinity() == ToolAffinity.MAIN) {
                JsonObject result = this.manager.callOnMainThread(
                        () -> execute(tool, context, work), timeout);
                if (result == null) {
                    return ToolJson.error("timeout",
                            "Tool '" + method + "' did not finish within " + timeout + "ms on the server thread.",
                            "The world may be busy; retry with a smaller radius/limit or after the current action ends.",
                            true);
                }
                return awaitCompletion(context, method, result, work);
            }
            return awaitCompletion(context, method, execute(tool, context, work), work);
        } catch (Throwable t) {
            return exception(method, t);
        }
    }

    /**
     * Some primitives start a server-side job (navigation or an inventory
     * transfer). The model called them expecting a completed operation, so the
     * agent thread waits here while the server thread ticks the job. This never
     * runs on the server thread.
     */
    private JsonObject awaitCompletion(ToolContext context, String method,
                                       JsonObject result, JsonObject args) {
        if (context == null || context.controller() == null || result == null) return result;
        if (isServerThread()) return result;
        Tool tool = this.registry.find(method);
        ToolSpec spec = tool == null ? null : tool.getSpec();
        if (spec != null && spec.has(ToolCapability.AWAITS_NAVIGATION)) {
            return awaitNavigation(context, result, args);
        }
        boolean awaits = spec != null && spec.has(ToolCapability.AWAITS_COMPLETION);
        if (awaits && (result.has("flyingToPlayer") || result.has("flyingToChest")
                || result.has("flying") || result.has("queued"))) {
            return awaitIdle(context, result, TRANSFER_WAIT_MS);
        }
        return result;
    }

    private JsonObject awaitNavigation(ToolContext context, JsonObject result, JsonObject args) {
        DroneController controller = context.controller();
        if (result == null || !result.has("started") || !result.get("started").getAsBoolean()) {
            // Synchronous destination/validation failure. It must never be
            // reported as a successful arrival.
            if (result != null && !result.has("arrived")) result.addProperty("arrived", false);
            return result;
        }
        long requestId = result.has("request_id") ? result.get("request_id").getAsLong() : 0L;
        long waitMs = args != null && args.has("max_wait_ms") ? args.get("max_wait_ms").getAsLong() : NavigationLimits.DEFAULT_WAIT_MS;
        long waitBudget = Math.max(NavigationLimits.MIN_WAIT_MS,
                Math.min(NavigationLimits.MAX_WAIT_MS, waitMs));
        long deadline = System.currentTimeMillis() + waitBudget;
        NavigationState state = this.manager.getNavigationManager().state(controller);
        while (System.currentTimeMillis() < deadline) {
            if (context.cancelled()) {
                return ToolJson.error("cancelled", "Navigation was cancelled by a newer task.", null, false);
            }
            if (state != null && state.isRequestFinished(requestId)) break;
            if (requestId <= 0L && !this.manager.getNavigationManager().isActive(controller)) break;
            sleep(100L);
        }
        boolean finished = state != null && state.isRequestFinished(requestId);
        JsonObject merged = result;
        merged.addProperty("waited_ms", Math.max(0L, System.currentTimeMillis()
                - (deadline - waitBudget)));
        merged.addProperty("navigation_phase",
                this.manager.getNavigationManager().status(controller).getPhase());
        if (!finished) {
            merged.addProperty("ok", false);
            merged.addProperty("arrived", false);
            merged.addProperty("code", "navigation_timeout");
            merged.addProperty("error", "Navigation did not finish within the wait budget.");
            merged.addProperty("hint", "Increase max_wait_ms, call stop, or choose a closer target.");
            return merged;
        }
        if ("arrived".equals(state.finishedOutcome) && state.finishedRequestId == requestId) {
            merged.addProperty("ok", true);
            merged.addProperty("arrived", true);
            return merged;
        }
        merged.addProperty("ok", false);
        merged.addProperty("arrived", false);
        boolean superseded = state.finishedRequestId != requestId;
        String error = superseded ? "Navigation was superseded by a newer request."
                : (state.finishedError != null && !state.finishedError.isEmpty()
                ? state.finishedError : "Navigation ended without arriving.");
        if (superseded || "replaced".equals(state.finishedOutcome)
                || "stopped".equals(state.finishedOutcome)
                || "cancelled".equals(state.finishedOutcome)) {
            merged.addProperty("code", "cancelled");
            merged.addProperty("error", error);
            merged.addProperty("retryable", false);
            return merged;
        }
        merged.addProperty("code", "navigation_failed");
        merged.addProperty("error", error);
        merged.addProperty("navigation_error", error);
        String hint;
        if (error != null && error.contains("dig budget")) {
            hint = "The goal is reachable only through terrain, but this call's "
                    + "max_dig_blocks budget is spent. Retry once with a larger "
                    + "max_dig_blocks (up to " + NavigationLimits.MAX_DIG_BLOCKS
                    + ") or pick a nearer destination; do not "
                    + "repeat the same goto with the same budget.";
        } else if (error != null && error.contains("disabled digging")) {
            hint = "This call set allow_dig:false and the goal has no no-dig route. "
                    + "Retry with allow_dig:true (and max_dig_blocks above 0) or choose "
                    + "a destination reachable in open air.";
        } else if (error != null && error.contains("no progress")) {
            hint = "The drone stopped approaching the goal (canopy, sealed pocket or an "
                    + "unreachable column). Do not repeat the same goto: move to open sky first "
                    + "(or use the remembered shaft) and then retry, or pick a nearby waypoint.";
        } else {
            hint = "Automatic trail, tunnel and detour recovery were exhausted. "
                    + "Pick a nearer goal or a different destination; do not repeat "
                    + "the same goto.";
        }
        merged.addProperty("hint", hint);
        return merged;
    }

    private JsonObject awaitIdle(ToolContext context, JsonObject result, long timeoutMs) {
        DroneController controller = context.controller();
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean busy = true;
        while (System.currentTimeMillis() < deadline) {
            if (context.cancelled()) break;
            busy = this.manager.getNavigationManager().isActive(controller)
                    || this.manager.getActionRegistry().activeContextCount(controller) > 0;
            if (!busy) break;
            sleep(100L);
        }
        // `completed` must mean "the transfer finished", not "the wait ended".
        // Reporting true while still busy made the model believe the items had
        // moved and skip the retry.
        result.addProperty("completed", !busy);
        result.addProperty("still_busy", busy);
        if (busy) {
            result.addProperty("ok", false);
            result.addProperty("code", "transfer_timeout");
            result.addProperty("error", "The transfer did not finish within " + timeoutMs + "ms.");
            result.addProperty("hint", "The drone is still working. Check status or wait before retrying; "
                    + "do not repeat the same transfer.");
            result.addProperty("retryable", false);
        }
        return result;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    private JsonObject execute(Tool tool, ToolContext context, JsonObject args) {
        try {
            JsonObject result = tool.getHandler().handle(context, args);
            if (result == null) {
                return ToolJson.error("empty_result",
                        "Tool '" + tool.getName() + "' returned no result.",
                        "This is a bug in the mod; report the tool name and arguments.", false);
            }
            if (!result.has("ok")) result.addProperty("ok", true);
            return result;
        } catch (ToolFailure failure) {
            return ToolJson.error(failure.code(), failure.getMessage(), failure.hint(), failure.retryable());
        } catch (Throwable t) {
            return exception(tool.getName(), t);
        }
    }

    private static JsonObject exception(String tool, Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getClass().getSimpleName()
                + (cause.getMessage() != null ? ": " + cause.getMessage() : "");
        return ToolJson.error("exception", "Tool '" + tool + "' failed: " + message,
                "Fix the arguments or the world condition named in the error, then retry. "
                        + "Do not repeat the identical call without changing something.", true);
    }

    /**
     * True when this call must run as a pollable job.
     *
     * <p>A tool that declares {@link ToolCapability#TIME_SLICED} is time-sliced.
     * One that also declares {@link ToolCapability#INLINE_WHEN_SMALL} stays
     * inline while its radius is small, so a cheap scan is not turned into a
     * poll round-trip. The decision is metadata-driven; no tool name appears
     * here.</p>
     */
    static boolean isTimeSliced(ToolSpec spec, JsonObject args) {
        if (spec == null || !spec.has(ToolCapability.TIME_SLICED)) return false;
        if (spec.has(ToolCapability.INLINE_WHEN_SMALL)) return shouldTimeSlice(spec, args);
        return true;
    }

    /**
     * True only for a tool that opted into inline-small scans and whose radius
     * exceeds the time-slice threshold.
     *
     * <p>Routing on radius alone sent {@code get_entities}/{@code scan_chests}/
     * {@code scan_furnaces} into {@code ScanJobs}, which is a block scanner with
     * an ore default filter and would have returned the wrong data. Only a spec
     * declaring {@link ToolCapability#INLINE_WHEN_SMALL} can match.</p>
     */
    static boolean shouldTimeSlice(ToolSpec spec, JsonObject args) {
        if (spec == null || !spec.has(ToolCapability.INLINE_WHEN_SMALL)) return false;
        if (args == null || !args.has("radius") || args.get("radius").isJsonNull()) return false;
        try {
            return args.get("radius").getAsInt() > LARGE_SCAN_RADIUS;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isServerThread() {
        return Threads.isServerThread();
    }

    /**
     * Convenience for tools that need a default player when none was supplied.
     */
    public static EntityPlayerMP resolvePlayer(ToolContext context, DroneController controller) {
        if (context != null && context.player() != null) return context.player();
        if (controller != null && controller.ownerName != null) {
            return CortexDroneMod.findPlayer(controller.ownerName);
        }
        return null;
    }
}
