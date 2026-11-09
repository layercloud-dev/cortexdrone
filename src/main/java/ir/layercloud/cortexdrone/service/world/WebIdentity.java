package ir.layercloud.cortexdrone.service.world;

/**
 * Identity of the mod when calling external web services.
 *
 * <p>Three different user-agent strings were being sent ({@code CortexDrone/1.4},
 * {@code CortexDrone/2.0} and a bare one), so an operator reading a wiki's
 * access log could not tell which build made the request. One value, derived
 * from the mod version, keeps them consistent.</p>
 */
public final class WebIdentity {
    private WebIdentity() {
    }

    /** The user-agent sent with every outbound request. */
    public static String userAgent() {
        return "CortexDrone/" + ir.layercloud.cortexdrone.CortexDroneMod.VERSION
                + " (Minecraft 1.7.10 AI drone)";
    }
}
