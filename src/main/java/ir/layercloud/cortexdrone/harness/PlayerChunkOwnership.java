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
 * <p>A chunk is player-managed when Forge reports it watched by a player or
 * when it lies inside any player's server view square. The AI only loads
 * chunks inside that square; AI-loaded chunks outside it are unloaded.</p>
 */
public final class PlayerChunkOwnership {
    public static final int PROTECTION_MARGIN = 0;

    private PlayerChunkOwnership() {
    }

    public static boolean isPlayerManaged(World world, int cx, int cz) {
        if (!(world instanceof WorldServer)) return false;
        if (PlayerChunkWatchRegistry.isWatched(world, cx, cz)) return true;
        return isWithinAnyPlayerView(world, cx, cz);
    }

    /** True when the chunk is inside any online player's server view square. */
    public static boolean isWithinAnyPlayerView(World world, int cx, int cz) {
        if (!(world instanceof WorldServer)) return false;
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
