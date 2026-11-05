package ir.layercloud.cortexdrone.world;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.navigation.VoxelRaycaster3D;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * 3D voxel raycaster (backed by Amanatides & Woo 3D DDA) and bounding-box support block detector.
 */
public final class PlayerTargetScanner {
    private PlayerTargetScanner() {
    }

    public static JsonObject raycastPlayerLook(World world, EntityPlayerMP player, double maxDist) {
        if (world == null || player == null) return null;
        double eyeX = player.field_70165_t;
        double eyeY = player.field_70163_u + (double) player.func_70047_e();
        double eyeZ = player.field_70161_v;
        double yawRad = Math.toRadians(player.field_70177_z);
        double pitchRad = Math.toRadians(player.field_70125_A);
        double dx = -Math.sin(yawRad) * Math.cos(pitchRad);
        double dy = -Math.sin(pitchRad);
        double dz = Math.cos(yawRad) * Math.cos(pitchRad);
        return VoxelRaycaster3D.raycastSolidBlock(world, eyeX, eyeY, eyeZ, dx, dy, dz, maxDist);
    }

    public static JsonObject findPlayerSupportBlock(World world, EntityPlayerMP player) {
        if (world == null || player == null) return null;
        int footY = MathHelper.func_76128_c(player.field_70163_u - 0.05) - 1;
        double[] offsets = new double[]{0.0, -0.32, 0.32};
        for (int dy = 0; dy >= -2; dy--) {
            int y = footY + dy;
            if (y <= 0) continue;
            for (double ox : offsets) {
                for (double oz : offsets) {
                    int bx = MathHelper.func_76128_c(player.field_70165_t + ox);
                    int bz = MathHelper.func_76128_c(player.field_70161_v + oz);
                    if (!world.func_147437_c(bx, y, bz)) {
                        Block b = world.func_147439_a(bx, y, bz);
                        if (b != null && !ItemClassifier.isLiquidBlock(b)) {
                            int meta = world.func_72805_g(bx, y, bz);
                            JsonObject sb = new JsonObject();
                            sb.addProperty("x", bx);
                            sb.addProperty("y", y);
                            sb.addProperty("z", bz);
                            sb.addProperty("name", ItemClassifier.getBlockDisplayName(b, meta));
                            String reg = Block.field_149771_c.func_148750_c(b);
                            sb.addProperty("registryName", reg != null ? reg : "");
                            sb.addProperty("id", Block.func_149682_b(b));
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
