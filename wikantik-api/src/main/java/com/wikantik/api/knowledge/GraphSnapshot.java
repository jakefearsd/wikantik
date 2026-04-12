package com.wikantik.api.knowledge;

import java.time.Instant;
import java.util.List;

public record GraphSnapshot(
    Instant generatedAt,
    int nodeCount,
    int edgeCount,
    int hubDegreeThreshold,
    List< SnapshotNode > nodes,
    List< SnapshotEdge > edges
) {}
