package ir.layercloud.cortexdrone.manager.chunk.impl;

import ir.layercloud.cortexdrone.CortexDroneMod;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraftforge.common.ForgeChunkManager;

import java.util.Collection;
import java.util.Set;

/**
 * Thin, exception-safe wrapper over Forge's chunk-ticket API.
 *
 * <p>Extracted from {@code ChunkLoadingManager}. Every call here can throw on an
 * unusual Forge build or when chunk loading is disabled, and the previous code
 * swallowed those failures with no trace. Failures are counted and reported once
 * per session through {@link Listener}, so a chunk-loading regression is visible
 * without spamming the log every tick.</p>
 */
final class ForgeTicketOps {
    /** Reports a swallowed Forge failure, once per distinct operation. */
    interface Listener {
        void onFailure(String operation, Throwable cause);
    }

    private final Listener listener;

    ForgeTicketOps(Listener listener) {
        this.listener = listener;
    }

    /** Respect Forge's per-mod chunkloading config (a zero disables it). */
    static boolean isAvailable() {
        try {
            return ForgeChunkManager.getMaxChunkDepthFor(CortexDroneMod.MODID) > 0
                    && ForgeChunkManager.getMaxTicketLengthFor(CortexDroneMod.MODID) > 0;
        } catch (Throwable unavailable) {
            return true;
        }
    }

    /** Requests an entity ticket bound to a drone, or {@code null} on failure. */
    ForgeChunkManager.Ticket requestEntityTicket(CortexDroneMod mod,
                                                 net.minecraft.world.World world) {
        if (world == null) return null;
        try {
            ForgeChunkManager.Ticket ticket =
                    ForgeChunkManager.requestTicket(mod, world, ForgeChunkManager.Type.ENTITY);
            configure(ticket);
            return ticket;
        } catch (Throwable failure) {
            report("request", failure);
            return null;
        }
    }

    /** Reports a failure that a caller caught itself. */
    void report(String operation, Throwable cause) {
        if (this.listener != null) this.listener.onFailure(operation, cause);
    }

    /** Forces a chunk into the ticket; true when the ticket now holds it. */
    boolean force(ForgeChunkManager.Ticket ticket, ChunkCoordIntPair pair) {
        if (ticket == null || pair == null) return false;
        try {
            ForgeChunkManager.forceChunk(ticket, pair);
            return ticket.getChunkList().contains(pair);
        } catch (Throwable failure) {
            report("force", failure);
            return false;
        }
    }

    void unforce(ForgeChunkManager.Ticket ticket, ChunkCoordIntPair pair) {
        if (ticket == null || pair == null) return;
        try {
            ForgeChunkManager.unforceChunk(ticket, pair);
        } catch (Throwable failure) {
            report("unforce", failure);
        }
    }

    void reorder(ForgeChunkManager.Ticket ticket, ChunkCoordIntPair pair) {
        if (ticket == null || pair == null) return;
        try {
            ForgeChunkManager.reorderChunk(ticket, pair);
        } catch (Throwable failure) {
            report("reorder", failure);
        }
    }

    void release(ForgeChunkManager.Ticket ticket) {
        if (ticket == null) return;
        try {
            ForgeChunkManager.releaseTicket(ticket);
        } catch (Throwable failure) {
            report("release", failure);
        }
    }

    /** Caps the ticket's depth at the planner's maximum. */
    static void configure(ForgeChunkManager.Ticket ticket) {
        if (ticket == null) return;
        try {
            int max = ticket.getMaxChunkListDepth();
            if (max <= 0) return;
            int depth = Math.min(NavigationChunkPlanner.MAX_TICKET_CHUNKS, max);
            if (ticket.getChunkListDepth() != depth) ticket.setChunkListDepth(depth);
        } catch (Throwable ignored) {
            // A ticket without a mutable depth still works at its default.
        }
    }

    /** First element of {@code chunks} not present in {@code keep}. */
    static ChunkCoordIntPair firstNotIn(Collection<ChunkCoordIntPair> chunks,
                                        Set<ChunkCoordIntPair> keep) {
        for (ChunkCoordIntPair pair : chunks) {
            if (!keep.contains(pair)) return pair;
        }
        return null;
    }

}
