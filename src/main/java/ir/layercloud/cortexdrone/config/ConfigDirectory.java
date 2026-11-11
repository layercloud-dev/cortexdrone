package ir.layercloud.cortexdrone.config;

import java.io.File;

/**
 * Resolves the Minecraft {@code config/} directory.
 *
 * <p>Every consumer used to fall back to a hard-coded {@code /root/forge-smp}
 * path, which silently disabled recipe and doc lookup on any other machine.
 * The order is: a live server's own config dir, then the working directory's
 * {@code config/}, then the platform's user config dir. The absolute server
 * path is only ever a guess produced at runtime, never compiled in.</p>
 */
public final class ConfigDirectory {
    /**
     * System property that overrides the resolution order (used by tests/ops).
     */
    public static final String OVERRIDE_PROPERTY = "cortexdrone.configDir";

    private ConfigDirectory() {
    }

    /**
     * The best available {@code config/} directory, never {@code null}.
     */
    public static File resolve() {
        File override = fromProperty();
        if (override != null) return override;

        File fromServer = fromServer();
        if (fromServer != null) return fromServer;

        // The working directory's config/ is the conventional fallback even
        // when it does not exist yet: the caller creates it on first write.
        return new File("config");
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
            net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
            if (server == null) return null;
            File config = server.getFile("config");
            return config != null && config.isDirectory() ? config : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
