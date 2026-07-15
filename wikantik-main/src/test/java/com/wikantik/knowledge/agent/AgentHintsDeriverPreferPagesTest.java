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
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.AgentHintsBlock;
import com.wikantik.api.agent.PreferredPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class AgentHintsDeriverPreferPagesTest {

    private StructuralIndexService index;
    private PageManager pageManager;
    private ReferenceManager refs;
    private AgentHintsDeriver deriver;

    @BeforeEach
    void setUp() {
        index       = mock( StructuralIndexService.class );
        pageManager = mock( PageManager.class );
        refs        = mock( ReferenceManager.class );
        deriver     = new AgentHintsDeriver( index, pageManager, refs );
    }

    private PageDescriptor pd( final String id, final String slug, final String cluster ) {
        return new PageDescriptor( id, slug, slug, PageType.UNKNOWN, cluster,
                List.of(), null, Instant.parse( "2026-05-10T00:00:00Z" ), Optional.empty(), false );
    }

    @Test
    void hubAppearsFirstWithRoleClusterHub() {
        final PageDescriptor self = pd( "page_a",  "PageA",  "cluster_x" );
        final PageDescriptor hub  = pd( "hub_x",   "HubX",   "cluster_x" );
        final PageDescriptor mate = pd( "page_b",  "PageB",  "cluster_x" );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( index.getCluster( "cluster_x" ) ).thenReturn( Optional.of(
                new ClusterDetails( "cluster_x", hub, List.of( self, mate, hub ),
                                    Map.of(), Instant.parse( "2026-05-10T00:00:00Z" ) ) ) );
        when( index.verificationOf( any() ) ).thenReturn( Optional.empty() );
        when( pageManager.getPureText( any(), anyInt() ) ).thenReturn( "" );
        when( refs.findReferrers( any() ) ).thenReturn( Set.of() );

        final AgentHintsBlock out = deriver.derive( "page_a" );

        assertFalse( out.prefer_pages().isEmpty() );
        assertEquals( "hub_x", out.prefer_pages().get( 0 ).canonical_id() );
        assertEquals( "cluster_hub", out.prefer_pages().get( 0 ).role() );
    }

    @Test
    void selfIsExcludedFromPreferPages() {
        final PageDescriptor self = pd( "page_a",  "PageA",  "cluster_x" );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( index.getCluster( "cluster_x" ) ).thenReturn( Optional.of(
                new ClusterDetails( "cluster_x", self, List.of( self ),
                                    Map.of(), Instant.parse( "2026-05-10T00:00:00Z" ) ) ) );
        when( index.verificationOf( any() ) ).thenReturn( Optional.empty() );
        when( pageManager.getPureText( any(), anyInt() ) ).thenReturn( "" );
        when( refs.findReferrers( any() ) ).thenReturn( Set.of() );

        final AgentHintsBlock out = deriver.derive( "page_a" );

        // Self is the hub here; expect no entry for self.
        assertTrue( out.prefer_pages().stream().noneMatch( p -> p.canonical_id().equals( "page_a" ) ) );
    }

    @Test
    void scoresByIntraClusterInboundLinksWithVerifiedBonus() {
        final PageDescriptor self = pd( "page_a", "PageA", "cluster_x" );
        final PageDescriptor mateHigh = pd( "high", "High", "cluster_x" );
        final PageDescriptor mateLow  = pd( "low",  "Low",  "cluster_x" );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( index.getCluster( "cluster_x" ) ).thenReturn( Optional.of(
                new ClusterDetails( "cluster_x", null, List.of( self, mateHigh, mateLow ),
                                    Map.of(), Instant.parse( "2026-05-10T00:00:00Z" ) ) ) );
        // Both High and Low have ONE same-cluster referrer each (each other).
        // Out-of-cluster referrers are filtered out, so:
        //   High intra-cluster inbound = 1 (from Low)
        //   Low  intra-cluster inbound = 1 (from High)
        // High is verified authoritative → score 1 * 1.5 = 1.5
        // Low is unverified              → score 1 * 1.0 = 1.0
        // High wins.
        when( refs.findReferrers( "High" ) ).thenReturn( Set.of( "PageA", "Low" ) );
        when( refs.findReferrers( "Low"  ) ).thenReturn( Set.of( "PageA", "High", "Other1", "Other2", "Other3" ) );
        when( index.verificationOf( "high" ) ).thenReturn( Optional.of(
                new Verification( Instant.now(), "tester", Confidence.AUTHORITATIVE, Audience.AGENTS ) ) );
        when( index.verificationOf( "low" ) ).thenReturn( Optional.empty() );
        when( pageManager.getPureText( any(), anyInt() ) ).thenReturn( "" );

        final AgentHintsBlock out = deriver.derive( "page_a" );

        assertEquals( "high", out.prefer_pages().get( 0 ).canonical_id() );
        assertEquals( "authoritative_reference", out.prefer_pages().get( 0 ).role() );
    }

    @Test
    void noClusterYieldsEmptyPreferPages() {
        final PageDescriptor self = pd( "page_a", "PageA", null );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( pageManager.getPureText( any(), anyInt() ) ).thenReturn( "" );

        final AgentHintsBlock out = deriver.derive( "page_a" );
        assertTrue( out.prefer_pages().isEmpty() );
    }

    @Test
    void capsAtFiveTotal() {
        final PageDescriptor self = pd( "page_a", "PageA", "cluster_x" );
        final PageDescriptor hub  = pd( "hub_x",  "HubX",  "cluster_x" );
        final List< PageDescriptor > many = new java.util.ArrayList<>();
        many.add( self ); many.add( hub );
        for ( int i = 0; i < 10; i++ ) many.add( pd( "p" + i, "P" + i, "cluster_x" ) );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( index.getCluster( "cluster_x" ) ).thenReturn( Optional.of(
                new ClusterDetails( "cluster_x", hub, many, Map.of(),
                                    Instant.parse( "2026-05-10T00:00:00Z" ) ) ) );
        when( index.verificationOf( any() ) ).thenReturn( Optional.empty() );
        when( pageManager.getPureText( any(), anyInt() ) ).thenReturn( "" );
        when( refs.findReferrers( any() ) ).thenReturn( Set.of() );

        final AgentHintsBlock out = deriver.derive( "page_a" );
        assertEquals( 5, out.prefer_pages().size() );
    }
}
