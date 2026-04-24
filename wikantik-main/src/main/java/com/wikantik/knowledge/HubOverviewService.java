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
import java.util.HashMap;
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
    private final NodeMentionSimilarity      similarity;
    private final PageManager                pageManager;
    private final HubDiscoveryService.PageWriter pageWriter;
    private final LuceneMlt                  luceneMlt;
    private final double                     nearMissThreshold;
    private final double                     overlapThreshold;
    private final int                        nearMissMaxResults;
    private final int                        mltMaxResults;

    private HubOverviewService( final Builder b ) {
        this.kgRepo                = b.kgRepo;
        this.similarity            = b.similarity;
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
        // When no mentions exist yet (e.g. Phase 1 with the extractor still disabled) the
        // vectors map is empty. Downstream computeHubShapes / nearMisses tolerate that
        // path — shapes come back with NaN coherence and nearMiss counts are 0 — so the
        // admin panel still lists every hub with its member count and inbound links.
        final Map< String, float[] > vectors = similarity.allCentroids();

        final Map< String, com.wikantik.api.knowledge.KgNode > hubsByName = loadHubNodes();
        if ( hubsByName.isEmpty() ) {
            LOG.info( "Hub overview list: 0 hubs, {}ms", System.currentTimeMillis() - start );
            return Collections.emptyList();
        }

        final Map< String, Set< String > > hubMembers   = loadAllHubMembers();
        final Map< String, HubShape >      shapes       = computeHubShapes( vectors, hubsByName, hubMembers );
        final Map< String, Integer >       nearMisses   = computeNearMissCounts( vectors, hubsByName, hubMembers, shapes );
        final Map< String, Set< String > > inboundByHub = computeInboundLinkSources( hubsByName, hubMembers );

        final List< HubOverviewSummary > summaries = buildSummaries(
            hubsByName, hubMembers, shapes, nearMisses, inboundByHub );

        LOG.info( "Hub overview list: {} hubs, {}ms", summaries.size(), System.currentTimeMillis() - start );
        return summaries;
    }

    /**
     * Step 1: collect every page that should appear as a hub. Two sources are
     * merged so the panel works both before and after the entity extractor has
     * populated the KG:
     * <ol>
     *   <li>KG nodes typed {@code type=hub} (populated by the extractor).</li>
     *   <li>Wiki pages whose YAML frontmatter declares {@code type: hub} —
     *       the source of truth now that {@code GraphProjector} no longer
     *       projects frontmatter into the graph. For these we synthesize a
     *       placeholder {@link com.wikantik.api.knowledge.KgNode} so downstream
     *       steps that iterate {@code hubsByName.keySet()} keep working.</li>
     * </ol>
     * Insertion order is preserved — KG nodes first, then any additional
     * frontmatter-only hubs, so existing deployments see stable row order.
     */
    private Map< String, com.wikantik.api.knowledge.KgNode > loadHubNodes() {
        final List< com.wikantik.api.knowledge.KgNode > allNodes =
            kgRepo.queryNodes( null, null, 100_000, 0 );
        final Map< String, com.wikantik.api.knowledge.KgNode > hubsByName = new LinkedHashMap<>();
        for ( final var node : allNodes ) {
            if ( node.properties() != null && "hub".equals( node.properties().get( "type" ) ) ) {
                hubsByName.put( node.name(), node );
            }
        }
        for ( final Map.Entry< String, List< String > > e : loadFrontmatterHubs().entrySet() ) {
            hubsByName.putIfAbsent( e.getKey(), synthesizeHubNode( e.getKey() ) );
        }
        return hubsByName;
    }

    /**
     * Scan every wiki page for a {@code type: hub} frontmatter declaration.
     * Returns a map of hub name to its declared {@code related:} member list
     * (empty when the list is missing or non-string-valued). Returned list is
     * order-preserving.
     */
    private Map< String, List< String > > loadFrontmatterHubs() {
        final Map< String, List< String > > hubs = new LinkedHashMap<>();
        if ( pageManager == null ) return hubs;
        final java.util.Collection< com.wikantik.api.core.Page > pages;
        try {
            pages = pageManager.getAllPages();
        } catch ( final ProviderException e ) {
            LOG.warn( "HubOverviewService.loadFrontmatterHubs: getAllPages failed: {}", e.getMessage() );
            return hubs;
        }
        for ( final com.wikantik.api.core.Page p : pages ) {
            final String name = p.getName();
            final String raw;
            try {
                raw = pageManager.getPureText( name, PageProvider.LATEST_VERSION );
            } catch ( final Exception e ) {
                LOG.info( "HubOverviewService: skipping '{}' — getPureText failed: {}",
                    name, e.getMessage() );
                continue;
            }
            if ( raw == null || raw.isEmpty() ) continue;
            final ParsedPage parsed;
            try {
                parsed = FrontmatterParser.parse( raw );
            } catch ( final Exception e ) {
                LOG.info( "HubOverviewService: skipping '{}' — frontmatter parse failed: {}",
                    name, e.getMessage() );
                continue;
            }
            final Map< String, Object > meta = parsed.metadata();
            if ( meta == null || !"hub".equals( meta.get( "type" ) ) ) continue;
            final List< String > related = coerceStringList( meta.get( "related" ) );
            hubs.put( name, related );
        }
        return hubs;
    }

    @SuppressWarnings( "unchecked" )
    private static List< String > coerceStringList( final Object value ) {
        if ( !( value instanceof List< ? > list ) ) return List.of();
        final List< String > out = new ArrayList<>( list.size() );
        for ( final Object item : list ) {
            if ( item != null ) out.add( item.toString() );
        }
        return out;
    }

    /**
     * Synthesize a placeholder {@link com.wikantik.api.knowledge.KgNode} for a
     * hub that exists only in page frontmatter. The admin panel only consumes
     * {@code name()} and {@code properties()} from the node, so a synthetic
     * UUID and minimal {@code type=hub} property bag are sufficient.
     */
    private static com.wikantik.api.knowledge.KgNode synthesizeHubNode( final String hubName ) {
        return new com.wikantik.api.knowledge.KgNode(
            java.util.UUID.nameUUIDFromBytes( ( "frontmatter-hub:" + hubName ).getBytes() ),
            hubName,
            "hub",
            hubName,
            com.wikantik.api.knowledge.Provenance.HUMAN_AUTHORED,
            Map.of( "type", "hub" ),
            null,
            null
        );
    }

    /** Normalized centroid and mean-dot coherence for a hub; centroid is null and coherence NaN if &lt;2 vectors. */
    private record HubShape( float[] centroid, double coherence ) {
        static HubShape empty() { return new HubShape( null, Double.NaN ); }
    }

    /** Step 3: per-hub centroid + coherence over members that have mention-centroid vectors. */
    private Map< String, HubShape > computeHubShapes(
            final Map< String, float[] > vectors,
            final Map< String, com.wikantik.api.knowledge.KgNode > hubsByName,
            final Map< String, Set< String > > hubMembers ) {
        final Map< String, HubShape > shapes = new LinkedHashMap<>();
        for ( final String hubName : hubsByName.keySet() ) {
            final List< float[] > vecs = vectorsFor( vectors, hubMembers.getOrDefault( hubName, Set.of() ) );
            if ( vecs.size() < 2 ) {
                shapes.put( hubName, HubShape.empty() );
                continue;
            }
            final float[][] arr = vecs.toArray( new float[ 0 ][] );
            final float[] centroid = HubDiscoveryService.normalizedCentroid( arr );
            shapes.put( hubName, new HubShape( centroid, HubDiscoveryService.meanDot( arr, centroid ) ) );
        }
        return shapes;
    }

    /** Step 4: per-hub count of non-member non-hub entities whose cosine ≥ threshold. */
    private Map< String, Integer > computeNearMissCounts(
            final Map< String, float[] > vectors,
            final Map< String, com.wikantik.api.knowledge.KgNode > hubsByName,
            final Map< String, Set< String > > hubMembers,
            final Map< String, HubShape > shapes ) {
        final Set< String > allMemberNames = new HashSet<>();
        for ( final Set< String > set : hubMembers.values() ) allMemberNames.addAll( set );
        final Map< String, Integer > counts = new HashMap<>();
        for ( final String hubName : hubsByName.keySet() ) counts.put( hubName, 0 );
        for ( final Map.Entry< String, float[] > entry : vectors.entrySet() ) {
            final String entityName = entry.getKey();
            if ( hubsByName.containsKey( entityName ) ) continue;
            if ( allMemberNames.contains( entityName ) ) continue;
            final float[] vec = entry.getValue();
            for ( final String hubName : hubsByName.keySet() ) {
                final float[] centroid = shapes.get( hubName ).centroid();
                if ( centroid != null && cosine( vec, centroid ) >= nearMissThreshold ) {
                    counts.merge( hubName, 1, Integer::sum );
                }
            }
        }
        return counts;
    }

    /**
     * Step 5: per-hub set of external link sources. A source counts as inbound when it
     * links into one of the hub's members, but isn't the hub itself and isn't a
     * sibling member of the same hub.
     */
    private Map< String, Set< String > > computeInboundLinkSources(
            final Map< String, com.wikantik.api.knowledge.KgNode > hubsByName,
            final Map< String, Set< String > > hubMembers ) {
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
                if ( hubName.equals( src ) || members.contains( src ) ) continue;
                inboundByHub.get( hubName ).add( src );
            }
        }
        return inboundByHub;
    }

    /** Step 6: zip the per-hub maps into summary records and sort by coherence ascending (NaN last). */
    private List< HubOverviewSummary > buildSummaries(
            final Map< String, com.wikantik.api.knowledge.KgNode > hubsByName,
            final Map< String, Set< String > > hubMembers,
            final Map< String, HubShape > shapes,
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

    // ---- private helpers ----

    private boolean pageExists( final String name ) {
        try {
            return pageManager.pageExists( name );
        } catch ( final ProviderException e ) {
            LOG.warn( "HubOverviewService pageExists failed for '{}': {}", name, e.getMessage() );
            return false;
        }
    }

    /**
     * Builds the {@code hub → members} index from both the KG and page
     * frontmatter. KG {@code related} edges are the authoritative source when
     * present; pages declaring {@code type: hub} in frontmatter contribute
     * their {@code related:} list so fresh hubs appear before the entity
     * extractor has run.
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
        final Map< String, List< String > > frontmatterHubs = loadFrontmatterHubs();
        hubNames.addAll( frontmatterHubs.keySet() );

        final Map< String, Set< String > > out = new LinkedHashMap<>();
        for ( final String hub : hubNames ) out.put( hub, new HashSet<>() );

        final List< Map< String, Object > > edges =
            kgRepo.queryEdgesWithNames( "related", null, 100_000, 0 );
        for ( final Map< String, Object > edge : edges ) {
            final String src = (String) edge.get( "source_name" );
            final String tgt = (String) edge.get( "target_name" );
            if ( src == null || tgt == null ) continue;
            if ( !hubNames.contains( src ) ) continue;
            out.get( src ).add( tgt );
        }
        for ( final Map.Entry< String, List< String > > e : frontmatterHubs.entrySet() ) {
            out.get( e.getKey() ).addAll( e.getValue() );
        }
        return out;
    }

    private static List< float[] > vectorsFor( final Map< String, float[] > vectors,
                                                final Set< String > names ) {
        final List< float[] > out = new ArrayList<>();
        for ( final String name : names ) {
            final float[] v = vectors.get( name );
            if ( v != null ) out.add( v );
        }
        return out;
    }

    private static double cosine( final float[] a, final float[] b ) {
        // Mention centroids are unit-normalized when produced by NodeMentionSimilarity,
        // so dot product == cosine without an explicit renormalization step.
        if ( a.length != b.length ) return 0.0;
        double dot = 0;
        for ( int i = 0; i < a.length; i++ ) dot += a[ i ] * b[ i ];
        return dot;
    }

    /** See spec section "loadDrilldown algorithm". Returns null if hub not found. */
    public HubDrilldown loadDrilldown( final String hubName ) {
        if ( hubName == null || hubName.isBlank() ) return null;
        final long start = System.currentTimeMillis();

        if ( !hubNodeExists( hubName ) ) return null;

        final Map< String, float[] > vectors = similarity.allCentroids();
        final Map< String, Set< String > > allHubMembers = loadAllHubMembers();
        final Set< String > rawMembers = allHubMembers.getOrDefault( hubName, Set.of() );

        final MemberPartition partition = partitionMembers( rawMembers );
        final HubShape shape = hubShapeOf( vectors, rawMembers );
        final List< MemberDetail > memberDetails = buildMemberDetails( vectors, shape.centroid(), partition.existing() );

        final List< NearMissTfidf > nearMissList = computeNearMissTfidf( vectors, shape.centroid(), allHubMembers.keySet(), rawMembers );
        final List< OverlapHub >    overlapHubs  = computeOverlapHubs ( vectors, shape.centroid(), allHubMembers, hubName, rawMembers );
        final List< MoreLikeThisLucene > mltList = fetchMoreLikeThis  ( hubName, rawMembers, memberDetails );

        final boolean hasBackingPage = pageExists( hubName );
        LOG.info( "Hub overview drilldown: hub='{}' members={} nearMiss={} overlap={} {}ms",
            hubName, memberDetails.size(), nearMissList.size(), overlapHubs.size(),
            System.currentTimeMillis() - start );
        return new HubDrilldown(
            hubName, hasBackingPage, shape.coherence(),
            memberDetails, partition.stubs(), nearMissList, mltList, overlapHubs );
    }

    /**
     * Step 1: is {@code hubName} either a KG node of type=hub or a wiki page
     * whose frontmatter declares {@code type: hub}? The KG filter uses
     * substring LIKE matching on name, so we still match exactly in Java.
     */
    private boolean hubNodeExists( final String hubName ) {
        final List< com.wikantik.api.knowledge.KgNode > hubNodes =
            kgRepo.queryNodes( Map.of( "node_type", "hub" ), null, 100_000, 0 );
        for ( final var n : hubNodes ) {
            if ( hubName.equals( n.name() ) ) return true;
        }
        return loadFrontmatterHubs().containsKey( hubName );
    }

    /** Step 3: sort members alphabetically and split existing pages from stubs. */
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

    /** Step 4: centroid + coherence for an explicit member set (used by drilldown). */
    private HubShape hubShapeOf( final Map< String, float[] > vectors, final Set< String > members ) {
        if ( vectors.isEmpty() || members.size() < 2 ) return HubShape.empty();
        final List< float[] > vecs = vectorsFor( vectors, members );
        if ( vecs.size() < 2 ) return HubShape.empty();
        final float[][] arr = vecs.toArray( new float[ 0 ][] );
        final float[] centroid = HubDiscoveryService.normalizedCentroid( arr );
        return new HubShape( centroid, HubDiscoveryService.meanDot( arr, centroid ) );
    }

    /** Step 5: per-member cosine to centroid, NaN when vector or centroid is missing, sorted ascending. */
    private List< MemberDetail > buildMemberDetails(
            final Map< String, float[] > vectors, final float[] centroid, final List< String > existing ) {
        final List< MemberDetail > out = new ArrayList<>();
        for ( final String m : existing ) {
            out.add( new MemberDetail( m, memberCosine( vectors, centroid, m ), true ) );
        }
        out.sort( HubOverviewService::compareByCosineAsc );
        return out;
    }

    private static double memberCosine( final Map< String, float[] > vectors, final float[] centroid, final String name ) {
        if ( centroid == null ) return Double.NaN;
        final float[] vec = vectors.get( name );
        return vec != null ? cosine( vec, centroid ) : Double.NaN;
    }

    private static int compareByCosineAsc( final MemberDetail a, final MemberDetail b ) {
        final boolean an = Double.isNaN( a.cosineToCentroid() );
        final boolean bn = Double.isNaN( b.cosineToCentroid() );
        if ( an && bn ) return a.name().compareTo( b.name() );
        if ( an ) return 1;
        if ( bn ) return -1;
        return Double.compare( a.cosineToCentroid(), b.cosineToCentroid() );
    }

    /** Step 6: top-N non-member non-hub entities with cosine ≥ nearMissThreshold, descending. */
    private List< NearMissTfidf > computeNearMissTfidf(
            final Map< String, float[] > vectors, final float[] centroid,
            final Set< String > allHubNames, final Set< String > rawMembers ) {
        if ( centroid == null ) return List.of();
        final List< NearMissTfidf > scored = new ArrayList<>();
        for ( final Map.Entry< String, float[] > entry : vectors.entrySet() ) {
            final String entityName = entry.getKey();
            if ( allHubNames.contains( entityName ) ) continue;
            if ( rawMembers.contains( entityName ) ) continue;
            final double cos = cosine( entry.getValue(), centroid );
            if ( cos >= nearMissThreshold ) {
                scored.add( new NearMissTfidf( entityName, cos ) );
            }
        }
        scored.sort( ( a, b ) -> Double.compare( b.cosineToCentroid(), a.cosineToCentroid() ) );
        return scored.subList( 0, Math.min( scored.size(), nearMissMaxResults ) );
    }

    /** Step 7: other hubs whose centroid cosine with this one is ≥ overlapThreshold. */
    private List< OverlapHub > computeOverlapHubs(
            final Map< String, float[] > vectors, final float[] centroid,
            final Map< String, Set< String > > allHubMembers,
            final String hubName, final Set< String > rawMembers ) {
        if ( centroid == null ) return List.of();
        final List< OverlapHub > overlapHubs = new ArrayList<>();
        for ( final var entry : allHubMembers.entrySet() ) {
            final String otherHub = entry.getKey();
            if ( otherHub.equals( hubName ) ) continue;
            final List< float[] > otherVecs = vectorsFor( vectors, entry.getValue() );
            if ( otherVecs.size() < 2 ) continue;
            final float[][] arr = otherVecs.toArray( new float[ 0 ][] );
            final float[] otherCentroid = HubDiscoveryService.normalizedCentroid( arr );
            final double cos = cosine( centroid, otherCentroid );
            if ( cos < overlapThreshold ) continue;
            final Set< String > shared = new HashSet<>( rawMembers );
            shared.retainAll( entry.getValue() );
            overlapHubs.add( new OverlapHub( otherHub, cos, shared.size() ) );
        }
        overlapHubs.sort( ( a, b ) -> Double.compare( b.centroidCosine(), a.centroidCosine() ) );
        return overlapHubs;
    }

    /**
     * Step 8: Lucene MoreLikeThis with empty fallback on any failure.
     * Seed = hub itself if it has a backing page; otherwise the highest-cosine member
     * ({@code memberDetails} is sorted ascending by cosine, so the last element wins).
     */
    private List< MoreLikeThisLucene > fetchMoreLikeThis(
            final String hubName, final Set< String > rawMembers, final List< MemberDetail > memberDetails ) {
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

    private String pickMoreLikeThisSeed( final String hubName, final List< MemberDetail > memberDetails ) {
        if ( pageExists( hubName ) ) return hubName;
        if ( memberDetails.isEmpty() ) return null;
        return memberDetails.get( memberDetails.size() - 1 ).name();
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
        private NodeMentionSimilarity      similarity;
        private PageManager                pageManager;
        private HubDiscoveryService.PageWriter pageWriter;
        private LuceneMlt                  luceneMlt;
        private Double                     nearMissThreshold;
        private Double                     overlapThreshold;
        private Integer                    nearMissMaxResults;
        private Integer                    mltMaxResults;

        private Builder() {}

        public Builder kgRepo( final JdbcKnowledgeRepository v ) { this.kgRepo = v; return this; }
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
            if ( kgRepo == null || similarity == null || pageManager == null ) {
                throw new IllegalStateException(
                    "HubOverviewService.Builder: kgRepo, similarity, and pageManager are required" );
            }
            return new HubOverviewService( this );
        }
    }
}
