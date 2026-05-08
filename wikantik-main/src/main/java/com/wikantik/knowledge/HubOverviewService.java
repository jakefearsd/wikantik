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
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Read-focused service that surfaces health statistics for already-accepted Hub pages.
 *
 * <p>Backs the "Existing Hubs" panel in the Hub Discovery admin tab. All statistics are
 * computed on demand from the live KG snapshot and the unified mention-centroid vectors
 * ({@link NodeMentionSimilarity}) — nothing is persisted, no caching, one round-trip per
 * {@code allCentroids()} fetch per call.
 *
 * <p>The only mutation exposed is per-member removal: it parses the hub page's
 * frontmatter, drops the named member from {@code related:}, and saves the page back
 * via the injected {@code PageWriter}. The existing {@code HubSyncFilter} reconciles
 * the {@code kg_edges} table on save.
 *
 * <p><b>Phase 11 Ckpt 6:</b> heavy internals delegated to
 * {@link HubMemberLoader} (hub/member loading from KG + frontmatter) and
 * {@link HubVectorAnalytics} (centroid/near-miss/overlap computations). This
 * facade owns the public records, top-level orchestration, and the mutation
 * ({@link #removeMember}).
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

    private final KgNodeRepository           kgNodes;
    private final KgEdgeRepository           kgEdges;
    private final NodeMentionSimilarity      similarity;
    private final PageManager                pageManager;
    private final HubDiscoveryService.PageWriter pageWriter;
    /**
     * Volatile so the post-construction setter ({@link #setLuceneMlt}) is
     * safely visible to reader threads. Phase 7 Ckpt 4 of the wikantik-main
     * subsystem decomposition wires this AFTER both the Knowledge and
     * Search subsystems are constructed, breaking the Search↔Knowledge
     * construction-time cycle.
     */
    private volatile LuceneMlt               luceneMlt;
    private final int                        mltMaxResults;

    // Composed helpers (Phase 11 Ckpt 6)
    private final HubMemberLoader            memberLoader;
    private final HubVectorAnalytics         vectorAnalytics;

    private HubOverviewService( final Builder b ) {
        this.kgNodes               = b.kgNodes;
        this.kgEdges               = b.kgEdges;
        this.similarity            = b.similarity;
        this.pageManager           = b.pageManager;
        this.pageWriter            = b.pageWriter;
        this.luceneMlt             = b.luceneMlt != null ? b.luceneMlt
            : ( seed, max, excludes ) -> Collections.emptyList();
        final double nearMissThreshold  = b.nearMissThreshold  != null ? b.nearMissThreshold  : DEFAULT_NEAR_MISS_THRESHOLD;
        final double overlapThreshold   = b.overlapThreshold   != null ? b.overlapThreshold   : DEFAULT_OVERLAP_THRESHOLD;
        final int    nearMissMaxResults = b.nearMissMaxResults  != null ? b.nearMissMaxResults : DEFAULT_NEAR_MISS_MAX;
        this.mltMaxResults             = b.mltMaxResults        != null ? b.mltMaxResults      : DEFAULT_MLT_MAX;
        this.memberLoader   = new HubMemberLoader( kgNodes, kgEdges, pageManager );
        this.vectorAnalytics = new HubVectorAnalytics( nearMissThreshold, overlapThreshold, nearMissMaxResults );
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
        final Map< String, float[] > vectors = similarity.allCentroids();

        final Map< String, com.wikantik.api.knowledge.KgNode > hubsByName = memberLoader.loadHubNodes();
        if ( hubsByName.isEmpty() ) {
            LOG.info( "Hub overview list: 0 hubs, {}ms", System.currentTimeMillis() - start );
            return Collections.emptyList();
        }

        final Map< String, Set< String > > hubMembers   = memberLoader.loadAllHubMembers();
        final Map< String, HubVectorAnalytics.HubShape > shapes =
            vectorAnalytics.computeHubShapes( vectors, hubsByName, hubMembers );
        final Map< String, Integer > nearMisses =
            vectorAnalytics.computeNearMissCounts( vectors, hubsByName, hubMembers, shapes );
        final Map< String, Set< String > > inboundByHub = computeInboundLinkSources( hubsByName, hubMembers );

        final List< HubOverviewSummary > summaries = buildSummaries(
            hubsByName, hubMembers, shapes, nearMisses, inboundByHub );

        LOG.info( "Hub overview list: {} hubs, {}ms", summaries.size(), System.currentTimeMillis() - start );
        return summaries;
    }

    /**
     * Step 5 of listHubOverviews: per-hub set of external link sources. A source counts as
     * inbound when it links into one of the hub's members, but isn't the hub itself and
     * isn't a sibling member of the same hub.
     */
    private Map< String, Set< String > > computeInboundLinkSources(
            final Map< String, com.wikantik.api.knowledge.KgNode > hubsByName,
            final Map< String, Set< String > > hubMembers ) {
        final List< Map< String, Object > > linksToEdges =
            kgEdges.queryEdgesWithNames( "links_to", null, 100_000, 0 );
        final Map< String, Set< String > > inboundByHub = new java.util.HashMap<>();
        for ( final String hubName : hubsByName.keySet() ) inboundByHub.put( hubName, new HashSet<>() );
        for ( final Map< String, Object > edge : linksToEdges ) {
            final String src = (String) edge.get( "source_name" );
            final String tgt = (String) edge.get( "target_name" );
            if ( src == null || tgt == null ) continue;
            for ( final String hubName : hubsByName.keySet() ) {
                final Set< String > members = hubMembers.getOrDefault( hubName, Set.of() );
                if ( !members.contains( tgt ) ) continue;
                if ( hubName.equals( src ) || members.contains( src ) ) continue;
                inboundByHub.get( hubName ).add( src );
            }
        }
        return inboundByHub;
    }

    /** Zip the per-hub maps into summary records and sort by coherence ascending (NaN last). */
    private List< HubOverviewSummary > buildSummaries(
            final Map< String, com.wikantik.api.knowledge.KgNode > hubsByName,
            final Map< String, Set< String > > hubMembers,
            final Map< String, HubVectorAnalytics.HubShape > shapes,
            final Map< String, Integer > nearMissCounts,
            final Map< String, Set< String > > inboundByHub ) {
        final List< HubOverviewSummary > summaries = new ArrayList<>();
        for ( final String hubName : hubsByName.keySet() ) {
            summaries.add( new HubOverviewSummary(
                hubName,
                hubMembers.getOrDefault( hubName, Set.of() ).size(),
                inboundByHub.get( hubName ).size(),
                nearMissCounts.get( hubName ),
                shapes.get( hubName ).coherence(),
                pageExists( hubName )
            ) );
        }
        summaries.sort( HubOverviewService::compareByCoherenceAsc );
        return summaries;
    }

    /** Coherence ascending, NaN last, name as tiebreak. */
    private static int compareByCoherenceAsc( final HubOverviewSummary a, final HubOverviewSummary b ) {
        final boolean an = Double.isNaN( a.coherence() );
        final boolean bn = Double.isNaN( b.coherence() );
        if ( an && bn ) return a.name().compareTo( b.name() );
        if ( an ) return 1;
        if ( bn ) return -1;
        final int cmp = Double.compare( a.coherence(), b.coherence() );
        return cmp != 0 ? cmp : a.name().compareTo( b.name() );
    }

    /** See spec section "loadDrilldown algorithm". Returns null if hub not found. */
    public HubDrilldown loadDrilldown( final String hubName ) {
        if ( hubName == null || hubName.isBlank() ) return null;
        final long start = System.currentTimeMillis();

        if ( !memberLoader.hubNodeExists( hubName ) ) return null;

        final Map< String, float[] > vectors = similarity.allCentroids();
        final Map< String, Set< String > > allHubMembers = memberLoader.loadAllHubMembers();
        final Set< String > rawMembers = allHubMembers.getOrDefault( hubName, Set.of() );

        final MemberPartition partition = partitionMembers( rawMembers );
        final HubVectorAnalytics.HubShape shape = vectorAnalytics.hubShapeOf( vectors, rawMembers );
        final List< MemberDetail > memberDetails =
            buildMemberDetails( vectors, shape.centroid(), partition.existing() );

        final List< NearMissTfidf > nearMissList = vectorAnalytics.computeNearMissTfidf(
            vectors, shape.centroid(), allHubMembers.keySet(), rawMembers );
        final List< OverlapHub > overlapHubs = vectorAnalytics.computeOverlapHubs(
            vectors, shape.centroid(), allHubMembers, hubName, rawMembers );
        final List< MoreLikeThisLucene > mltList = fetchMoreLikeThis( hubName, rawMembers, memberDetails );

        final boolean hasBackingPage = pageExists( hubName );
        LOG.info( "Hub overview drilldown: hub='{}' members={} nearMiss={} overlap={} {}ms",
            hubName, memberDetails.size(), nearMissList.size(), overlapHubs.size(),
            System.currentTimeMillis() - start );
        return new HubDrilldown(
            hubName, hasBackingPage, shape.coherence(),
            memberDetails, partition.stubs(), nearMissList, mltList, overlapHubs );
    }

    /** Sort members alphabetically and split existing pages from stubs. */
    private record MemberPartition( List< String > existing, List< StubMember > stubs ) {}

    private MemberPartition partitionMembers( final Set< String > rawMembers ) {
        final List< String > sorted = new ArrayList<>( rawMembers );
        Collections.sort( sorted );
        final List< String > existing = new ArrayList<>();
        final List< StubMember > stubs = new ArrayList<>();
        for ( final String m : sorted ) {
            if ( pageExists( m ) ) existing.add( m );
            else stubs.add( new StubMember( m ) );
        }
        return new MemberPartition( existing, stubs );
    }

    /** Per-member cosine to centroid, NaN when vector or centroid is missing, sorted ascending. */
    private List< MemberDetail > buildMemberDetails(
            final Map< String, float[] > vectors, final float[] centroid,
            final List< String > existing ) {
        final List< MemberDetail > out = new ArrayList<>();
        for ( final String m : existing ) {
            out.add( new MemberDetail( m, memberCosine( vectors, centroid, m ), true ) );
        }
        out.sort( HubOverviewService::compareByCosineAsc );
        return out;
    }

    private static double memberCosine( final Map< String, float[] > vectors,
                                         final float[] centroid, final String name ) {
        if ( centroid == null ) return Double.NaN;
        final float[] vec = vectors.get( name );
        return vec != null ? HubVectorAnalytics.cosine( vec, centroid ) : Double.NaN;
    }

    private static int compareByCosineAsc( final MemberDetail a, final MemberDetail b ) {
        final boolean an = Double.isNaN( a.cosineToCentroid() );
        final boolean bn = Double.isNaN( b.cosineToCentroid() );
        if ( an && bn ) return a.name().compareTo( b.name() );
        if ( an ) return 1;
        if ( bn ) return -1;
        return Double.compare( a.cosineToCentroid(), b.cosineToCentroid() );
    }

    /**
     * Lucene MoreLikeThis with empty fallback on any failure. Seed = hub itself if it
     * has a backing page; otherwise the highest-cosine member (memberDetails is sorted
     * ascending by cosine, so the last element wins).
     */
    private List< MoreLikeThisLucene > fetchMoreLikeThis(
            final String hubName, final Set< String > rawMembers,
            final List< MemberDetail > memberDetails ) {
        final String seed = pickMoreLikeThisSeed( hubName, memberDetails );
        if ( seed == null ) return List.of();
        final Set< String > excludes = new HashSet<>( rawMembers );
        excludes.add( hubName );
        try {
            final List< MoreLikeThisLucene > raw = luceneMlt.findSimilar( seed, mltMaxResults, excludes );
            return raw != null ? raw : List.of();
        } catch ( final Exception e ) {
            LOG.warn( "Hub overview drilldown: Lucene MoreLikeThis failed for hub '{}': {}",
                hubName, e.getMessage() );
            return List.of();
        }
    }

    private String pickMoreLikeThisSeed( final String hubName,
                                          final List< MemberDetail > memberDetails ) {
        if ( pageExists( hubName ) ) return hubName;
        if ( memberDetails.isEmpty() ) return null;
        return memberDetails.get( memberDetails.size() - 1 ).name();
    }

    // ---- private helpers ----

    private boolean pageExists( final String name ) {
        try {
            return pageManager.pageExists( name );
        } catch ( final ProviderException e ) {
            LOG.warn( "HubOverviewService pageExists failed for '{}': {}", name, e.getMessage() );
            return false;
        }
    }

    /** See spec section "removeMember algorithm". */
    public RemoveMemberResult removeMember( final String hubName,
                                              final String member,
                                              final String reviewedBy ) {
        if ( hubName == null || hubName.isBlank() ) {
            throw new IllegalArgumentException( "hubName must not be empty" );
        }
        if ( member == null || member.isBlank() ) {
            throw new IllegalArgumentException( "member must not be empty" );
        }
        if ( pageWriter == null ) {
            throw new IllegalStateException(
                "HubOverviewService: pageWriter is required for removeMember" );
        }

        // 1. Read existing page text — 404 if missing.
        final String text;
        try {
            text = pageManager.getPageText( hubName, PageProvider.LATEST_VERSION );
        } catch ( final ProviderException e ) {
            throw new HubOverviewException( "Failed to read hub page '" + hubName + "'", e );
        }
        if ( text == null ) {
            throw new HubOverviewException( "Hub page '" + hubName + "' not found", null );
        }

        // 2. Parse frontmatter and assert type=hub.
        final ParsedPage parsed = FrontmatterParser.parse( text );
        final Map< String, Object > metadata = new LinkedHashMap<>( parsed.metadata() );
        if ( !"hub".equals( metadata.get( "type" ) ) ) {
            throw new IllegalArgumentException(
                "Page '" + hubName + "' is not a hub page (type != 'hub')" );
        }

        // 3. Pull related list and validate member is present.
        final Object rawRelated = metadata.get( "related" );
        if ( !( rawRelated instanceof List< ? > rawList ) ) {
            throw new IllegalArgumentException(
                "Hub page '" + hubName + "' has no 'related' list in frontmatter" );
        }
        final List< String > related = new ArrayList<>();
        for ( final Object o : rawList ) {
            if ( o != null ) related.add( o.toString() );
        }
        if ( !related.contains( member ) ) {
            throw new IllegalArgumentException(
                "Member '" + member + "' is not in hub '" + hubName + "'" );
        }

        // 4. Remove preserving order.
        related.remove( member );

        // 5. 2-member-minimum guard.
        if ( related.size() < 2 ) {
            throw new HubOverviewException(
                "Removing '" + member + "' would leave hub '" + hubName
                    + "' with fewer than 2 members; delete the hub instead", null );
        }

        // 6. Re-serialize and save.
        metadata.put( "related", related );
        final String newText = FrontmatterWriter.write( metadata, parsed.body() );
        try {
            pageWriter.write( hubName, newText );
        } catch ( final Exception e ) {
            throw new HubOverviewException(
                "Failed to save updated hub page '" + hubName + "'", e );
        }

        LOG.info( "Hub overview: removed member '{}' from '{}', remaining {} (reviewed by {})",
            member, hubName, related.size(), reviewedBy );
        return new RemoveMemberResult( member, related.size() );
    }

    // ---- LuceneMlt narrow collaborator (testable seam) ----

    /**
     * Narrow seam over Lucene's MoreLikeThis query so the service can be unit-tested
     * without a real Lucene index.
     */
    @FunctionalInterface
    public interface LuceneMlt {
        List< MoreLikeThisLucene > findSimilar( String seedDoc, int maxResults, Set< String > excludeNames )
            throws Exception;
    }

    /**
     * Post-construction wire of the {@link LuceneMlt} seam. Phase 7 Ckpt 4:
     * Search builds AFTER Knowledge, so {@code WikiEngine.initialize} calls this
     * once both subsystems are ready.
     */
    public void setLuceneMlt( final LuceneMlt mlt ) {
        if ( mlt == null ) {
            LOG.warn( "HubOverviewService.setLuceneMlt: ignoring null mlt; existing seam preserved" );
            return;
        }
        this.luceneMlt = mlt;
    }

    // ---- Builder ----

    public static final class Builder {
        private KgNodeRepository           kgNodes;
        private KgEdgeRepository           kgEdges;
        private NodeMentionSimilarity      similarity;
        private PageManager                pageManager;
        private HubDiscoveryService.PageWriter pageWriter;
        private LuceneMlt                  luceneMlt;
        private Double                     nearMissThreshold;
        private Double                     overlapThreshold;
        private Integer                    nearMissMaxResults;
        private Integer                    mltMaxResults;

        private Builder() {}

        public Builder kgNodes( final KgNodeRepository v ) { this.kgNodes = v; return this; }
        public Builder kgEdges( final KgEdgeRepository v ) { this.kgEdges = v; return this; }
        public Builder similarity( final NodeMentionSimilarity v ) { this.similarity = v; return this; }
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
            if ( kgNodes == null || kgEdges == null || similarity == null || pageManager == null ) {
                throw new IllegalStateException(
                    "HubOverviewService.Builder: kgNodes, kgEdges, similarity, and pageManager are required" );
            }
            return new HubOverviewService( this );
        }
    }
}
