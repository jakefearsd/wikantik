package com.wikantik.api.knowledge;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record KgEdge(
    UUID id,
    UUID sourceId,
    UUID targetId,
    String relationshipType,
    Provenance provenance,
    Map< String, Object > properties,
    Instant created,
    Instant modified
) {
    public KgEdge {
        properties = properties == null ? Map.of() : Map.copyOf( properties );
    }
}
