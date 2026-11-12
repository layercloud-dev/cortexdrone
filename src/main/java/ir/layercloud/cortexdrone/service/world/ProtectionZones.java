package ir.layercloud.cortexdrone.service.world;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ir.layercloud.cortexdrone.CortexDroneMod;
import net.minecraft.world.World;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Process-wide registry of player-owned no-break volumes.
 *
 * <p>Navigation's automatic tunnelling and the model's explicit
 * {@code break_block} calls both consult this registry. The zones are loaded
 * from {@code config.json} at server start so the rule survives a restart and
 * is visible to the operator.</p>
 *
 * <p>The registry is intentionally static: {@code TunnelPlanner.safeToDig} and
 * {@code DigPathfinder.classify} are pure helpers called from the server tick
 * and from the pathfinder, and threading a manager reference through them would
 * couple two layers that currently do not know each other. The list is swapped
 * atomically on reload.</p>
 */
public final class ProtectionZones {
    private static volatile List<ProtectedZone> zones = Collections.emptyList();

    private ProtectionZones() {
    }

    /**
     * Replaces the active set. Empty or null disables protection.
     */
    public static void configure(List<ProtectedZone> configured) {
        if (configured == null || configured.isEmpty()) {
            zones = Collections.emptyList();
            return;
        }
        List<ProtectedZone> copy = new ArrayList<>(configured.size());
        for (ProtectedZone zone : configured) {
            if (zone != null) copy.add(zone.sanitized());
        }
        zones = Collections.unmodifiableList(copy);
    }

    public static List<ProtectedZone> all() {
        return zones;
    }

    /**
     * Loads zones from a standalone file and merges them with the active set.
     *
     * <p>A separate file is not cosmetic: an older build of the mod rewrites
     * {@code config.json} from its own in-memory model on shutdown and would
     * silently drop a newly added field. Keeping the authoritative zones in
     * {@code protected-zones.json} makes the rule survive a downgrade and a
     * config rewrite.</p>
     *
     * @return number of zones added
     */
    public static int loadSupplemental(File file) {
        if (file == null || !file.isFile()) return 0;
        try (FileReader reader = new FileReader(file)) {
            Type listType = new TypeToken<List<ProtectedZone>>() {
            }.getType();
            List<ProtectedZone> loaded = new Gson().fromJson(reader, listType);
            if (loaded == null || loaded.isEmpty()) return 0;
            List<ProtectedZone> merged = new ArrayList<>(zones);
            int added = 0;
            for (ProtectedZone zone : loaded) {
                if (zone == null) continue;
                zone.sanitized();
                if (!contains(merged, zone)) {
                    merged.add(zone);
                    added++;
                }
            }
            zones = Collections.unmodifiableList(merged);
            return added;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static boolean contains(List<ProtectedZone> list, ProtectedZone candidate) {
        for (ProtectedZone zone : list) {
            if (zone.getDimension() == candidate.getDimension()
                    && Math.abs(zone.getX() - candidate.getX()) < 0.5D
                    && Math.abs(zone.getY() - candidate.getY()) < 0.5D
                    && Math.abs(zone.getZ() - candidate.getZ()) < 0.5D
                    && Math.abs(zone.getRadius() - candidate.getRadius()) < 0.5D) {
                return true;
            }
        }
        return false;
    }

    public static int count() {
        return zones.size();
    }

    /**
     * True when breaking this block would damage a protected place.
     */
    public static boolean blocksBreaking(World world, int x, int y, int z) {
        if (world == null || zones.isEmpty()) return false;
        int dimension = world.provider != null ? world.provider.dimensionId : 0;
        return blocksBreaking(dimension, x, y, z);
    }

    public static boolean blocksBreaking(int dimension, int x, int y, int z) {
        for (ProtectedZone zone : zones) {
            if (zone.contains(dimension, x, y, z)) return true;
        }
        return false;
    }

    /**
     * The zone covering this block, or {@code null} when it is free.
     */
    public static ProtectedZone zoneAt(World world, int x, int y, int z) {
        if (world == null || zones.isEmpty()) return null;
        int dimension = world.provider != null ? world.provider.dimensionId : 0;
        for (ProtectedZone zone : zones) {
            if (zone.contains(dimension, x, y, z)) return zone;
        }
        return null;
    }

    /**
     * One-line summary for status output and refusals.
     */
    public static String describe() {
        if (zones.isEmpty()) return "none";
        StringBuilder out = new StringBuilder();
        for (ProtectedZone zone : zones) {
            if (out.length() > 0) out.append("; ");
            out.append(zone.getLabel()).append(" @(").append(Math.round(zone.getX()))
                    .append(',').append(Math.round(zone.getY())).append(',')
                    .append(Math.round(zone.getZ())).append(") r=")
                    .append(Math.round(zone.getRadius()));
        }
        return out.toString();
    }
}
