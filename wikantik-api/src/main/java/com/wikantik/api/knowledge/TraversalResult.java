package com.wikantik.api.knowledge;

import java.util.List;

public record TraversalResult(
    List< KgNode > nodes,
    List< KgEdge > edges
) {}
