package com.wikantik.api.knowledge;

import java.util.List;

public record GraphSnapshot(
    String generatedAt,
    int nodeCount,
    int edgeCount,
    int hubDegreeThreshold,
    List< SnapshotNode > nodes,
    List< SnapshotEdge > edges
) {}
