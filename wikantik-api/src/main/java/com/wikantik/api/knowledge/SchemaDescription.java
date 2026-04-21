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
    public SchemaDescription {
        nodeTypes         = nodeTypes         == null ? List.of() : List.copyOf( nodeTypes );
        relationshipTypes = relationshipTypes == null ? List.of() : List.copyOf( relationshipTypes );
        statusValues      = statusValues      == null ? List.of() : List.copyOf( statusValues );
        propertyKeys      = propertyKeys      == null ? Map.of()  : Map.copyOf( propertyKeys );
    }

    public record PropertyInfo( long count, List< String > sampleValues ) {
        public PropertyInfo {
            sampleValues = sampleValues == null ? List.of() : List.copyOf( sampleValues );
        }
    }
    public record Stats( long nodes, long edges, long unreviewedProposals ) {}
}
