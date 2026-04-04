package com.wikantik.knowledge;

import java.util.*;

/**
 * Determines which frontmatter keys represent relationships (edges in the knowledge graph)
 * versus properties (JSONB node attributes).
 *
 * Convention: A key produces edges when its value is a {@code List<String>} and the key
 * is not in the excluded set. Keys in the excluded set (tags, keywords, type, etc.) are
 * always treated as node properties regardless of value type.
 */
public class FrontmatterRelationshipDetector {

    /** Keys that are always treated as properties, never as relationships. */
    private static final Set< String > PROPERTY_ONLY_KEYS = Set.of(
        "tags", "keywords", "type", "summary", "date", "author", "cluster",
        "status", "title", "description", "category", "language"
    );

    public record DetectionResult(
        /** relationship-key -> list of target node names */
        Map< String, List< String > > relationships,
        /** property-key -> value (scalar or list, stored as-is in JSONB) */
        Map< String, Object > properties
    ) {}

    @SuppressWarnings( "unchecked" )
    public DetectionResult detect( final Map< String, Object > frontmatter ) {
        final Map< String, List< String > > relationships = new LinkedHashMap<>();
        final Map< String, Object > properties = new LinkedHashMap<>();

        for ( final Map.Entry< String, Object > entry : frontmatter.entrySet() ) {
            final String key = entry.getKey();
            final Object value = entry.getValue();

            if ( PROPERTY_ONLY_KEYS.contains( key ) ) {
                properties.put( key, value );
                continue;
            }

            if ( value instanceof List< ? > list && !list.isEmpty()
                    && list.stream().allMatch( String.class::isInstance ) ) {
                relationships.put( key, (List< String >) list );
            } else {
                properties.put( key, value );
            }
        }

        return new DetectionResult(
            Collections.unmodifiableMap( relationships ),
            Collections.unmodifiableMap( properties )
        );
    }
}
