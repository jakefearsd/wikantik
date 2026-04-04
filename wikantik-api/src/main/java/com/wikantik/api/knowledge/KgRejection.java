package com.wikantik.api.knowledge;

import java.time.Instant;
import java.util.UUID;

public record KgRejection(
    UUID id,
    String proposedSource,
    String proposedTarget,
    String proposedRelationship,
    String rejectedBy,
    String reason,
    Instant created
) {}
