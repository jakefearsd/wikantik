package com.wikantik.api.knowledge;

import java.time.Duration;
import java.util.List;

/**
 * Output of a single extraction run over one or more chunks. The extractor
 * never touches the database directly — the listener consumes this value and
 * routes nodes/edges through the proposal workflow and mentions through
 * {@code chunk_entity_mentions}.
 */
public record ExtractionResult(
    List< ProposedNode > nodes,
    List< ProposedEdge > edges,
    List< ExtractedMention > mentions,
    String extractorCode,
    Duration latency
) {
    public ExtractionResult {
        nodes = nodes == null ? List.of() : List.copyOf( nodes );
        edges = edges == null ? List.of() : List.copyOf( edges );
        mentions = mentions == null ? List.of() : List.copyOf( mentions );
    }

    public static ExtractionResult empty( final String extractorCode, final Duration latency ) {
        return new ExtractionResult( List.of(), List.of(), List.of(), extractorCode, latency );
    }
}
