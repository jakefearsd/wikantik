package com.wikantik.api.knowledge;

import java.util.UUID;

public record SnapshotEdge(
    UUID id,
    UUID source,
    UUID target,
    String relationshipType,
    Provenance provenance
) {}
