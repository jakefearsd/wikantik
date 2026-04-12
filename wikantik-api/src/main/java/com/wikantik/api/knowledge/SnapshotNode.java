package com.wikantik.api.knowledge;

import java.util.List;
import java.util.UUID;

public record SnapshotNode(
    UUID id,
    String name,
    String type,
    String role,
    Provenance provenance,
    String sourcePage,
    int degreeIn,
    int degreeOut,
    boolean restricted,
    String cluster,
    List< String > tags,
    String status
) {}
