package com.wikantik.api.knowledge;

import java.util.UUID;

/**
 * A mention of a node inside a chunk. The listener resolves {@code nodeName}
 * to a {@link KgNode} id via exact-match lookup; unresolved names are either
 * dropped or filed as {@link ProposedNode} instances by the extractor.
 */
public record ExtractedMention(
    UUID chunkId,
    String nodeName,
    double confidence
) {
}
