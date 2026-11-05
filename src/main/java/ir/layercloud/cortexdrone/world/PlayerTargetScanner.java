package ir.layercloud.cortexdrone.world;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.navigation.raytrace.VoxelRaycaster3D;
import lombok.experimental.UtilityClass;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * 3D voxel raycaster (backed by Amanatides & Woo 3D DDA) and bounding-box support block detector.
 */
@UtilityClass
public final class PlayerTargetScanner {

    public static JsonObject raycastPlayerLook(World world, EntityPlayerMP player, double maxDist) {
        if (world == null || player == null) return null;
        double eyeX = player.posX;
        double eyeY = player.posY + (double) player.getEyeHeight();
        double eyeZ = player.posZ;
        double yawRad = Math.toRadians(player.rotationYaw);
        double pitchRad = Math.toRadians(player.rotationPitch);
        double dx = -Math.sin(yawRad) * Math.cos(pitchRad);
        double dy = -Math.sin(pitchRad);
        double dz = Math.cos(yawRad) * Math.cos(pitchRad);
        return VoxelRaycaster3D.raycastSolidBlock(world, eyeX, eyeY, eyeZ, dx, dy, dz, maxDist);
    }

    public static JsonObject findPlayerSupportBlock(World world, EntityPlayerMP player) {
        if (world == null || player == null) return null;
        int footY = MathHelper.floor_double(player.posY - 0.05) - 1;
        double[] offsets = new double[]{0.0, -0.32, 0.32};
        for (int dy = 0; dy >= -2; dy--) {
            int y = footY + dy;
            if (y <= 0) continue;
            for (double ox : offsets) {
                for (double oz : offsets) {
                    int bx = MathHelper.floor_double(player.posX + ox);
                    int bz = MathHelper.floor_double(player.posZ + oz);
                    if (!world.isAirBlock(bx, y, bz)) {
                        Block b = world.getBlock(bx, y, bz);
                        if (b != null && !ItemClassifier.isLiquidBlock(b)) {
                            int meta = world.getBlockMetadata(bx, y, bz);
                            JsonObject sb = new JsonObject();
                            sb.addProperty("x", bx);
                            sb.addProperty("y", y);
                            sb.addProperty("z", bz);
                            sb.addProperty("name", ItemClassifier.getBlockDisplayName(b, meta));
                            String reg = Block.blockRegistry.getNameForObject(b);
                            sb.addProperty("registryName", reg != null ? reg : "");
                            sb.addProperty("id", Block.getIdFromBlock(b));
                            sb.addProperty("meta", meta);
                            return sb;
                        }
                    }
                }
            }
        }
        return null;
    }
}
