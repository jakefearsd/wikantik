package com.wikantik.api.knowledge;

import java.util.Map;

/**
 * An edge the extractor proposes, identified by the string names of its
 * endpoints (resolution to node ids happens at review time). Routed into
 * {@code kg_proposals} with {@code proposal_type="new-edge"}.
 */
public record ProposedEdge(
    String sourceName,
    String targetName,
    String relationshipType,
    Map< String, Object > properties,
    double confidence,
    String reasoning
) {
    public ProposedEdge {
        properties = properties == null ? Map.of() : Map.copyOf( properties );
    }
}
