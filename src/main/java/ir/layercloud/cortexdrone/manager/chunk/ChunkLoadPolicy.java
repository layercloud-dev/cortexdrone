package ir.layercloud.cortexdrone.manager.chunk;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/**
 * Player-driven chunk-loading policy.
 *
 * <ul>
 *   <li>after a player logs in, wait 10s before the AI may load chunks;</li>
 *   <li>while a player is online, AI chunks outside every player's view
 *       distance are dropped only when no drone is busy;</li>
 *   <li>after the last player logs out, keep AI chunks for 10s, then release
 *       everything the AI loaded.</li>
 * </ul>
 *
 * <p>The timing is real wall-clock time so a lagging server does not stretch
 * the login delay or the logout grace indefinitely.</p>
 */
public final class ChunkLoadPolicy {
    public static final long LOGIN_DELAY_MS = 10000L;
    public static final long LOGOUT_GRACE_MS = 10000L;

    private long playerOnlineSinceMs;
    private long noPlayerSinceMs;
    private boolean loadAllowed;
    private boolean unloadAll;
    private boolean playerOnline;

    /**
     * Server-thread tick called once per server tick.
     */
    public synchronized void tick(MinecraftServer server) {
        tick(hasAnyPlayer(server), System.currentTimeMillis());
    }

    /**
     * Pure timing transition, exposed for tests.
     */
    public synchronized void tick(boolean hasPlayer, long nowMs) {
        this.playerOnline = hasPlayer;
        if (hasPlayer) {
            if (this.playerOnlineSinceMs == 0L) this.playerOnlineSinceMs = nowMs;
            this.noPlayerSinceMs = 0L;
            this.loadAllowed = nowMs - this.playerOnlineSinceMs >= LOGIN_DELAY_MS;
            this.unloadAll = false;
        } else {
            this.playerOnlineSinceMs = 0L;
            if (this.noPlayerSinceMs == 0L) this.noPlayerSinceMs = nowMs;
            this.loadAllowed = false;
            this.unloadAll = nowMs - this.noPlayerSinceMs >= LOGOUT_GRACE_MS;
        }
    }

    public synchronized boolean isLoadAllowed() {
        return this.loadAllowed;
    }

    public synchronized boolean hasPlayerOnline() {
        return this.playerOnline;
    }

    public synchronized boolean isUnloadAll() {
        return this.unloadAll;
    }

    /**
     * Clears the login/logout clocks for a fresh server start.
     */
    public synchronized void reset() {
        this.playerOnlineSinceMs = 0L;
        this.noPlayerSinceMs = 0L;
        this.loadAllowed = false;
        this.unloadAll = false;
        this.playerOnline = false;
    }

    private static boolean hasAnyPlayer(MinecraftServer server) {
        if (server == null || server.worldServers == null) return false;
        for (WorldServer world : server.worldServers) {
            if (world == null || world.playerEntities == null) continue;
            if (!world.playerEntities.isEmpty()) return true;
        }
        return false;
    }
}
