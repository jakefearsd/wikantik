package com.wikantik.api.knowledge;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record KgNode(
    UUID id,
    String name,
    String nodeType,
    String sourcePage,
    Provenance provenance,
    Map< String, Object > properties,
    Instant created,
    Instant modified
) {
    public KgNode {
        properties = properties == null ? Map.of() : Map.copyOf( properties );
    }

    /** Returns true if this node is a stub (referenced but has no wiki page yet). */
    public boolean isStub() {
        return sourcePage == null;
    }
}
