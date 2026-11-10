package ir.layercloud.cortexdrone.harness.tool;

import lombok.Getter;

/**
 * A declared tool plus its bound handler. Handlers are bound by the domain
 * provider that owns the implementation; the registry rejects duplicates and
 * unbound declarations.
 */
@Getter
public final class Tool {
    private final ToolSpec spec;
    private final ToolHandler handler;
    private final String owner;

    Tool(ToolSpec spec, ToolHandler handler, String owner) {
        this.spec = spec;
        this.handler = handler;
        this.owner = owner;
    }

    public String getName() {
        return this.spec.getName();
    }
}
