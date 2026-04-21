package com.wikantik.api.knowledge;

import java.util.List;

public record GraphSnapshot(
    String generatedAt,
    int nodeCount,
    int edgeCount,
    int hubDegreeThreshold,
    List< SnapshotNode > nodes,
    List< SnapshotEdge > edges
) {
    public GraphSnapshot {
        nodes = nodes == null ? List.of() : List.copyOf( nodes );
        edges = edges == null ? List.of() : List.copyOf( edges );
    }
}
