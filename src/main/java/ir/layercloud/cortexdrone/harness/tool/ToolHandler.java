package ir.layercloud.cortexdrone.harness.tool;

import com.google.gson.JsonObject;

/**
 * Executes one validated tool call. Implementations either return a JSON
 * result (with {@code ok:true}) or throw; the invoker converts both outcomes
 * into the single error/success envelope the model sees.
 */
@FunctionalInterface
public interface ToolHandler {
    JsonObject handle(ToolContext context, JsonObject arguments) throws Exception;
}
