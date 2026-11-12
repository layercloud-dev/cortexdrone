package ir.layercloud.cortexdrone.manager.chunk;

import li.cil.oc.common.entity.Drone;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.util.List;

/**
 * Pure player-view ownership rules used by the navigation chunk manager.
 *
 * <p>A chunk inside any online player's vanilla server view square is owned by
 * vanilla and is never forced, loaded, re-ordered, released or dropped by the
 * drone. The square matches {@code PlayerManager.playerViewRadius}, not a
 * circle, so the result is identical to vanilla's watcher area.</p>
 */
public final class PlayerChunkOwnership {
    private static final int DEFAULT_VIEW_CHUNKS = 10;
    private static final int MIN_VIEW_CHUNKS = 3;
    private static final int MAX_VIEW_CHUNKS = 15;

    private PlayerChunkOwnership() {
    }

    public static boolean isWithinViewDistance(int playerChunkX, int playerChunkZ,
                                               int chunkX, int chunkZ,
                                               int viewDistanceChunks) {
        int view = Math.max(0, viewDistanceChunks);
        return Math.abs(playerChunkX - chunkX) <= view
                && Math.abs(playerChunkZ - chunkZ) <= view;
    }

    /**
     * True when the chunk is inside any online player's server view square.
     */
    public static boolean isWithinAnyPlayerView(World world, int chunkX, int chunkZ) {
        if (!(world instanceof WorldServer)) return false;
        List<?> players = world.playerEntities;
        if (players == null || players.isEmpty()) return false;
        int view = viewDistanceChunks();
        for (Object o : players) {
            if (!(o instanceof EntityPlayerMP)) continue;
            EntityPlayerMP player = (EntityPlayerMP) o;
            if (player.worldObj != world) continue;
            int pcx = MathHelper.floor_double(player.posX) >> 4;
            int pcz = MathHelper.floor_double(player.posZ) >> 4;
            if (isWithinViewDistance(pcx, pcz, chunkX, chunkZ, view)) return true;
        }
        return false;
    }

    /**
     * True while the drone is close enough to a chunk that pulling its claim
     * could make the chunk vanish under the entity. This is the "drone is away"
     * half of the unload rule.
     */
    public static boolean isDroneNear(Drone drone, int chunkX, int chunkZ, int keepRadiusChunks) {
        if (drone == null || drone.worldObj == null) return false;
        int cx = MathHelper.floor_double(drone.posX) >> 4;
        int cz = MathHelper.floor_double(drone.posZ) >> 4;
        return isWithinViewDistance(cx, cz, chunkX, chunkZ, keepRadiusChunks);
    }

    public static int viewDistanceChunks() {
        try {
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null && server.getConfigurationManager() != null) {
                int view = server.getConfigurationManager().getViewDistance();
                return Math.max(MIN_VIEW_CHUNKS, Math.min(MAX_VIEW_CHUNKS, view));
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_VIEW_CHUNKS;
    }
}
