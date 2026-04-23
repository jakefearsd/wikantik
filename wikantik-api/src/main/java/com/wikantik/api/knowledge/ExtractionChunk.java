package com.wikantik.api.knowledge;

import java.util.List;
import java.util.UUID;

/**
 * A persisted content chunk handed to an {@link EntityExtractor}. Carries the
 * minimum needed to extract entities/relations and emit mention rows keyed by
 * the chunk's database id.
 */
public record ExtractionChunk(
    UUID id,
    String pageName,
    int chunkIndex,
    List< String > headingPath,
    String text
) {
    public ExtractionChunk {
        headingPath = headingPath == null ? List.of() : List.copyOf( headingPath );
    }
}
