/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.knowledge;

import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Read-focused service that surfaces health statistics for already-accepted Hub pages.
 *
 * <p>Backs the "Existing Hubs" panel in the Hub Discovery admin tab. All statistics are
 * computed on demand from the live KG snapshot and the current TF-IDF content model —
 * nothing is persisted, no caching, one round-trip per call.
 *
 * <p>The only mutation exposed is per-member removal: it parses the hub page's
 * frontmatter, drops the named member from {@code related:}, and saves the page back
 * via the injected {@code PageWriter}. The existing {@code HubSyncFilter} reconciles
 * the {@code kg_edges} table on save.
 */
public class HubOverviewService {

    private static final Logger LOG = LogManager.getLogger( HubOverviewService.class );

    public static final String PROP_NEAR_MISS_THRESHOLD     = "wikantik.hub.overview.nearMissThreshold";
    public static final String PROP_OVERLAP_THRESHOLD       = "wikantik.hub.overview.overlapThreshold";
    public static final String PROP_NEAR_MISS_MAX_RESULTS   = "wikantik.hub.overview.nearMissMaxResults";
    public static final String PROP_MLT_MAX_RESULTS         = "wikantik.hub.overview.moreLikeThisMaxResults";

    private static final double DEFAULT_NEAR_MISS_THRESHOLD = 0.50;
    private static final double DEFAULT_OVERLAP_THRESHOLD   = 0.60;
    private static final int    DEFAULT_NEAR_MISS_MAX       = 10;
    private static final int    DEFAULT_MLT_MAX             = 10;

    private final JdbcKnowledgeRepository    kgRepo;
    private final Supplier< TfidfModel >     contentModelSupplier;
    private final PageManager                pageManager;
    private final HubDiscoveryService.PageWriter pageWriter;
    private final LuceneMlt                  luceneMlt;
    private final double                     nearMissThreshold;
    private final double                     overlapThreshold;
    private final int                        nearMissMaxResults;
    private final int                        mltMaxResults;

