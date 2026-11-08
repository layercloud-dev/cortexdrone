package ir.layercloud.cortexdrone.service.navigation;

import lombok.Value;

/**
 * One read-only landing query result.
 *
 * <p>{@code x/y/z} is the block the passenger's feet occupy, not the block
 * below it and not the drone's hover point. {@code safe} means a player can be
 * dismounted here without falling, drowning, burning or suffocating.
 * {@code openSky} is a preference flag: a spot under a canopy is still safe,
 * just less desirable for a long descent.</p>
 */
@Value
public class LandingSpot {
    int x;
    int y;
    int z;
    boolean safe;
    boolean openSky;
    boolean surfaceLevel;
    String reason;
    String floorBlock;

    public static LandingSpot unsafe(int x, int y, int z, String reason) {
        return new LandingSpot(x, y, z, false, false, false, reason, "Unknown");
    }
}
