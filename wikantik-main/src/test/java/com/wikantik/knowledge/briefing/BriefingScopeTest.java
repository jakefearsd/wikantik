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
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.knowledge.briefing.BriefingTestFixtures.StubIndex;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wikantik.knowledge.briefing.BriefingTestFixtures.cluster;
import static com.wikantik.knowledge.briefing.BriefingTestFixtures.corpusEdges;
import static com.wikantik.knowledge.briefing.BriefingTestFixtures.corpusIndex;
import static com.wikantik.knowledge.briefing.BriefingTestFixtures.desc;
import static com.wikantik.knowledge.briefing.BriefingTestFixtures.refs;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class BriefingScopeTest {

    private final StubIndex index = corpusIndex();
    private final ReferenceManager refs = refs( corpusEdges() );
    private final List< String > warnings = new ArrayList<>();
    private final Set< String > warned = new HashSet<>();

    private BriefingScope compute( final List< String > clusters, final int citedMax ) {
        return BriefingScope.compute( clusters, index, refs, true, citedMax, warnings, warned );
    }

    private BriefingScope compute( final String... clusters ) {
        return compute( List.of( clusters ), 8 );
    }

    private static List< String > slugs( final List< PageDescriptor > pages ) {
        return pages.stream().map( PageDescriptor::slug ).toList();
    }

    private static List< String > citedSlugs( final BriefingScope scope ) {
        return scope.cited().stream().map( c -> c.page().slug() ).toList();
    }

    // ------------------------------------------------------------ ordering

    @Test
    void rollerMembersOrderedHubThenInDegreeThenUpdated() {
        final BriefingScope scope = compute( "roller" );

        // RollerTelemetrySpec is the newest page, so recency alone would put it before RollerMcp;
        // RollerMcp wins on in-degree (2 members link to it, against 1).
        assertEquals( List.of( "RollerHub", "RollerMcp", "RollerTelemetrySpec" ), slugs( scope.members() ) );
        assertEquals( 1, scope.tierOf( "RollerMcp" ) );
        assertFalse( scope.isNone() );
        assertTrue( warnings.isEmpty(), "a clean scope warns about nothing: " + warnings );
    }

    @Test
    void rollerCitedOrderedByCitersThenReach() {
        final BriefingScope scope = compute( "roller" );

        assertEquals( List.of( "TelemetryContract", "StackHub" ), citedSlugs( scope ) );
        assertEquals( 2, scope.cited().get( 0 ).citers() );
        assertEquals( 3, scope.cited().get( 0 ).reach() );
        assertEquals( 1, scope.cited().get( 1 ).citers() );
        assertEquals( 3, scope.cited().get( 1 ).reach() );
        assertEquals( 2, scope.tierOf( "TelemetryContract" ) );
        assertEquals( 2, scope.tierOf( "StackHub" ) );
    }

    @Test
    void equalCitersAreOrderedByReach() {
        // Both cited pages are cited by exactly one member; "Wide" is referred to from two clusters.
        final StubIndex idx = new StubIndex();
        final PageDescriptor hub = desc( "Hub", List.of( "proj" ), "h", null );
        final PageDescriptor narrow = desc( "Narrow", List.of( "other" ), "n", null );
        final PageDescriptor wide = desc( "Wide", List.of( "other" ), "w", null );
        final PageDescriptor outsider = desc( "Outsider", List.of( "third" ), "o", null );
        for ( final PageDescriptor d : List.of( hub, narrow, wide, outsider ) ) idx.register( d );
        idx.clusters.put( "proj", cluster( "proj", hub, List.of() ) );
        final Map< String, List< String > > edges = new LinkedHashMap<>();
        edges.put( "Hub", List.of( "Narrow", "Wide" ) );
        edges.put( "Outsider", List.of( "Wide" ) );

        final BriefingScope scope = BriefingScope.compute( List.of( "proj" ), idx, refs( edges ), true, 8,
            warnings, warned );

        assertEquals( List.of( "Wide", "Narrow" ), citedSlugs( scope ) );
        assertEquals( 3, scope.cited().get( 0 ).reach(), "proj + third + its own 'other'" );
        assertEquals( 2, scope.cited().get( 1 ).reach(), "proj + its own 'other'" );
    }

    @Test
    void equalRankIsOrderedByUpdatedNewestFirstNullsLast() {
        final StubIndex idx = new StubIndex();
        final Instant t = Instant.parse( "2026-10-01T00:00:00Z" );
        final PageDescriptor hub = desc( "Hub", List.of( "proj" ), "h", t );
        final PageDescriptor never = desc( "Never", List.of( "proj" ), "s", null );
        final PageDescriptor old = desc( "Old", List.of( "proj" ), "s", t );
        final PageDescriptor fresh = desc( "Fresh", List.of( "proj" ), "s", t.plusSeconds( 60 ) );
        for ( final PageDescriptor d : List.of( hub, never, old, fresh ) ) idx.register( d );
        idx.clusters.put( "proj", cluster( "proj", hub, List.of( never, old, fresh ) ) );

        final BriefingScope scope = BriefingScope.compute( List.of( "proj" ), idx, refs( Map.of() ), true, 8,
            warnings, warned );

        assertEquals( List.of( "Hub", "Fresh", "Old", "Never" ), slugs( scope.members() ) );
    }

    @Test
    void memberWithoutSummaryOrInboundSortsLast() {
        final StubIndex idx = new StubIndex();
        final Instant t = Instant.parse( "2026-10-01T00:00:00Z" );
        final PageDescriptor hub = desc( "Hub", List.of( "proj" ), "h", t );
        // Bare is the newest page but has neither a summary nor an inbound link.
        final PageDescriptor bare = desc( "Bare", List.of( "proj" ), null, t.plusSeconds( 600 ) );
        final PageDescriptor summarised = desc( "Summarised", List.of( "proj" ), "s", t );
        final PageDescriptor linked = desc( "Linked", List.of( "proj" ), null, t );
        for ( final PageDescriptor d : List.of( hub, bare, summarised, linked ) ) idx.register( d );
        idx.clusters.put( "proj", cluster( "proj", hub, List.of( bare, summarised, linked ) ) );
        final Map< String, List< String > > edges = new LinkedHashMap<>();
        edges.put( "Summarised", List.of( "Linked" ) );

        final BriefingScope scope = BriefingScope.compute( List.of( "proj" ), idx, refs( edges ), true, 8,
            warnings, warned );

        // in-degree beats summary, summary beats recency
        assertEquals( List.of( "Hub", "Linked", "Summarised", "Bare" ), slugs( scope.members() ) );
    }

    // ------------------------------------------------------------- tiering

    @Test
    void stockroomPhase7NeverEntersRollerScope() {
        final BriefingScope scope = compute( "roller" );

        assertEquals( 0, scope.tierOf( "Phase7Plan" ) );
        assertEquals( 0, scope.tierOf( "StockroomHub" ), "no Roller member links to it" );
        assertEquals( 0, scope.tierOf( "NotARealPage" ) );
    }

    @Test
    void simpleAgilityCitesProjectHubsNotPlans() {
        final BriefingScope scope = compute( "simple-agility" );

        assertEquals( Set.of( "RollerHub", "StockroomHub" ), Set.copyOf( citedSlugs( scope ) ) );
        assertEquals( "StockroomHub", scope.cited().get( 0 ).page().slug(), "two citers beat one" );
        assertEquals( 0, scope.tierOf( "Phase7Plan" ) );
    }

    @Test
    void memberOfOneRequestedClusterIsNeverCited() {
        final BriefingScope scope = compute( "roller", "simple-agility" );

        assertEquals( 1, scope.tierOf( "TelemetryContract" ) );
        assertEquals( 1, scope.tierOf( "StackHub" ) );
        assertFalse( citedSlugs( scope ).contains( "TelemetryContract" ) );
        assertFalse( citedSlugs( scope ).contains( "StackHub" ) );
        assertEquals( List.of( "RollerHub", "StackHub" ), slugs( scope.members() ).subList( 0, 2 ),
            "hubs of every requested cluster come first, in request order" );
    }

    // ---------------------------------------------------------- robustness

    @Test
    void nullReferrersTreatedAsEmpty() {
        when( refs.findReferrers( "RollerMcp" ) ).thenReturn( null );

        final BriefingScope[] scope = new BriefingScope[ 1 ];
        assertDoesNotThrow( () -> scope[ 0 ] = compute( "roller" ) );

        // A null referrer set counts as in-degree 0, so RollerMcp (was 2) falls below the Spec (1).
        assertEquals( List.of( "RollerHub", "RollerTelemetrySpec", "RollerMcp" ), slugs( scope[ 0 ].members() ) );
    }

    @Test
    void nullOutboundLinksTreatedAsEmpty() {
        when( refs.findRefersTo( "RollerHub" ) ).thenReturn( null );

        final BriefingScope scope = compute( "roller" );

        assertEquals( List.of( "TelemetryContract" ), citedSlugs( scope ), "only the Spec's link remains" );
        assertEquals( 1, scope.cited().get( 0 ).citers() );
    }

    @Test
    void citedPageWithoutDescriptorIsSkipped() {
        final Map< String, List< String > > edges = corpusEdges();
        edges.put( "RollerMcp", List.of( "RollerHub", "SandBox" ) );
        final ReferenceManager withSandbox = refs( edges );

        final BriefingScope scope = BriefingScope.compute( List.of( "roller" ), index, withSandbox, true, 8,
            warnings, warned );

        assertFalse( citedSlugs( scope ).contains( "SandBox" ) );
        assertEquals( 0, scope.tierOf( "SandBox" ) );
        assertEquals( List.of( "TelemetryContract", "StackHub" ), citedSlugs( scope ) );
        assertTrue( warnings.isEmpty(), "an unresolvable cited page is skipped silently: " + warnings );
    }

    @Test
    void citedCapKeepsTopByCiters() {
        final BriefingScope scope = compute( List.of( "roller" ), 1 );

        assertEquals( List.of( "TelemetryContract" ), citedSlugs( scope ) );
        assertEquals( 0, scope.tierOf( "StackHub" ), "a page cut by the cap is out of scope" );
    }

    @Test
    void citedDisabledYieldsNoCitedAndNoWarning() {
        final BriefingScope scope = BriefingScope.compute( List.of( "roller" ), index, null, false, 8,
            warnings, warned );

        assertTrue( scope.cited().isEmpty() );
        assertEquals( 3, scope.members().size() );
        assertTrue( warnings.isEmpty(), "disabled cited tier does not warn: " + warnings );
    }

    @Test
    void nullReferenceManagerWarnsAndYieldsNoCited() {
        final BriefingScope scope = BriefingScope.compute( List.of( "roller" ), index, null, true, 8,
            warnings, warned );

        assertTrue( scope.cited().isEmpty() );
        assertEquals( List.of( "reference data unavailable; cited pages skipped" ), warnings );
        assertEquals( 3, scope.members().size(), "members still resolve without link data" );
        assertEquals( "RollerHub", scope.members().get( 0 ).slug() );
    }

    @Test
    void unknownClusterWarnsOnceAndOthersStillResolve() {
        final BriefingScope first = compute( "nope", "roller" );
        final BriefingScope second = compute( "nope", "roller" );

        assertEquals( List.of( "unknown cluster: nope" ), warnings, "warned once across both computes" );
        assertEquals( 3, first.members().size() );
        assertEquals( 3, second.members().size() );
    }

    @Test
    void onlyUnknownClustersYieldAnEmptyScopeThatIsNotNone() {
        final BriefingScope unknown = compute( "nope" );
        final BriefingScope known = compute( "roller" );

        assertTrue( unknown.isEmpty(), "no member and no cited page" );
        assertFalse( unknown.isNone(), "scoping was requested, it just matched nothing" );
        assertFalse( known.isEmpty() );
        assertTrue( BriefingScope.NONE.isEmpty() );
    }

    @Test
    void membersCappedPerClusterWithWarning() {
        final StubIndex idx = new StubIndex();
        final List< PageDescriptor > articles = new ArrayList<>();
        for ( int i = 0; i < 201; i++ ) {
            final PageDescriptor d = desc( String.format( "Mem%03d", i ), List.of( "big" ), "s", Instant.now() );
            idx.register( d );
            articles.add( d );
        }
        idx.clusters.put( "big", cluster( "big", null, articles ) );

        final BriefingScope scope = BriefingScope.compute( List.of( "big" ), idx, refs( Map.of() ), true, 8,
            warnings, warned );

        assertEquals( BriefingConfig.MAX_CLUSTER_MEMBERS, scope.members().size() );
        assertTrue( warnings.contains( "too many members in cluster big; first 200 included" ), warnings.toString() );
    }

    @Test
    void cappedOutMemberIsNeverCited() {
        // 210 articles plus a hub: the per-cluster cap keeps the hub and the 199 newest articles, so the
        // oldest (Mem000) is capped out of members(). The hub links to it, which must not make it a
        // "contract this project cites": it is still a member of the cluster, just not listed.
        final StubIndex idx = new StubIndex();
        final Instant base = Instant.parse( "2026-10-01T00:00:00Z" );
        final PageDescriptor hub = desc( "BigHub", List.of( "big" ), "Hub.", base );
        idx.register( hub );
        final List< PageDescriptor > articles = new ArrayList<>();
        for ( int i = 0; i < 210; i++ ) {
            final PageDescriptor d = desc( String.format( "Mem%03d", i ), List.of( "big" ), "s", base.plusSeconds( i ) );
            idx.register( d );
            articles.add( d );
        }
        idx.clusters.put( "big", cluster( "big", hub, articles ) );

        final BriefingScope scope = BriefingScope.compute( List.of( "big" ), idx,
            refs( Map.of( "BigHub", List.of( "Mem000" ) ) ), true, 8, warnings, warned );

        assertEquals( BriefingConfig.MAX_CLUSTER_MEMBERS, scope.members().size() );
        assertFalse( slugs( scope.members() ).contains( "Mem000" ), "the cap still bounds the ordered member list" );
        assertFalse( citedSlugs( scope ).contains( "Mem000" ), "a capped-out member is not a cited page: " + citedSlugs( scope ) );
        assertEquals( 1, scope.tierOf( "Mem000" ), "its retrieved sections stay tier 1, never demoted to out of scope" );
        assertEquals( 1, scope.tierOf( "Mem209" ) );
        assertEquals( 0, scope.tierOf( "SomewhereElse" ) );
    }

    // ----------------------------------------------------------------- none

    @Test
    void noClustersReturnsNone() {
        final BriefingScope scope = compute( List.of(), 8 );

        assertTrue( scope.isNone() );
        assertEquals( 0, scope.tierOf( "RollerHub" ) );
        assertTrue( scope.members().isEmpty() );
        assertTrue( scope.cited().isEmpty() );
        assertTrue( warnings.isEmpty() );
    }

    @Test
    void nullIndexWithClustersReturnsNoneAndWarns() {
        final BriefingScope scope = BriefingScope.compute( List.of( "roller" ), null, refs, true, 8,
            warnings, warned );

        assertTrue( scope.isNone() );
        assertEquals( List.of( "structural index unavailable; clusters skipped" ), warnings );
        assertEquals( 0, scope.tierOf( "RollerHub" ) );
    }
}
