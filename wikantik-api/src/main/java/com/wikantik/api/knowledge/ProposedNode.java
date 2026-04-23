package com.wikantik.api.knowledge;

import java.util.Map;

/**
 * A node the extractor proposes. Routed into {@code kg_proposals} with
 * {@code proposal_type="new-node"}; never written directly to {@code kg_nodes}.
 */
public record ProposedNode(
    String name,
    String nodeType,
    Map< String, Object > properties,
    double confidence,
    String reasoning
) {
    public ProposedNode {
        properties = properties == null ? Map.of() : Map.copyOf( properties );
    }
}
