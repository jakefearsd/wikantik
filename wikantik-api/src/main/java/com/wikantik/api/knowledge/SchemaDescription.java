package com.wikantik.api.knowledge;

import java.util.List;
import java.util.Map;

public record SchemaDescription(
    List< String > nodeTypes,
    List< String > relationshipTypes,
    List< String > statusValues,
    Map< String, PropertyInfo > propertyKeys,
    Stats stats
) {
    public SchemaDescription {
        nodeTypes         = nodeTypes         == null ? List.of() : List.copyOf( nodeTypes );
        relationshipTypes = relationshipTypes == null ? List.of() : List.copyOf( relationshipTypes );
        statusValues      = statusValues      == null ? List.of() : List.copyOf( statusValues );
        propertyKeys      = propertyKeys      == null ? Map.of()  : Map.copyOf( propertyKeys );
    }

    public record PropertyInfo( long count, List< String > sampleValues ) {
        public PropertyInfo {
            sampleValues = sampleValues == null ? List.of() : List.copyOf( sampleValues );
        }
    }

    /**
     * Pending-proposal breakdown surfaced in {@link Stats} so the admin UI can
     * say more than "N pending proposals" — admins need to know what the queue
     * is made of (new nodes vs new edges) and the LLM-judge disposition that
     * determines who should look at them next.
     *
     * <ul>
     *   <li>{@code total} — every proposal with {@code status = 'pending'}.</li>
     *   <li>{@code newNodes}, {@code newEdges} — split by {@code proposal_type}.</li>
     *   <li>{@code judgeApproved} — LLM judge greenlit ({@code machine_status='approved'});
     *       awaiting a human OK in the Proposals tab.</li>
     *   <li>{@code judgeAbstained} — LLM judge couldn't decide
     *       ({@code machine_status='abstain'}); needs human triage.</li>
     *   <li>{@code unjudged} — judge hasn't run yet ({@code machine_status IS NULL}).</li>
     * </ul>
     *
     * <p>The four judge-disposition buckets sum to {@code total} (every pending
     * proposal lands in exactly one).
     */
    public record PendingBreakdown(
        long total,
        long newNodes,
        long newEdges,
        long judgeApproved,
        long judgeAbstained,
        long unjudged
    ) {
        public static final PendingBreakdown EMPTY =
            new PendingBreakdown( 0, 0, 0, 0, 0, 0 );
    }

    public record Stats(
        long nodes,
        long edges,
        long unreviewedProposals,
        PendingBreakdown pendingBreakdown
    ) {
        public Stats {
            pendingBreakdown = pendingBreakdown == null
                ? PendingBreakdown.EMPTY : pendingBreakdown;
        }
    }
}