    private HubOverviewService( final Builder b ) {
        this.kgRepo                = b.kgRepo;
        this.contentModelSupplier  = b.contentModelSupplier;
        this.pageManager           = b.pageManager;
        this.pageWriter            = b.pageWriter;
        this.luceneMlt             = b.luceneMlt != null ? b.luceneMlt
            : ( seed, max, excludes ) -> Collections.emptyList();
        this.nearMissThreshold     = b.nearMissThreshold     != null ? b.nearMissThreshold     : DEFAULT_NEAR_MISS_THRESHOLD;
        this.overlapThreshold      = b.overlapThreshold      != null ? b.overlapThreshold      : DEFAULT_OVERLAP_THRESHOLD;
        this.nearMissMaxResults    = b.nearMissMaxResults    != null ? b.nearMissMaxResults    : DEFAULT_NEAR_MISS_MAX;
        this.mltMaxResults         = b.mltMaxResults         != null ? b.mltMaxResults         : DEFAULT_MLT_MAX;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- Public records ----

    public record HubOverviewSummary(
        String name, int memberCount, int inboundLinkCount,
        int nearMissCount, double coherence, boolean hasBackingPage
    ) {}

    public record HubDrilldown(
        String name, boolean hasBackingPage, double coherence,
        List< MemberDetail > members,
        List< StubMember > stubMembers,
        List< NearMissTfidf > nearMissTfidf,
        List< MoreLikeThisLucene > moreLikeThisLucene,
        List< OverlapHub > overlapHubs
    ) {}

    public record MemberDetail( String name, double cosineToCentroid, boolean hasPage ) {}
    public record StubMember( String name ) {}
    public record NearMissTfidf( String name, double cosineToCentroid ) {}
    public record MoreLikeThisLucene( String name, double luceneScore ) {}
    public record OverlapHub( String name, double centroidCosine, int sharedMemberCount ) {}

    public record RemoveMemberResult( String removed, int remainingMemberCount ) {}

    // ---- Public API ----

    /** See spec section "listHubOverviews algorithm". */
    public List< HubOverviewSummary > listHubOverviews() {
        final long start = System.currentTimeMillis();
        final TfidfModel resolved = resolveModel();
        if ( resolved == null ) {
            LOG.info( "Hub overview list: skipped — content model unavailable" );
            return Collections.emptyList();
        }
        // 1. Load all KG nodes and partition into hubs / non-hubs.
        final List< com.wikantik.api.knowledge.KgNode > allNodes =
            kgRepo.queryNodes( null, null, 100_000, 0 );
        final Map< String, com.wikantik.api.knowledge.KgNode > hubsByName = new LinkedHashMap<>();
        for ( final var node : allNodes ) {
            if ( node.properties() != null && "hub".equals( node.properties().get( "type" ) ) ) {
                hubsByName.put( node.name(), node );
            }
        }
        if ( hubsByName.isEmpty() ) {
            LOG.info( "Hub overview list: 0 hubs, {}ms", System.currentTimeMillis() - start );
            return Collections.emptyList();
        }

        // 2. Load all 'related' edges and group by hub source.
        final Map< String, Set< String > > hubMembers = loadAllHubMembers();

        // 3. Compute centroids for every hub from model-backed members.
        final Map< String, float[] > centroids = new LinkedHashMap<>();
        final Map< String, Double > coherences = new LinkedHashMap<>();
        for ( final String hubName : hubsByName.keySet() ) {
            final List< float[] > vecs = vectorsFor( resolved, hubMembers.getOrDefault( hubName, Set.of() ) );
            if ( vecs.size() < 2 ) {
                centroids.put( hubName, null );
                coherences.put( hubName, Double.NaN );
                continue;
            }
            final float[][] arr = vecs.toArray( new float[ 0 ][] );
            final float[] centroid = HubDiscoveryService.normalizedCentroid( arr );
            centroids.put( hubName, centroid );
            coherences.put( hubName, HubDiscoveryService.meanDot( arr, centroid ) );
        }

        // 4. Near-miss counts: for each non-member candidate, accumulate per-hub matches.
        final Set< String > allMemberNames = new HashSet<>();
        for ( final Set< String > set : hubMembers.values() ) allMemberNames.addAll( set );
        final Map< String, Integer > nearMissCounts = new HashMap<>();
        for ( final String hubName : hubsByName.keySet() ) nearMissCounts.put( hubName, 0 );
        for ( final String entityName : resolved.getEntityNames() ) {
            if ( hubsByName.containsKey( entityName ) ) continue;
            if ( allMemberNames.contains( entityName ) ) continue;
            final int eid = resolved.entityId( entityName );
            if ( eid < 0 ) continue;
            final float[] vec = resolved.getVector( eid );
            for ( final String hubName : hubsByName.keySet() ) {
                final float[] centroid = centroids.get( hubName );
                if ( centroid == null ) continue;
                if ( cosine( vec, centroid ) >= nearMissThreshold ) {
                    nearMissCounts.merge( hubName, 1, Integer::sum );
                }
            }
        }

        // 5. Inbound links: for each hub, count distinct external link sources whose
        //    target is one of this hub's members, minus the hub itself and minus any
        //    other member of the same hub.
        final List< Map< String, Object > > linksToEdges =
            kgRepo.queryEdgesWithNames( "links_to", null, 100_000, 0 );
        final Map< String, Set< String > > inboundByHub = new HashMap<>();
        for ( final String hubName : hubsByName.keySet() ) inboundByHub.put( hubName, new HashSet<>() );
        for ( final Map< String, Object > edge : linksToEdges ) {
            final String src = (String) edge.get( "source_name" );
            final String tgt = (String) edge.get( "target_name" );
            if ( src == null || tgt == null ) continue;
            for ( final String hubName : hubsByName.keySet() ) {
                final Set< String > members = hubMembers.getOrDefault( hubName, Set.of() );
                if ( !members.contains( tgt ) ) continue;
                if ( hubName.equals( src ) ) continue;
                if ( members.contains( src ) ) continue;
                inboundByHub.get( hubName ).add( src );
            }
        }

        // 6. Build summaries; sort by coherence ascending (NaN to end), name tiebreak.
        final List< HubOverviewSummary > summaries = new ArrayList<>();
        for ( final String hubName : hubsByName.keySet() ) {
            final boolean hasPage = pageExists( hubName );
            summaries.add( new HubOverviewSummary(
                hubName,
                hubMembers.getOrDefault( hubName, Set.of() ).size(),
                inboundByHub.get( hubName ).size(),
                nearMissCounts.get( hubName ),
                coherences.get( hubName ),
                hasPage
            ) );
        }
        summaries.sort( ( a, b ) -> {
            final boolean an = Double.isNaN( a.coherence() );
            final boolean bn = Double.isNaN( b.coherence() );
            if ( an && bn ) return a.name().compareTo( b.name() );
            if ( an ) return 1;
            if ( bn ) return -1;
            final int cmp = Double.compare( a.coherence(), b.coherence() );
            return cmp != 0 ? cmp : a.name().compareTo( b.name() );
        } );

        LOG.info( "Hub overview list: {} hubs, {}ms", summaries.size(), System.currentTimeMillis() - start );
        return summaries;
    }

    // ---- private helpers ----

    private TfidfModel resolveModel() {
        final TfidfModel m = contentModelSupplier.get();
        if ( m == null || m.getEntityCount() == 0 ) return null;
        return m;
    }

    private boolean pageExists( final String name ) {
        try {
            return pageManager.pageExists( name );
        } catch ( final ProviderException e ) {
            LOG.warn( "HubOverviewService pageExists failed for '{}': {}", name, e.getMessage() );
            return false;
        }
    }

    /**
     * Loads every {@code related} edge from the KG and groups targets by hub source name.
     * Only sources whose KG node is hub-typed are included.
     */
    private Map< String, Set< String > > loadAllHubMembers() {
        final List< com.wikantik.api.knowledge.KgNode > allNodes =
            kgRepo.queryNodes( null, null, 100_000, 0 );
        final Set< String > hubNames = new HashSet<>();
        for ( final var node : allNodes ) {
            if ( node.properties() != null && "hub".equals( node.properties().get( "type" ) ) ) {
                hubNames.add( node.name() );
            }
        }
        final List< Map< String, Object > > edges =
            kgRepo.queryEdgesWithNames( "related", null, 100_000, 0 );
        final Map< String, Set< String > > out = new LinkedHashMap<>();
        for ( final String hub : hubNames ) out.put( hub, new HashSet<>() );
        for ( final Map< String, Object > edge : edges ) {
            final String src = (String) edge.get( "source_name" );
            final String tgt = (String) edge.get( "target_name" );
            if ( src == null || tgt == null ) continue;
            if ( !hubNames.contains( src ) ) continue;
            out.get( src ).add( tgt );
        }
        return out;
    }

    private List< float[] > vectorsFor( final TfidfModel m, final Set< String > names ) {
        final List< float[] > out = new ArrayList<>();
        for ( final String name : names ) {
            final int eid = m.entityId( name );
            if ( eid >= 0 ) out.add( m.getVector( eid ) );
        }
        return out;
    }

    private static double cosine( final float[] a, final float[] b ) {
        // Both vectors are L2-normalised in the TF-IDF model, so dot product == cosine.
        double dot = 0;
        for ( int i = 0; i < a.length; i++ ) dot += a[ i ] * b[ i ];
        return dot;
    }

    /** See spec section "loadDrilldown algorithm". Returns null if hub not found. */
    public HubDrilldown loadDrilldown( final String hubName ) {
        throw new UnsupportedOperationException( "implemented in a later task" );
    }

    /** See spec section "removeMember algorithm". */
    public RemoveMemberResult removeMember( final String hubName,
                                              final String member,
                                              final String reviewedBy ) {
        throw new UnsupportedOperationException( "implemented in a later task" );
    }

    // ---- LuceneMlt narrow collaborator (testable seam) ----

    /**
     * Narrow seam over Lucene's MoreLikeThis query so the service can be unit-tested
     * without a real Lucene index. Production wires this to
     * {@code LuceneSearchProvider::moreLikeThis}; tests pass a stub lambda.
     */
    @FunctionalInterface
    public interface LuceneMlt {
        List< MoreLikeThisLucene > findSimilar( String seedDoc, int maxResults, Set< String > excludeNames )
            throws Exception;
    }

    // ---- Builder ----

    public static final class Builder {
        private JdbcKnowledgeRepository    kgRepo;
        private Supplier< TfidfModel >     contentModelSupplier;
        private PageManager                pageManager;
        private HubDiscoveryService.PageWriter pageWriter;
        private LuceneMlt                  luceneMlt;
        private Double                     nearMissThreshold;
        private Double                     overlapThreshold;
        private Integer                    nearMissMaxResults;
        private Integer                    mltMaxResults;

        private Builder() {}

        public Builder kgRepo( final JdbcKnowledgeRepository v ) { this.kgRepo = v; return this; }
        public Builder contentModel( final TfidfModel v ) { this.contentModelSupplier = () -> v; return this; }
        public Builder contentModelSupplier( final Supplier< TfidfModel > v ) { this.contentModelSupplier = v; return this; }
        public Builder pageManager( final PageManager v ) { this.pageManager = v; return this; }
        public Builder pageWriter( final HubDiscoveryService.PageWriter v ) { this.pageWriter = v; return this; }
        public Builder luceneMlt( final LuceneMlt v ) { this.luceneMlt = v; return this; }
        public Builder nearMissThreshold( final double v ) { this.nearMissThreshold = v; return this; }
        public Builder overlapThreshold( final double v ) { this.overlapThreshold = v; return this; }
        public Builder nearMissMaxResults( final int v ) { this.nearMissMaxResults = v; return this; }
        public Builder mltMaxResults( final int v ) { this.mltMaxResults = v; return this; }

        /** Reads known properties from {@code props}, falling back to defaults. */
        public Builder propsFrom( final Properties props ) {
            this.nearMissThreshold = Double.parseDouble(
                props.getProperty( PROP_NEAR_MISS_THRESHOLD, String.valueOf( DEFAULT_NEAR_MISS_THRESHOLD ) ) );
            this.overlapThreshold = Double.parseDouble(
                props.getProperty( PROP_OVERLAP_THRESHOLD, String.valueOf( DEFAULT_OVERLAP_THRESHOLD ) ) );
            this.nearMissMaxResults = Integer.parseInt(
                props.getProperty( PROP_NEAR_MISS_MAX_RESULTS, String.valueOf( DEFAULT_NEAR_MISS_MAX ) ) );
            this.mltMaxResults = Integer.parseInt(
                props.getProperty( PROP_MLT_MAX_RESULTS, String.valueOf( DEFAULT_MLT_MAX ) ) );
            return this;
        }

        public HubOverviewService build() {
            if ( kgRepo == null || contentModelSupplier == null || pageManager == null ) {
                throw new IllegalStateException(
                    "HubOverviewService.Builder: kgRepo, content model, and pageManager are required" );
            }
            return new HubOverviewService( this );
        }
    }
}
