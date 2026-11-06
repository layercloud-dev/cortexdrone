package ir.layercloud.cortexdrone.harness.tool;

/**
 * One domain of the tool surface.
 *
 * <p>A provider declares its {@link ToolSpec}s and binds a handler for each.
 * The harness merges all providers into one {@link ToolRegistry}; duplicate
 * names or unbound declarations are startup errors.</p>
 */
public interface ToolProvider {
    void register(ToolRegistry registry);
}
