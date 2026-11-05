package ir.layercloud.cortexdrone.navigation;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/**
 * Player-driven chunk-loading policy:
 *
 * <ul>
 *   <li>after a player logs in, wait 10s before the AI may load chunks;</li>
 *   <li>while a player is online, AI chunks outside every player's view
 *       distance are unloaded;</li>
 *   <li>after the last player logs out, keep AI chunks for 10s, then unload
 *       everything the AI loaded.</li>
 * </ul>
 *
 * The timing is real wall-clock time so a lagging server does not stretch the
 * grace periods indefinitely.
 */
public final class ChunkLoadPolicy {
    public static final long LOGIN_DELAY_MS = 10000L;
    public static final long LOGOUT_GRACE_MS = 10000L;

    private long playerOnlineSinceMs;
    private long noPlayerSinceMs;
    private boolean loadAllowed;
    private boolean unloadAll;
    private boolean playerOnline;

    /** Server-thread tick called once per server tick. */
    public synchronized void tick(MinecraftServer server) {
        tick(hasAnyPlayer(server), System.currentTimeMillis());
    }

    /** Pure timing transition, exposed for tests. */
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

    private static boolean hasAnyPlayer(MinecraftServer server) {
        if (server == null || server.field_71305_c == null) return false;
        for (WorldServer world : server.field_71305_c) {
            if (world == null || world.field_73010_i == null) continue;
            if (!world.field_73010_i.isEmpty()) return true;
        }
        return false;
    }
}
