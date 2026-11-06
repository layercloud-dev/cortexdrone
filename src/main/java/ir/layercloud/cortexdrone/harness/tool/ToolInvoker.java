package ir.layercloud.cortexdrone.harness.tool;

import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.manager.navigation.NavigationState;

import com.google.gson.JsonObject;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Executes a validated tool call on the correct thread and normalizes every
 * outcome into one envelope.
 *
 * <p>This is the only entry point used by the agent loop, the JavaScript
 * runtime and the HTTP API. It replaces the old Node.js RPC transport: calls
 * are in-process, typed by the registry schema, and errors are returned to the
 * model as data instead of being swallowed or retried heuristically.</p>
 */
public final class ToolInvoker {
    private static final long DEFAULT_MAIN_TIMEOUT_MS = 8_000L;
    private static final int LARGE_SCAN_RADIUS = 32;

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
        if (isLargeScan(method, work) && !isServerThread()) {
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
        if ("goto".equals(method)) return awaitNavigation(context, result, args);
        if (isTransfer(method) && (result.has("flyingToPlayer") || result.has("flyingToChest")
                || result.has("flying") || result.has("queued"))) {
            return awaitIdle(context, result, 60_000L);
        }
        return result;
    }

    private JsonObject awaitNavigation(ToolContext context, JsonObject result, JsonObject args) {
        DroneController controller = context.controller();
        long waitMs = args != null && args.has("max_wait_ms") ? args.get("max_wait_ms").getAsLong() : 120_000L;
        long deadline = System.currentTimeMillis() + Math.max(5_000L, Math.min(180_000L, waitMs));
        while (System.currentTimeMillis() < deadline) {
            if (context.cancelled()) {
                return ToolJson.error("cancelled", "Navigation was cancelled by a newer task.", null, false);
            }
            if (!this.manager.getNavigationManager().isActive(controller)) break;
            sleep(100L);
        }
        NavigationState state = this.manager.getNavigationManager().state(controller);
        boolean active = this.manager.getNavigationManager().isActive(controller);
        boolean failed = state != null && state.error != null && !state.error.isEmpty();
        JsonObject merged = result;
        merged.addProperty("arrived", !active && !failed);
        merged.addProperty("waited_ms", Math.max(0L, System.currentTimeMillis()
                - (deadline - Math.max(5_000L, Math.min(180_000L, waitMs)))));
        if (state != null) {
            merged.addProperty("navigation_phase", this.manager.getNavigationManager().status(controller).getPhase());
            if (failed) {
                merged.addProperty("navigation_error", state.error);
                merged.addProperty("ok", false);
                merged.addProperty("code", "navigation_failed");
                merged.addProperty("error", state.error);
                merged.addProperty("hint", "The navigator could not find a safe route. Dig a tunnel with "
                        + "break_block or choose a closer standoff, then retry.");
            }
        }
        if (active && !failed) {
            merged.addProperty("ok", false);
            merged.addProperty("code", "navigation_timeout");
            merged.addProperty("error", "Navigation did not finish within the wait budget.");
            merged.addProperty("hint", "Increase max_wait_ms, call stop, or choose a closer target.");
        }
        return merged;
    }

    private JsonObject awaitIdle(ToolContext context, JsonObject result, long timeoutMs) {
        DroneController controller = context.controller();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (context.cancelled()) break;
            boolean busy = this.manager.getNavigationManager().isActive(controller)
                    || this.manager.getActionRegistry().activeContextCount(controller) > 0;
            if (!busy) break;
            sleep(100L);
        }
        result.addProperty("completed", true);
        result.addProperty("still_busy", this.manager.getNavigationManager().isActive(controller)
                || this.manager.getActionRegistry().activeContextCount(controller) > 0);
        return result;
    }

    private static boolean isTransfer(String method) {
        return "give_to_player".equals(method) || "take_from_player".equals(method)
                || "withdraw_chest".equals(method) || "deposit_chest".equals(method)
                || "drop_items".equals(method) || "move_item".equals(method);
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

    private static boolean isLargeScan(String method, JsonObject args) {
        if (!"scan_blocks".equals(method)) return false;
        if (args == null || !args.has("radius") || args.get("radius").isJsonNull()) return false;
        try {
            return args.get("radius").getAsInt() > LARGE_SCAN_RADIUS;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isServerThread() {
        return Thread.currentThread().getName() != null
                && Thread.currentThread().getName().contains("Server thread");
    }

    /** Convenience for tools that need a default player when none was supplied. */
    public static EntityPlayerMP resolvePlayer(ToolContext context, DroneController controller) {
        if (context != null && context.player() != null) return context.player();
        if (controller != null && controller.ownerName != null) {
            return ir.layercloud.cortexdrone.CortexDroneMod.findPlayer(controller.ownerName);
        }
        return null;
    }
}
