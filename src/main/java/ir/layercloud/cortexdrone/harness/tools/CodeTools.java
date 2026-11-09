package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.service.code.CodeLimits;
import ir.layercloud.cortexdrone.harness.tool.ToolTimeouts;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolCapability;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;

import java.util.Collections;
import java.util.List;

/**
 * The code-mode tool: run a JavaScript/TypeScript-ish body inside the
 * in-process Rhino runtime. No file, no subprocess, no RPC.
 */
public final class CodeTools implements ToolProvider {
    private static final List<ToolSpec> SPECS = Collections.singletonList(
            ToolSpec.builder("execute")
                    .description("Execute a JavaScript body in the in-process Rhino runtime. Use it for "
                            + "loops, composition, conditionals and bulk processing. All drone tools are "
                            + "synchronous; `await` is accepted but unnecessary. Return a small value with "
                            + "`return`. Tool failures throw (catch with try/catch) and successful calls "
                            + "return plain objects. Globals: drone (all tools, camelCase or snake_case), "
                            + "tools.call/list, skills (every stored skill is a callable function: "
                            + "skills.carry_player(params) or skills.carryPlayer(params); plus "
                            + "skills.search/load/call/fn/names), ctx, log/console.log, sleep (<=10s), "
                            + "hsvToRgb, and Java.type when reflection is enabled. Large scans auto-poll via "
                            + "drone.scanBlocks. Keep the script under ~80 lines and return only what you need.")
                    .parameters(ToolSchema.object()
                            .requiredString("code", "JavaScript function body. Use `return` to return a value.")
                            .optionalInt("timeout_ms", "Wall-clock budget for this script, "
                                    + CodeLimits.MIN_TIMEOUT_MS + ".." + CodeLimits.MAX_TIMEOUT_MS + ".",
                                    CodeLimits.MIN_TIMEOUT_MS, CodeLimits.MAX_TIMEOUT_MS,
                                    CodeLimits.DEFAULT_TIMEOUT_MS))
                    .affinity(ToolAffinity.ASYNC)
                    .timeoutMs(ToolTimeouts.CODE_EXECUTE_MS)
                    .capabilities(ToolCapability.MUTATES_WORLD)
                    .build()
    );

    private final AIDroneManager manager;

    public CodeTools(AIDroneManager manager) {
        this.manager = manager;
    }

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    @Override
    public void register(ToolRegistry registry) {
        registry.bind("execute", getClass().getSimpleName(), (context, arguments) -> {
            if (context.js() == null) {
                return ToolJson.error("engine_unavailable", "The JavaScript runtime is not initialized.", null, false);
            }
            String code = arguments.has("code") ? arguments.get("code").getAsString() : "";
            long timeout = arguments.has("timeout_ms")
                    ? arguments.get("timeout_ms").getAsLong()
                    : this.manager.getConfig().getExecuteTimeoutMs();
            this.manager.getLogger().log("CODEMODE", context.controller(), "execute started",
                    ir.layercloud.cortexdrone.harness.agent.LoopTracer.snippet(code, 600));
            JsonObject result = context.js().execute(context, code, timeout);
            this.manager.getLogger().log("CODEMODE", context.controller(), "execute finished",
                    ir.layercloud.cortexdrone.harness.agent.LoopTracer.snippet(result.toString(), 900));
            return result;
        });
    }
}
