package ir.layercloud.cortexdrone.harness;

import java.util.List;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * Player-owned chunk policy used by DroneChunkLoader.
 *
 * <p>A chunk is protected when Forge currently reports it as watched by a
 * player (exact, event-driven) or when it lies inside any player's view radius
 * plus a small border margin. Protected chunks are never forced, loaded,
 * re-ordered, unforced or released by the drone.</p>
 */
public final class PlayerChunkOwnership {
    /** Border/deferred chunks can outlive the nominal view radius briefly. */
    public static final int PROTECTION_MARGIN = 2;

    private PlayerChunkOwnership() {
    }

    public static boolean isPlayerManaged(World world, int cx, int cz) {
        if (!(world instanceof WorldServer)) return false;
        if (PlayerChunkWatchRegistry.isWatched(world, cx, cz)) return true;
        List<?> players = world.field_73010_i;
        if (players == null || players.isEmpty()) return false;
        int view = viewDistanceChunks() + PROTECTION_MARGIN;
        for (Object o : players) {
            if (!(o instanceof EntityPlayerMP)) continue;
            EntityPlayerMP p = (EntityPlayerMP) o;
            if (p.field_70170_p != world) continue;
            int pcx = MathHelper.func_76128_c(p.field_70165_t) >> 4;
            int pcz = MathHelper.func_76128_c(p.field_70161_v) >> 4;
            if (PlayerChunkPolicy.isWithinViewDistance(pcx, pcz, cx, cz, view)) return true;
        }
        return false;
    }

    private static int viewDistanceChunks() {
        try {
            MinecraftServer srv = MinecraftServer.func_71276_C();
            if (srv != null && srv.func_71203_ab() != null) {
                int v = srv.func_71203_ab().func_72395_o();
                return Math.max(3, Math.min(15, v));
            }
        } catch (Throwable ignored) {
        }
        return 10;
    }
}
