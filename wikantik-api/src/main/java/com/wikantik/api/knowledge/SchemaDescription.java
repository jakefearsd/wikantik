package com.wikantik.api.knowledge;

import java.util.List;
import java.util.Map;

public record SchemaDescription(
    List< String > nodeTypes,
    List< String > relationshipTypes,
    List< String > statusValues,
    Map< String, PropertyInfo > propertyKeys,
    Stats stats
) {
    public record PropertyInfo( long count, List< String > sampleValues ) {}
    public record Stats( long nodes, long edges, long unreviewedProposals ) {}
}
