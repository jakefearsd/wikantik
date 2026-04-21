package com.wikantik.api.knowledge;

import java.util.List;

public record TraversalResult(
    List< KgNode > nodes,
    List< KgEdge > edges
) {
    public TraversalResult {
        nodes = nodes == null ? List.of() : List.copyOf( nodes );
        edges = edges == null ? List.of() : List.copyOf( edges );
    }
}
