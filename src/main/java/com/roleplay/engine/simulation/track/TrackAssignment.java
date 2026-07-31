package com.roleplay.engine.simulation.track;

import com.roleplay.engine.core.Track;

import java.util.List;

/**
 * Per-agent track assignment produced by {@link SpatialTrackResolver}.
 *
 * <p>Connects the 2D spatial layer (position / distance / hearing) to the Track
 * system's information-isolation layer:
 * <ul>
 *   <li>MERGED   — agent shares full context with its conversation partners</li>
 *   <li>WEAK     — agent only receives a summarized observation, never full content</li>
 *   <li>ISOLATED — agent sees nothing (out of hearing range / private-room boundary)</li>
 * </ul>
 *
 * <p>Reuses {@link Track.Mode} as the canonical track-mode enum — no duplicate
 * enum is introduced (core/Track.java already defines MERGED / WEAK / ISOLATED).
 *
 * @param agentId       agent this assignment belongs to
 * @param type          track mode for this agent (MERGED / WEAK / ISOLATED)
 * @param visibleAgents agents this agent may perceive: direct conversation partners
 *                      for MERGED; audible-but-summarized peers for WEAK; empty for ISOLATED
 * @param contextNote   context hint for this agent (摘要说明或"完全隔离"等)
 */
public record TrackAssignment(
        String agentId,
        Track.Mode type,
        List<String> visibleAgents,
        String contextNote
) {
    public TrackAssignment {
        visibleAgents = visibleAgents == null ? List.of() : List.copyOf(visibleAgents);
        contextNote = contextNote == null ? "" : contextNote;
    }

    public static TrackAssignment of(String agentId, Track.Mode type,
                                     List<String> visibleAgents, String contextNote) {
        return new TrackAssignment(agentId, type, visibleAgents, contextNote);
    }

    /** Convenience factory for a fully isolated agent. */
    public static TrackAssignment isolated(String agentId, String reason) {
        return new TrackAssignment(agentId, Track.Mode.ISOLATED, List.of(), reason);
    }
}
