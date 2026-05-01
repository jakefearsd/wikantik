package com.wikantik.api.knowledge;

import java.util.List;

/**
 * The output of {@code ProposalConsolidator}. One row per logical claim.
 * Same shape regardless of {@link Kind}; node-only fields are null on edges
 * and vice versa.
 */
public record ConsolidatedProposal(
        Kind kind,
        String signature,
        String displayName,            // nodes: name; edges: null
        String type,                   // nodes only
        String source, String target,  // edges only
        String predicate,              // edges only
        List<SupportEvidence> support,
        double aggregateConfidence) {

    public enum Kind { NEW_NODE, NEW_EDGE }

    public static ConsolidatedProposal newNode(String signature, String displayName, String type,
                                                List<SupportEvidence> support, double aggregateConfidence) {
        return new ConsolidatedProposal(Kind.NEW_NODE, signature, displayName, type,
                                         null, null, null,
                                         List.copyOf(support), aggregateConfidence);
    }

    public static ConsolidatedProposal newEdge(String signature, String source, String target,
                                                String predicate, List<SupportEvidence> support,
                                                double aggregateConfidence) {
        return new ConsolidatedProposal(Kind.NEW_EDGE, signature, null, null,
                                         source, target, predicate,
                                         List.copyOf(support), aggregateConfidence);
    }
}
