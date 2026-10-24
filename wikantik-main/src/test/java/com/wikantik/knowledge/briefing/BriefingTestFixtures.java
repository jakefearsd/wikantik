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
package com.wikantik.knowledge.briefing;

import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.ClusterSummary;
import com.wikantik.api.pagegraph.IndexHealth;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.Sitemap;
import com.wikantik.api.pagegraph.StructuralConflict;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.TagSummary;
import com.wikantik.api.pagegraph.Verification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared test doubles for the briefing tests: a {@link StructuralIndexService} stub, page
 * descriptor / cluster builders, a {@link ReferenceManager} mock built from an edge map, and the
 * eight-page "project context separation" corpus.
 */
final class BriefingTestFixtures {

    private BriefingTestFixtures() {
    }

    static PageDescriptor desc( final String slug, final Instant updated ) {
        return new PageDescriptor( "01" + slug, slug, slug, null, "billing",
            List.of(), null, updated, Optional.empty(), false );
    }

    /** A descriptor with an explicit cluster membership list and summary. */
    static PageDescriptor desc( final String slug, final List< String > clusters, final String summary,
                                final Instant updated ) {
        return new PageDescriptor( "01" + slug, slug, slug, null, null, clusters,
            List.of(), summary, updated, Optional.empty(), false );
    }

    static ClusterDetails cluster( final String name, final PageDescriptor hub,
                                   final List< PageDescriptor > articles ) {
        return new ClusterDetails( name, hub, articles, Map.of(), Instant.now() );
    }

    /**
     * A {@link ReferenceManager} mock whose {@code findRefersTo} returns the given outbound edges
     * and whose {@code findReferrers} returns the exact inverse, so the two can never disagree.
     * Pages that appear in neither role get Mockito's empty defaults.
     */
    static ReferenceManager refs( final Map< String, List< String > > outbound ) {
        final ReferenceManager rm = mock( ReferenceManager.class );
        final Map< String, Set< String > > inbound = new LinkedHashMap<>();
        outbound.forEach( ( from, targets ) -> {
            when( rm.findRefersTo( from ) ).thenReturn( new ArrayList<>( targets ) );
            targets.forEach( t -> inbound.computeIfAbsent( t, k -> new LinkedHashSet<>() ).add( from ) );
        } );
        inbound.forEach( ( to, from ) -> when( rm.findReferrers( to ) ).thenReturn( new LinkedHashSet<>( from ) ) );
        return rm;
    }

    /**
     * Eight pages in three clusters. Outbound edges (body wikilinks and {@code related:} alike):
     * <pre>
     * StackHub            -> RollerHub, StockroomHub, TelemetryContract, CommerceDataFlow
     * TelemetryContract   -> StackHub
     * CommerceDataFlow    -> TelemetryContract, StockroomHub
     * RollerHub           -> RollerMcp, RollerTelemetrySpec, StackHub, TelemetryContract
     * RollerMcp           -> RollerHub
     * RollerTelemetrySpec -> RollerMcp, TelemetryContract
     * StockroomHub        -> Phase7Plan, StackHub, TelemetryContract, CommerceDataFlow, RollerHub
     * Phase7Plan          -> StockroomHub, TelemetryContract
     * </pre>
     * Hubs: {@code StackHub} (simple-agility), {@code RollerHub} (roller), {@code StockroomHub}
     * (stockroom). {@code RollerTelemetrySpec} is deliberately the most recently updated Roller
     * page so a purely recency-ordered scope would rank it above {@code RollerMcp}.
     */
    static Map< String, List< String > > corpusEdges() {
        final Map< String, List< String > > e = new LinkedHashMap<>();
        e.put( "StackHub", List.of( "RollerHub", "StockroomHub", "TelemetryContract", "CommerceDataFlow" ) );
        e.put( "TelemetryContract", List.of( "StackHub" ) );
        e.put( "CommerceDataFlow", List.of( "TelemetryContract", "StockroomHub" ) );
        e.put( "RollerHub", List.of( "RollerMcp", "RollerTelemetrySpec", "StackHub", "TelemetryContract" ) );
        e.put( "RollerMcp", List.of( "RollerHub" ) );
        e.put( "RollerTelemetrySpec", List.of( "RollerMcp", "TelemetryContract" ) );
        e.put( "StockroomHub", List.of( "Phase7Plan", "StackHub", "TelemetryContract", "CommerceDataFlow", "RollerHub" ) );
        e.put( "Phase7Plan", List.of( "StockroomHub", "TelemetryContract" ) );
        return e;
    }

