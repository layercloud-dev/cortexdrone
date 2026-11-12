package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.Harness;
import ir.layercloud.cortexdrone.harness.memory.MemoryStore;
import ir.layercloud.cortexdrone.harness.tool.ToolContext;
import ir.layercloud.cortexdrone.service.inventory.ContainerAccess;
import ir.layercloud.cortexdrone.service.navigation.Breadcrumb;
import ir.layercloud.cortexdrone.service.world.DamageJournal;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Remembers a machine, furnace or container the drone has actually reached.
 *
 * <p>The point is the return trip: when a drone has to fly to a remote furnace
 * or chest without damaging anything, the approach is worth keeping. The entry
 * stores the container position plus the last few trail points, so a later task
 * can retrace the route instead of re-planning from scratch.</p>
 *
 * <p>Only reachable containers are remembered. An entry that was never actually
 * visited would be a guess, not a trail.</p>
 */
final class PlaceMemory {
    private static final int TRAIL_POINTS = 8;

    private PlaceMemory() {
    }

    /**
     * Records one visited container. Safe to call speculatively.
     */
    static void rememberContainer(ToolContext context, DroneController controller,
                                  World world, int x, int y, int z, String kind) {
        if (context == null || controller == null || world == null) return;
        if (!isReachable(controller, world, x, y, z)) return;
        Harness harness = context.harness();
        if (harness == null) return;
        MemoryStore memory = harness.memory();
        if (memory == null) return;

        String label = labelOf(world, x, y, z, kind);
        String name = label + " @" + x + "," + y + "," + z;
        List<Breadcrumb> trail = trail(context, controller);
        boolean dugIn = recentDamage(context, controller, world);
        String text = "Reached " + label + " at " + x + "," + y + "," + z
                + (trail.isEmpty() ? "" : " via " + formatTrail(trail))
                + (dugIn ? " (a tunnel was opened; run repair_trail when done)" : "");
        List<String> tags = new ArrayList<>(Arrays.asList("container", "trail", kind));
        if (dugIn) tags.add("damaged");
        memory.remember("machine", name, text, true, x, y, z,
                world.provider != null ? world.provider.dimensionId : 0,
                tags, false, "drone");
    }

    /**
     * Records a visited machine only when its tile is present and reachable.
     */
    static void rememberTile(ToolContext context, DroneController controller,
                             World world, TileEntity tile) {
        if (tile == null || world == null) return;
        rememberContainer(context, controller, world,
                tile.xCoord, tile.yCoord, tile.zCoord, kindOf(tile));
    }

    /**
     * True when the drone broke something on the way here recently, so the
     * entry can say so and point at repair_trail.
     */
    private static boolean recentDamage(ToolContext context, DroneController controller, World world) {
        try {
            if (context.manager() == null) return false;
            List<DamageJournal.Record> records =
                    context.manager().getDamageJournal().pending(controller);
            if (records.isEmpty()) return false;
            long now = world.getTotalWorldTime();
            for (DamageJournal.Record record : records) {
                if (now - record.tick() <= 400L) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isReachable(DroneController controller, World world, int x, int y, int z) {
        return ContainerAccess
                .check(world, controller.drone, x, y, z).isAccessible();
    }

    private static List<Breadcrumb> trail(ToolContext context, DroneController controller) {
        try {
            if (context.manager() == null) return new ArrayList<>();
            return context.manager().getBreadcrumbService().snapshot(controller, TRAIL_POINTS);
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    private static String formatTrail(List<Breadcrumb> trail) {
        StringBuilder out = new StringBuilder();
        for (Breadcrumb point : trail) {
            if (out.length() > 0) out.append(" -> ");
            out.append(point.getX()).append(',').append(point.getY()).append(',').append(point.getZ());
        }
        return out.toString();
    }

    private static String labelOf(World world, int x, int y, int z, String kind) {
        try {
            Block block = world.getBlock(x, y, z);
            String display = ItemClassifier.getBlockDisplayName(block, world.getBlockMetadata(x, y, z));
            return !display.isEmpty() ? display : kind;
        } catch (Throwable ignored) {
            return kind;
        }
    }

    private static String kindOf(TileEntity tile) {
        String name = tile.getClass().getSimpleName().toLowerCase();
        if (name.contains("chest")) return "chest";
        if (name.contains("furnace")) return "furnace";
        if (name.contains("hopper")) return "hopper";
        return "machine";
    }
}
