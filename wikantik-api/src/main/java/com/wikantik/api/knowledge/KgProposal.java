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
    String status,             // human verdict: pending | approved | rejected
    String reviewedBy,
    Instant created,
    Instant reviewedAt,
    String tier,               // none | machine | human
    String machineStatus,      // null | approved | rejected | abstain
    Double machineConfidence,
    Instant machineJudgedAt,
    String machineModel
) {
    public KgProposal {
        proposedData = proposedData == null ? Map.of() : Map.copyOf( proposedData );
        tier = tier == null ? "none" : tier;
    }
}
