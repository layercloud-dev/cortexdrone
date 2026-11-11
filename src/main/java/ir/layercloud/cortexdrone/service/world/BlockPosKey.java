package ir.layercloud.cortexdrone.service.world;

/**
 * Canonical packing of a block position into one {@code long} map key.
 *
 * <p>Layout: {@code x[63..38] | z[37..12] | y[11..0]}. The 1.7.10 world is
 * ±30M blocks horizontally and 0..255 vertically, so every coordinate fits
 * without collision. This was previously implemented three times; two copies
 * used this layout and one used a different one, which is a silent
 * cross-module key mismatch waiting to happen.</p>
 */
public final class BlockPosKey {
    private BlockPosKey() {
    }

    /**
     * Packs a block position.
     */
    public static long pack(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38)
                | (((long) z & 0x3FFFFFFL) << 12)
                | ((long) y & 0xFFFL);
    }

    /**
     * Unpacks the X component.
     */
    public static int unpackX(long key) {
        int x = (int) ((key >> 38) & 0x3FFFFFFL);
        return x >= 0x2000000 ? x - 0x4000000 : x;
    }

    /**
     * Unpacks the Y component.
     */
    public static int unpackY(long key) {
        return (int) (key & 0xFFFL);
    }

    /**
     * Unpacks the Z component.
     */
    public static int unpackZ(long key) {
        int z = (int) ((key >> 12) & 0x3FFFFFFL);
        return z >= 0x2000000 ? z - 0x4000000 : z;
    }
}
