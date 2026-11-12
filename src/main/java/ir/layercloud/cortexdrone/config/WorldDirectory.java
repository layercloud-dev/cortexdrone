package ir.layercloud.cortexdrone.config;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.ISaveHandler;

import java.io.File;

/**
 * Resolves the live world save directory.
 *
 * <p>Memory and protected zones are world state: they name places that only
 * exist in one save, so they live inside that save and disappear with it. The
 * harness config stays in {@code config/cortexdrone/} and survives a world
 * reset.</p>
 *
 * <p>Resolution is deferred to server start, because no world exists during
 * pre-init. {@code null} means "no live save" (a test harness, a client without
 * an integrated server, or a server that has not loaded its worlds yet); the
 * caller then keeps world state next to the config instead of inventing a
 * world directory.</p>
 */
public final class WorldDirectory {
    /**
     * System property that overrides the resolution order (used by tests/ops).
     */
    public static final String OVERRIDE_PROPERTY = "cortexdrone.worldDir";

    private WorldDirectory() {
    }

    /**
     * The world save directory, or {@code null} when there is no live world.
     */
    public static File resolve() {
        File override = fromProperty();
        if (override != null) return override;
        return fromServer();
    }

    private static File fromProperty() {
        try {
            String value = System.getProperty(OVERRIDE_PROPERTY);
            if (value != null && !value.trim().isEmpty()) {
                File dir = new File(value.trim());
                if (dir.isDirectory()) return dir;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static File fromServer() {
        try {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null || server.worldServers == null) return null;
            WorldServer overworld = server.worldServers.length > 0 ? server.worldServers[0] : null;
            if (overworld == null) return null;
            ISaveHandler saveHandler = overworld.getSaveHandler();
            File world = saveHandler != null ? saveHandler.getWorldDirectory() : null;
            return world != null && world.isDirectory() ? world : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
