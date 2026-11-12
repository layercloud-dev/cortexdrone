package ir.layercloud.cortexdrone.command.impl;

import ir.layercloud.cortexdrone.DroneController;

import java.util.Collection;
import java.util.Locale;

/**
 * Resolves which drone a {@code /drone despawn <name>} argument refers to.
 *
 * <p>Pure selection logic, extracted so the ownership rule is unit-testable
 * without a running server. A player may only despawn their own drone; an
 * operator (permission level 2) may despawn any drone. Before this rule the
 * command matched <em>any</em> controller by name, so any player could destroy
 * another player's drone and receive its inventory at their own feet.</p>
 *
 * <p>Console and command blocks have no owning player, so {@link
 * #onlyController(Collection)} stands in for "own drone": a nameless {@code
 * /drone despawn} there resolves the single live drone and refuses when the
 * choice would be ambiguous.</p>
 */
public final class DespawnTargets {
    /**
     * @see CommandPermissions#OP_LEVEL
     */
    public static final int OP_PERMISSION_LEVEL = CommandPermissions.OP_LEVEL;

    private DespawnTargets() {
    }

    /**
     * @param senderName  the command sender's name
     * @param senderLevel the sender's permission level
     * @param requested   the optional name / {@code #id} / id argument
     * @param own         the sender's own controller, or {@code null}
     * @param controllers every live controller
     * @return the controller to despawn, or {@code null} when the sender may not
     */
    public static DroneController select(String senderName, int senderLevel, String requested,
                                         DroneController own,
                                         Collection<DroneController> controllers) {
        if (requested == null || requested.trim().isEmpty()) {
            return own;
        }
        DroneController match = findByName(requested.trim(), controllers);
        if (match == null) return null;
        if (isOperator(senderLevel) || owns(senderName, match)) return match;
        return null;
    }

    /**
     * The only live controller, or {@code null} when there is none or more than
     * one.
     *
     * <p>Used for a nameless {@code /drone despawn} from console or a command
     * block, where there is no "own" drone: with exactly one drone the choice
     * is unambiguous, otherwise the sender has to name one.</p>
     */
    public static DroneController onlyController(Collection<DroneController> controllers) {
        if (controllers == null) return null;
        DroneController found = null;
        for (DroneController controller : controllers) {
            if (controller == null) continue;
            if (found != null) return null;
            found = controller;
        }
        return found;
    }

    /**
     * True when {@code controller} belongs to {@code playerName}.
     */
    public static boolean owns(String playerName, DroneController controller) {
        if (playerName == null || controller == null || controller.ownerName == null) return false;
        return playerName.trim().equalsIgnoreCase(controller.ownerName.trim());
    }

    public static boolean isOperator(int permissionLevel) {
        return permissionLevel >= OP_PERMISSION_LEVEL;
    }

    private static DroneController findByName(String requested, Collection<DroneController> controllers) {
        if (controllers == null) return null;
        String wanted = requested.toLowerCase(Locale.ROOT);
        for (DroneController controller : controllers) {
            if (controller == null || controller.name == null) continue;
            if (controller.name.toLowerCase(Locale.ROOT).equals(wanted)
                    || ("#" + controller.id).equals(wanted)
                    || String.valueOf(controller.id).equals(wanted)) {
                return controller;
            }
        }
        return null;
    }
}
