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

import com.wikantik.api.knowledge.KgEdgeView;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.PageKnowledgeSlice;
import com.wikantik.api.knowledge.ProposalConflictFlags;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Statement-count and equivalence tests for the batched KG lookups behind the admin proposal listing
 * ({@link ProposalConflictFlags#forProposals}) and {@link DefaultKnowledgeGraphService#getPageSlice}.
 */
@RequiresPostgres
class KgBatchLookupsTest {

    private static final String[] TABLES = { "chunk_entity_mentions", "kg_content_chunks", "kg_edges",
        "kg_rejections", "kg_nodes", "kg_excluded_pages" };

    private static DataSource dataSource;

    @BeforeAll
    static void init() { dataSource = PostgresTestDb.createDataSource(); }

    @BeforeEach
    void clean() { PostgresTestDb.truncate( TABLES ); }

    private static DefaultKnowledgeGraphService service( final DataSource ds ) {
        return new DefaultKnowledgeGraphService( new KgNodeRepository( ds ), new KgEdgeRepository( ds ),
            new KgProposalRepository( ds ), new KgRejectionRepository( ds ), ds );
    }

    private static KgProposal proposal( final String type, final Map< String, Object > data ) {
        return new KgProposal( UUID.randomUUID(), type, "P.md", data, 0.9, "r", "pending", null,
            null, null, "none", null, null, null, null );
    }

    private static void exclude( final String page ) throws Exception {
        try ( final Connection c = dataSource.getConnection();
              final java.sql.PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO kg_excluded_pages ( page_name, reason ) VALUES ( ?, 'page_override' )" ) ) {
            ps.setString( 1, page );
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------ proposal conflict flags

    private static List< KgProposal > mixedProposals( final int n ) {
        final List< KgProposal > list = new ArrayList<>();
        for ( int i = 0; i < n; i++ ) {
            list.add( proposal( "new-node", Map.of( "name", "Node" + i ) ) );
            list.add( proposal( "new-edge", Map.of( "source", "Node" + i, "target", "Node" + ( i + 1 ),
                "relationship", "uses" ) ) );
        }
        list.add( proposal( "new-node", Map.of( "name", "   " ) ) );
        list.add( proposal( "new-edge", Map.of( "source", "A" ) ) );
        list.add( proposal( "something-else", Map.of( "name", "Node0" ) ) );
        return list;
    }

    private void seedConflictFixtures( final int n ) throws Exception {
        final DefaultKnowledgeGraphService svc = service( dataSource );
        for ( int i = 0; i < n; i += 2 ) {
            svc.upsertNode( "Node" + i, "concept", null, Provenance.HUMAN_AUTHORED, Map.of() );
        }
        svc.upsertNode( "Hidden", "concept", "Secret.md", Provenance.HUMAN_AUTHORED, Map.of() );
        exclude( "Secret.md" );
        final KgRejectionRepository rej = new KgRejectionRepository( dataSource );
        for ( int i = 0; i < n; i += 3 ) {
            rej.insertRejection( "Node" + i, "Node" + ( i + 1 ), "uses", "alice", "no" );
        }
        // same pair, different relationship: must NOT make Node1->Node2 'uses' rejected
        rej.insertRejection( "Node1", "Node2", "depends_on", "alice", "no" );
    }

    @Test
    void forProposals_costsConstantStatements_notOnePerProposal() throws Exception {
        seedConflictFixtures( 40 );
        final StatementCountingDataSource counting = new StatementCountingDataSource( dataSource );
        final DefaultKnowledgeGraphService svc = service( counting );

        ProposalConflictFlags.forProposals( svc, mixedProposals( 3 ), true );
        final int small = counting.statementCount();
        counting.reset();
        ProposalConflictFlags.forProposals( svc, mixedProposals( 40 ), true );
        final int large = counting.statementCount();

        assertTrue( large <= 2, "node-name + rejection lookups must be one statement each, got " + large );
        assertEquals( small, large, "statement count must not grow with the number of proposals" );
    }

    @Test
    void forProposals_matchesPerProposalFlags_forEveryProposal() throws Exception {
        seedConflictFixtures( 20 );
        final DefaultKnowledgeGraphService svc = service( dataSource );
        final List< KgProposal > proposals = new ArrayList<>( mixedProposals( 20 ) );
        proposals.add( proposal( "new-node", Map.of( "name", "Hidden" ) ) );
        proposals.add( proposal( "new-node", Map.of( "name", "node0" ) ) ); // case-sensitive: no match

        for ( final boolean bypass : new boolean[] { true, false } ) {
            final List< Map< String, Object > > batch = ProposalConflictFlags.forProposals( svc, proposals, bypass );
            assertEquals( proposals.size(), batch.size() );
            for ( int i = 0; i < proposals.size(); i++ ) {
                assertEquals( ProposalConflictFlags.forProposal( svc, proposals.get( i ), bypass ), batch.get( i ),
                    "flags differ for proposal " + i + " (bypass=" + bypass + ")" );
            }
        }
        // sanity: the fixtures really exercise both outcomes, and the excluded node is hidden without bypass
        final var withBypass = ProposalConflictFlags.forProposals( svc, proposals, true );
        final var without = ProposalConflictFlags.forProposals( svc, proposals, false );
        final int hiddenIdx = proposals.size() - 2;
        assertEquals( true, withBypass.get( hiddenIdx ).get( "node_exists" ) );
        assertEquals( false, without.get( hiddenIdx ).get( "node_exists" ) );
        assertEquals( true, withBypass.get( 1 ).get( "edge_previously_rejected" ) );
        assertEquals( false, withBypass.get( 3 ).get( "edge_previously_rejected" ) );
    }

    @Test
    void forProposals_emptyList_issuesNoStatements() {
        final StatementCountingDataSource counting = new StatementCountingDataSource( dataSource );
        assertTrue( ProposalConflictFlags.forProposals( service( counting ), List.of(), true ).isEmpty() );
        assertEquals( 0, counting.statementCount() );
    }

    // ------------------------------------------------------------ getPageSlice

    private void seedChunkWithMentions( final String page, final UUID... nodeIds ) throws Exception {
        final UUID chunkId = UUID.randomUUID();
        try ( final Connection c = dataSource.getConnection() ) {
            try ( final java.sql.PreparedStatement ps = c.prepareStatement(
                "INSERT INTO kg_content_chunks "
              + "(id, page_name, chunk_index, text, char_count, token_count_estimate, content_hash) "
              + "VALUES (?, ?, 0, 't', 1, 1, 'h')" ) ) {
                ps.setObject( 1, chunkId );
                ps.setString( 2, page );
                ps.executeUpdate();
            }
            for ( final UUID id : nodeIds ) {
                try ( final java.sql.PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor) "
                  + "VALUES (?, ?, 1.0, 'test')" ) ) {
                    ps.setObject( 1, chunkId );
                    ps.setObject( 2, id );
                    ps.executeUpdate();
                }
            }
        }
    }

    /** Seeds {@code k} entities on PageA chained by edges, plus one excluded-page entity with edges to them. */
    private Set< UUID > seedSlice( final int k ) throws Exception {
        final DefaultKnowledgeGraphService svc = service( dataSource );
        final List< KgNode > ents = new ArrayList<>();
        for ( int i = 0; i < k; i++ ) {
            ents.add( svc.upsertNode( "Ent" + i, "concept", "PageA.md", Provenance.HUMAN_AUTHORED, Map.of() ) );
        }
        for ( int i = 0; i + 1 < k; i++ ) {
            svc.upsertEdge( ents.get( i ).id(), ents.get( i + 1 ).id(), "uses", Provenance.HUMAN_AUTHORED, Map.of() );
        }
        final KgNode hidden = svc.upsertNode( "HiddenEnt", "concept", "Secret.md", Provenance.HUMAN_AUTHORED, Map.of() );
        svc.upsertEdge( hidden.id(), ents.get( 0 ).id(), "uses", Provenance.HUMAN_AUTHORED, Map.of() );
        svc.upsertEdge( ents.get( 0 ).id(), hidden.id(), "uses", Provenance.HUMAN_AUTHORED, Map.of() );
        exclude( "Secret.md" );
        final List< UUID > ids = new ArrayList<>( ents.stream().map( KgNode::id ).toList() );
        ids.add( hidden.id() );
        seedChunkWithMentions( "PageA.md", ids.toArray( new UUID[ 0 ] ) );
        return Set.copyOf( ents.stream().map( KgNode::id ).toList() );
    }

    @Test
    void getPageSlice_costsConstantStatements_notPerEntity() throws Exception {
        seedSlice( 3 );
        final StatementCountingDataSource counting = new StatementCountingDataSource( dataSource );
        service( counting ).getPageSlice( "PageA.md" );
        final int small = counting.statementCount();

        PostgresTestDb.truncate( TABLES );
        seedSlice( 12 );
        counting.reset();
        service( counting ).getPageSlice( "PageA.md" );
        final int large = counting.statementCount();

        assertTrue( large <= 5, "ids + nodes + edges + names, got " + large );
        assertEquals( small, large, "statement count must not grow with the number of entities" );
    }

    @Test
    void getPageSlice_resultMatchesPerItemLookups_includingExclusion() throws Exception {
        final Set< UUID > visible = seedSlice( 6 );
        final DefaultKnowledgeGraphService svc = service( dataSource );

        final PageKnowledgeSlice slice = svc.getPageSlice( "PageA.md" );

        assertEquals( visible, Set.copyOf( slice.entities().stream().map( KgNode::id ).toList() ),
            "excluded-page entity must be skipped, all others present" );
        final KgNodeRepository nodes = new KgNodeRepository( dataSource );
        for ( final KgNode n : slice.entities() ) assertEquals( nodes.getNode( n.id() ), n );
        // 5 chain edges + Ent0->HiddenEnt (only the SOURCE endpoint is filtered, and the hidden id is still in
        // the page's id set); HiddenEnt->Ent0 is dropped because its source is on the excluded page.
        assertEquals( 6, slice.edges().size() );
        final KgEdgeRepository edges = new KgEdgeRepository( dataSource );
        final Set< UUID > expectedEdgeIds = new HashSet<>();
        final Set< UUID > pageIds = new HashSet<>( visible );
        pageIds.add( nodes.getNodeByName( "HiddenEnt", true ).id() );
        for ( final UUID id : pageIds ) {
            edges.getEdgesForNode( id, "both" ).stream()
                .filter( e -> pageIds.contains( e.sourceId() ) && pageIds.contains( e.targetId() ) )
                .forEach( e -> expectedEdgeIds.add( e.id() ) );
        }
        assertEquals( expectedEdgeIds, Set.copyOf( slice.edges().stream().map( KgEdgeView::id ).toList() ) );
        assertTrue( slice.edges().stream().allMatch( e -> e.sourceName().startsWith( "Ent" ) ) );
        assertEquals( 1, slice.edges().stream().filter( e -> e.targetName().isEmpty() ).count(),
            "the excluded endpoint's name stays unresolved" );
    }

    // ------------------------------------------------------------ counting DataSource

    /** Counts {@code prepareStatement}/{@code createStatement} calls on every connection handed out. */
    private static final class StatementCountingDataSource implements DataSource {
        private final DataSource delegate;
        private final AtomicInteger statements = new AtomicInteger();

        StatementCountingDataSource( final DataSource delegate ) { this.delegate = delegate; }

        int statementCount() { return statements.get(); }
        void reset() { statements.set( 0 ); }

        private Connection wrap( final Connection real ) {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class< ? >[] { Connection.class },
                ( proxy, method, args ) -> {
                    if ( method.getName().equals( "prepareStatement" ) || method.getName().equals( "createStatement" ) ) {
                        statements.incrementAndGet();
                    }
                    try {
                        return method.invoke( real, args );
                    } catch ( final java.lang.reflect.InvocationTargetException e ) {
                        throw e.getCause();
                    }
                } );
        }

        @Override public Connection getConnection() throws SQLException { return wrap( delegate.getConnection() ); }
        @Override public Connection getConnection( String u, String p ) throws SQLException {
            return wrap( delegate.getConnection( u, p ) );
        }
        @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
        @Override public void setLogWriter( PrintWriter w ) throws SQLException { delegate.setLogWriter( w ); }
        @Override public void setLoginTimeout( int s ) throws SQLException { delegate.setLoginTimeout( s ); }
        @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
        @Override public Logger getParentLogger() { return Logger.getLogger( "StatementCountingDataSource" ); }
        @Override public < T > T unwrap( Class< T > iface ) throws SQLException { return delegate.unwrap( iface ); }
        @Override public boolean isWrapperFor( Class< ? > iface ) throws SQLException { return delegate.isWrapperFor( iface ); }
    }
}
