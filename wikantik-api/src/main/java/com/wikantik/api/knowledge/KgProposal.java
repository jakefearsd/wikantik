package com.wikantik.api.knowledge;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record KgProposal(
    UUID id,
    String proposalType,
    String sourcePage,
    Map< String, Object > proposedData,
    double confidence,
    String reasoning,
    String status,
    String reviewedBy,
    Instant created,
    Instant reviewedAt
) {
    public KgProposal {
        proposedData = proposedData == null ? Map.of() : Map.copyOf( proposedData );
    }
}
