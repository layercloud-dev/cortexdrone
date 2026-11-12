package ir.layercloud.cortexdrone.service.world;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
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
 * {@code break_block} calls both consult this registry. The zones live in the
 * world save ({@code <world>/cortexdrone/protected-zones.json}) because a base
 * only exists in one world; they are loaded at server start.</p>
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
     * Replaces the active set with the zones in {@code file}.
     *
     * <p>The world file is authoritative, so a missing or unreadable file means
     * "no zones", not "keep the previous world's zones". This matters when a
     * server switches saves without a restart.</p>
     *
     * @return number of zones loaded
     */
    public static int load(File file) {
        List<ProtectedZone> loaded = read(file);
        configure(loaded);
        return zones.size();
    }

    /**
     * Reads a zone list without touching the active set.
     */
    public static List<ProtectedZone> read(File file) {
        if (file == null || !file.isFile()) return Collections.emptyList();
        try (FileReader reader = new FileReader(file)) {
            Type listType = new TypeToken<List<ProtectedZone>>() {
            }.getType();
            List<ProtectedZone> loaded = new Gson().fromJson(reader, listType);
            return loaded == null ? Collections.<ProtectedZone>emptyList() : loaded;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
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
