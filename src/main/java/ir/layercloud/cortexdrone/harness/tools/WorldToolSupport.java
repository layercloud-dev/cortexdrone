package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.service.world.ChunkAccess;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Shared helpers for the world tool providers.
 *
 * <p>Centring, loaded-chunk checks and the block descriptor live here so every
 * world query reports the same fields and the same "chunk not loaded" guidance
 * instead of each tool inventing its own.</p>
 */
@SuppressWarnings("unused")
public final class WorldToolSupport {
    private WorldToolSupport() {
    }

    /**
     * Resolves the centre of a scan.
     *
     * <p>Every radius/scan is centred on the <b>drone</b>: that is where the
     * machine and its loaded chunks are. An explicit x/y/z always wins; an
     * {@code anchor} may be {@code drone} (default) or {@code player} when the
     * caller explicitly wants the player's position.</p>
     */
    public static int[] scanCenter(Drone drone, EntityPlayerMP player, JsonObject args) {
        String anchor = args != null && args.has("anchor")
                ? args.get("anchor").getAsString() : "drone";
        boolean usePlayer = "player".equalsIgnoreCase(anchor) && player != null;
        int x = args != null && args.has("x") ? args.get("x").getAsInt()
                : MathHelper.floor_double(usePlayer ? player.posX : drone.posX);
        int y = args != null && args.has("y") ? args.get("y").getAsInt()
                : MathHelper.floor_double(usePlayer ? player.posY : drone.posY);
        int z = args != null && args.has("z") ? args.get("z").getAsInt()
                : MathHelper.floor_double(usePlayer ? player.posZ : drone.posZ);
        return new int[]{x, y, z};
    }

    /**
     * True when the drone's own chunk is loaded; most world reads need it.
     */
    public static boolean droneChunkLoaded(Drone drone) {
        World world = drone.worldObj;
        return world == null || world.isRemote
                || ChunkAccess.isBlockLoaded(world,
                MathHelper.floor_double(drone.posX), MathHelper.floor_double(drone.posZ));
    }

    /**
     * A read at a coordinate whose chunk is not loaded. The drone can still
     * reach the area: navigation force-loads a corridor as it flies, so the
     * hint is to travel first and read again, not to give up.
     */
    public static JsonObject chunkNotLoaded(String source) {
        return ToolJson.error("chunk_not_loaded",
                "Chunk is not loaded: " + source,
                "This area is outside the loaded world right now. Fly there with goto "
                        + "(it force-loads a corridor as the drone advances), then repeat the read.",
                true);
    }

    /**
     * Full descriptor for one block, used by single reads.
     */
    public static JsonObject blockJson(World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        boolean air = world.isAirBlock(x, y, z);
        JsonObject out = ToolJson.point(x, y, z);
        out.addProperty("id", block != null ? Block.getIdFromBlock(block) : 0);
        out.addProperty("meta", meta);
        out.addProperty("name", air ? "Air"
                : (block != null ? ItemClassifier.getBlockDisplayName(block, meta) : "Air"));
        String registryName = !air && block != null
                ? Block.blockRegistry.getNameForObject(block) : "minecraft:air";
        out.addProperty("registryName", registryName != null ? registryName : "minecraft:air");
        out.addProperty("isAir", air);
        out.addProperty("isLiquid", ItemClassifier.isLiquidBlock(block));
        return out;
    }

    /**
     * Appends the ore-dictionary tags of a block when it has any.
     */
    public static void addOreDict(JsonObject target, Block block, int meta, boolean air) {
        if (air) return;
        JsonArray tags = ItemClassifier.blockOreDictJson(block, meta);
        if (tags != null) target.add("oreDict", tags);
    }
}
