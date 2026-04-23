package com.wikantik.api.knowledge;

import java.util.List;
import java.util.Map;

/**
 * Per-extraction context passed alongside each chunk. Carries a dictionary of
 * already-known nodes (so the extractor anchors mentions to existing ids when
 * possible) plus the page-level frontmatter for light contextual hints.
 *
 * <p>Kept deliberately small — the extractor should rely on chunk text +
 * existing-node names for precision; richer context would bloat the prompt
 * cache without clear wins.
 */
public record ExtractionContext(
    String pageName,
    List< KgNode > existingNodes,
    Map< String, Object > frontmatter
) {
    public ExtractionContext {
        existingNodes = existingNodes == null ? List.of() : List.copyOf( existingNodes );
        frontmatter = frontmatter == null ? Map.of() : Map.copyOf( frontmatter );
    }
}
