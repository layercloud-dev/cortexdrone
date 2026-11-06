package ir.layercloud.cortexdrone.harness.tool;

/**
 * A declared tool plus its bound handler. Handlers are bound by the domain
 * provider that owns the implementation; the registry rejects duplicates and
 * unbound declarations.
 */
public final class Tool {
    private final ToolSpec spec;
    private final ToolHandler handler;
    private final String owner;

    Tool(ToolSpec spec, ToolHandler handler, String owner) {
        this.spec = spec;
        this.handler = handler;
        this.owner = owner;
    }

    public ToolSpec getSpec() {
        return this.spec;
    }

    public ToolHandler getHandler() {
        return this.handler;
    }

    public String getOwner() {
        return this.owner;
    }

    public String getName() {
        return this.spec.getName();
    }
}
