package ir.layercloud.cortexdrone.harness.tool;

/**
 * Thread affinity of a tool.
 *
 * <p>{@link #MAIN} tools touch the Minecraft world, entities or the drone and
 * must run on the server thread. {@link #ASYNC} tools only perform I/O or pure
 * computation and never block a server tick.</p>
 */
public enum ToolAffinity {
    MAIN,
    ASYNC
}
