package com.wikantik.api.knowledge;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Set-at-a-time Knowledge Graph lookups for callers that resolve a whole page of items (e.g. proposal
 * conflict flags) — one query instead of one per item. The defaults loop the single lookups, so every
 * {@link KnowledgeGraphService} implementation and mock keeps working; the database-backed service
 * overrides them.
 */
public interface KgBatchLookups {

    /** @see KnowledgeGraphService#getNodeByName(String, boolean) */
    KgNode getNodeByName( String name, boolean adminBypass );

    /** @see KnowledgeGraphService#isRejected(String, String, String) */
    boolean isRejected( String sourceName, String targetName, String relationshipType );

    /**
     * Batch form of {@link #getNodeByName(String, boolean)}: returns the nodes that resolve, keyed by
     * exact name; names that do not resolve (or are filtered out) are absent. The default loops the
     * single lookup; the database-backed service overrides it with one query.
     */
    default Map< String, KgNode > getNodesByNames( final Collection< String > names, final boolean adminBypass ) {
        final Map< String, KgNode > found = new LinkedHashMap<>();
        for ( final String name : names ) {
            final KgNode node = getNodeByName( name, adminBypass );
            if ( node != null ) found.put( name, node );
        }
        return found;
    }

    /**
     * Batch form of {@link #isRejected(String, String, String)}: given {@code [source, target,
     * relationship]} triples, returns the subset that has a recorded rejection. The default loops the
     * single check; the database-backed service overrides it with one query.
     */
    default Set< List< String > > findRejected( final Collection< List< String > > triples ) {
        final Set< List< String > > rejected = new LinkedHashSet<>();
        for ( final List< String > t : triples ) {
            if ( isRejected( t.get( 0 ), t.get( 1 ), t.get( 2 ) ) ) rejected.add( t );
        }
        return rejected;
    }
}
