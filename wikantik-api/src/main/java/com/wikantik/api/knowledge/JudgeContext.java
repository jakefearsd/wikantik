package com.wikantik.api.knowledge;

import java.util.List;
import java.util.Map;

/**
 * Read-only context handed to a {@link ProposalJudge}: the bodies of pages
 * referenced in the proposal's support array (so the judge can verify
 * grounding), and a small neighborhood of existing nodes for canonicalization
 * decisions ("does GitHub Inc. already exist as GitHub?").
 */
public record JudgeContext(Map<String, String> sourcePageBodies,
                           List<KgNode> neighborhoodNodes) {
    public JudgeContext {
        sourcePageBodies   = sourcePageBodies   == null ? Map.of()  : Map.copyOf(sourcePageBodies);
        neighborhoodNodes  = neighborhoodNodes  == null ? List.of() : List.copyOf(neighborhoodNodes);
    }
}