    /** The eight-page corpus (see {@link #corpusEdges()}) registered in a fresh {@link StubIndex}. */
    static StubIndex corpusIndex() {
        final Instant base = Instant.parse( "2026-10-01T00:00:00Z" );
        final StubIndex idx = new StubIndex();
        final PageDescriptor stackHub = desc( "StackHub", List.of( "simple-agility" ), "Stack hub.", base );
        final PageDescriptor contract = desc( "TelemetryContract", List.of( "simple-agility" ), "Contract.", base );
        final PageDescriptor dataFlow = desc( "CommerceDataFlow", List.of( "simple-agility" ), "Data flow.", base );
        final PageDescriptor rollerHub = desc( "RollerHub", List.of( "roller" ), "Roller hub.", base );
        final PageDescriptor rollerMcp = desc( "RollerMcp", List.of( "roller" ), "Roller MCP.", base.plusSeconds( 10 ) );
        final PageDescriptor rollerSpec = desc( "RollerTelemetrySpec", List.of( "roller" ), "Roller spec.", base.plusSeconds( 20 ) );
        final PageDescriptor stockroomHub = desc( "StockroomHub", List.of( "stockroom" ), "Stockroom hub.", base );
        final PageDescriptor phase7 = desc( "Phase7Plan", List.of( "stockroom" ), "Phase 7 plan.", base.plusSeconds( 30 ) );
        for ( final PageDescriptor d : List.of( stackHub, contract, dataFlow, rollerHub, rollerMcp, rollerSpec,
                                                stockroomHub, phase7 ) ) {
            idx.register( d );
        }
        idx.clusters.put( "simple-agility", cluster( "simple-agility", stackHub, List.of( contract, dataFlow ) ) );
        idx.clusters.put( "roller", cluster( "roller", rollerHub, List.of( rollerMcp, rollerSpec ) ) );
        idx.clusters.put( "stockroom", cluster( "stockroom", stockroomHub, List.of( phase7 ) ) );
        return idx;
    }

    /** Configurable StructuralIndexService double; only the lookups the briefing code uses are wired. */
    static final class StubIndex implements StructuralIndexService {
        final Map< String, ClusterDetails > clusters = new HashMap<>();
        final Map< String, String > slugFromCanonical = new HashMap<>();
        final Map< String, String > canonicalFromSlug = new HashMap<>();
        final Map< String, PageDescriptor > byCanonical = new HashMap<>();

        /** Make the descriptor resolvable by slug and by canonical id. */
        void register( final PageDescriptor d ) {
            slugFromCanonical.put( d.canonicalId(), d.slug() );
            canonicalFromSlug.put( d.slug(), d.canonicalId() );
            byCanonical.put( d.canonicalId(), d );
        }

        @Override public Optional< ClusterDetails > getCluster( final String name ) {
            return Optional.ofNullable( clusters.get( name ) );
        }
        @Override public Optional< String > resolveSlugFromCanonicalId( final String canonicalId ) {
            return Optional.ofNullable( slugFromCanonical.get( canonicalId ) );
        }
        @Override public Optional< String > resolveCanonicalIdFromSlug( final String slug ) {
            return Optional.ofNullable( canonicalFromSlug.get( slug ) );
        }
        @Override public Optional< PageDescriptor > getByCanonicalId( final String canonicalId ) {
            return Optional.ofNullable( byCanonical.get( canonicalId ) );
        }
        @Override public List< ClusterSummary > listClusters() { throw new UnsupportedOperationException(); }
        @Override public List< TagSummary > listTags( final int minPages ) { throw new UnsupportedOperationException(); }
        @Override public List< PageDescriptor > listPagesByType( final PageType type ) { throw new UnsupportedOperationException(); }
        @Override public List< PageDescriptor > listPagesByFilter( final StructuralFilter filter ) { throw new UnsupportedOperationException(); }
        @Override public Sitemap sitemap() { throw new UnsupportedOperationException(); }
        @Override public void rebuild() { throw new UnsupportedOperationException(); }
        @Override public IndexHealth health() { throw new UnsupportedOperationException(); }
        @Override public List< StructuralConflict > conflicts() { throw new UnsupportedOperationException(); }
        @Override public Optional< Verification > verificationOf( final String canonicalId ) { throw new UnsupportedOperationException(); }
        @Override public StructuralProjectionSnapshot snapshot() { throw new UnsupportedOperationException(); }
    }
}
