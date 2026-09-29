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
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.test.StubPageManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@RequiresPostgres
class HubMemberLoaderTest {

    private static DataSource dataSource;
    private KgNodeRepository nodes;
    private KgEdgeRepository edges;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestDb.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        nodes = new KgNodeRepository( dataSource );
        edges = new KgEdgeRepository( dataSource );
    }

    private static String hubPage( final String related ) {
        return "---\ntype: hub\nrelated: [" + related + "]\n---\nBody text.";
    }

    // ---- loadHubNodes ----

    @Test
    void loadHubNodes_mergesKgAndFrontmatterHubsWithoutOverwritingKgEntry() {
        final KgNode kgHub = nodes.upsertNode( "AlphaHub", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of( "type", "hub" ) );
        nodes.upsertNode( "NotAHub", "concept", null, Provenance.HUMAN_AUTHORED, Map.of() );

        final StubPageManager pm = new StubPageManager();
        pm.savePage( "AlphaHub", hubPage( "X" ) ); // also declared in frontmatter — KG entry must win
        pm.savePage( "BetaHub", hubPage( "Y, Z" ) );

        final HubMemberLoader loader = new HubMemberLoader( nodes, edges, pm );
        final Map< String, KgNode > result = loader.loadHubNodes();

        assertEquals( 2, result.size() );
        assertEquals( kgHub, result.get( "AlphaHub" ) ); // KG node retained, not the synthesized one
        assertEquals( HubMemberLoader.synthesizeHubNode( "BetaHub" ), result.get( "BetaHub" ) );
    }

    // ---- loadFrontmatterHubs ----

    @Test
    void loadFrontmatterHubs_nullPageManager_returnsEmptyMap() {
        final HubMemberLoader loader = new HubMemberLoader( nodes, edges, null );
        assertEquals( Map.of(), loader.loadFrontmatterHubs() );
    }

    /**
     * StubPageManager#getAllPages doesn't declare {@code throws ProviderException}
     * (it never fails in practice), so an override can't declare it either — sneaky-throw
     * the real checked exception past the compiler so {@link HubMemberLoader}'s
     * {@code catch (ProviderException e)} branch is exercised for real, not simulated
     * with a RuntimeException it would not catch.
     */
    @SuppressWarnings( "unchecked" )
    private static < T extends Throwable > void sneakyThrow( final Throwable t ) throws T {
        throw ( T ) t;
    }

    @Test
    void loadFrontmatterHubs_getAllPagesThrows_returnsEmptyMap() {
        final StubPageManager throwing = new StubPageManager() {
            @Override
            public Collection< com.wikantik.api.core.Page > getAllPages() {
                HubMemberLoaderTest.< RuntimeException >sneakyThrow( new ProviderException( "boom: getAllPages" ) );
                return null; // unreachable
            }
        };
        final HubMemberLoader loader = new HubMemberLoader( nodes, edges, throwing );
        assertEquals( Map.of(), loader.loadFrontmatterHubs() );
    }

    @Test
    void loadFrontmatterHubs_skipsPageWhenGetPureTextThrows() {
        final StubPageManager pm = new StubPageManager() {
            @Override
            public String getPureText( final String page, final int version ) {
                if ( "Broken".equals( page ) ) throw new RuntimeException( "boom: getPureText" );
                return super.getPureText( page, version );
            }
        };
        pm.savePage( "Broken", hubPage( "X" ) );
        pm.savePage( "GoodHub", hubPage( "A, B" ) );

        final HubMemberLoader loader = new HubMemberLoader( nodes, edges, pm );
        final Map< String, List< String > > result = loader.loadFrontmatterHubs();

        assertEquals( Set.of( "GoodHub" ), result.keySet() );
        assertEquals( List.of( "A", "B" ), result.get( "GoodHub" ) );
    }

    @Test
    void loadFrontmatterHubs_skipsBlankRawText() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "EmptyPage", "" );

        final HubMemberLoader loader = new HubMemberLoader( nodes, edges, pm );
        assertEquals( Map.of(), loader.loadFrontmatterHubs() );
    }

    @Test
    void loadFrontmatterHubs_nonHubTypeIsSkipped() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "RegularArticle", "---\ntype: article\n---\nBody" );

        final HubMemberLoader loader = new HubMemberLoader( nodes, edges, pm );
        assertEquals( Map.of(), loader.loadFrontmatterHubs() );
    }

    @Test
    void loadFrontmatterHubs_hubWithoutRelatedYieldsEmptyMemberList() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "LonelyHub", "---\ntype: hub\n---\nBody" );

        final HubMemberLoader loader = new HubMemberLoader( nodes, edges, pm );
        final Map< String, List< String > > result = loader.loadFrontmatterHubs();
        assertEquals( List.of(), result.get( "LonelyHub" ) );
    }

    // ---- loadAllHubMembers ----

    @Test
    void loadAllHubMembers_combinesKgEdgesAndFrontmatterRelated() {
        final UUID techHub = nodes.upsertNode( "TechHub", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of( "type", "hub" ) ).id();
        final UUID java = nodes.upsertNode( "Java", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID python = nodes.upsertNode( "Python", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        edges.upsertEdge( techHub, java, "related_to", Provenance.HUMAN_CURATED, Map.of() );
        edges.upsertEdge( techHub, python, "related_to", Provenance.HUMAN_CURATED, Map.of() );
        // Edge whose source is NOT a hub must be ignored entirely.
        edges.upsertEdge( java, python, "related_to", Provenance.HUMAN_CURATED, Map.of() );

        final StubPageManager pm = new StubPageManager();
        pm.savePage( "FrontHub", hubPage( "A, B" ) );

        final HubMemberLoader loader = new HubMemberLoader( nodes, edges, pm );
        final Map< String, Set< String > > result = loader.loadAllHubMembers();

        assertEquals( Set.of( "Java", "Python" ), result.get( "TechHub" ) );
        assertEquals( Set.of( "A", "B" ), result.get( "FrontHub" ) );
        assertFalse( result.containsKey( "Java" ) ); // Java never a hub itself
    }

    @Test
    void loadAllHubMembers_hubWithNoEdgesOrRelatedHasEmptyMemberSet() {
        nodes.upsertNode( "LoneHub", "concept", null, Provenance.HUMAN_AUTHORED, Map.of( "type", "hub" ) );

        final HubMemberLoader loader = new HubMemberLoader( nodes, edges, null );
        final Map< String, Set< String > > result = loader.loadAllHubMembers();

        assertEquals( Set.of(), result.get( "LoneHub" ) );
    }

    // ---- hubNodeExists ----

    @Test
    void hubNodeExists_trueForKgHubNode() {
        // hubNodeExists queries the node_type COLUMN (not the properties JSON "type" field
        // that loadHubNodes/loadAllHubMembers key off), so the node must be typed hub for real.
        nodes.upsertNode( "KgOnlyHub", "hub", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final HubMemberLoader loader = new HubMemberLoader( nodes, edges, null );
        assertTrue( loader.hubNodeExists( "KgOnlyHub" ) );
    }

    @Test
    void hubNodeExists_trueForFrontmatterOnlyHub() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "FmOnlyHub", hubPage( "X" ) );
        final HubMemberLoader loader = new HubMemberLoader( nodes, edges, pm );
        assertTrue( loader.hubNodeExists( "FmOnlyHub" ) );
    }

    @Test
    void hubNodeExists_falseWhenNeitherSourceDeclaresIt() {
        final HubMemberLoader loader = new HubMemberLoader( nodes, edges, null );
        assertFalse( loader.hubNodeExists( "NoSuchHub" ) );
    }

    // ---- synthesizeHubNode ----

    @Test
    void synthesizeHubNode_producesDeterministicPlaceholder() {
        final KgNode synthesized = HubMemberLoader.synthesizeHubNode( "MyHub" );
        assertEquals( UUID.nameUUIDFromBytes( "frontmatter-hub:MyHub".getBytes( StandardCharsets.UTF_8 ) ),
                synthesized.id() );
        assertEquals( "MyHub", synthesized.name() );
        assertEquals( "hub", synthesized.nodeType() );
        assertEquals( "MyHub", synthesized.sourcePage() );
        assertEquals( Provenance.HUMAN_AUTHORED, synthesized.provenance() );
        assertEquals( Map.of( "type", "hub" ), synthesized.properties() );
        assertEquals( "human", synthesized.tier() );
        assertNull( synthesized.provenanceProposalId() );
    }

    // ---- coerceStringList ----

    @Test
    void coerceStringList_nonListReturnsEmpty() {
        assertEquals( List.of(), HubMemberLoader.coerceStringList( "not-a-list" ) );
        assertEquals( List.of(), HubMemberLoader.coerceStringList( null ) );
    }

    @Test
    void coerceStringList_filtersNullsAndStringifiesOthers() {
        final List< String > result = HubMemberLoader.coerceStringList( Arrays.asList( "A", null, 42 ) );
        assertEquals( List.of( "A", "42" ), result );
    }
}
