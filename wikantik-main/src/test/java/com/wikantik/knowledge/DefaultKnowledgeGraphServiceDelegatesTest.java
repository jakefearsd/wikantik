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

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.PageKnowledgeSlice;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.knowledge.SchemaDescription;
import com.wikantik.api.knowledge.Tier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mock-repository coverage for {@link DefaultKnowledgeGraphService}'s large set of thin
 * delegate methods that neither {@code DefaultKnowledgeGraphServiceTest} (real-database,
 * exercises the happy paths that matter functionally) nor the other split-out test classes
 * in this package call directly: the {@code adminBypass}/{@code minTier}/{@code endpointKind}
 * overloads, the orphan/stub-count read paths, {@code diffAndRemoveStaleEdges}, filtered
 * proposal listing, rejection queries, {@code mergeNodes}' outbound-edge copy loop,
 * {@code getPageSlice}'s blank-input and failure branches, {@code clearAll}, and the
 * {@code fireKgChange} no-touched/no-removed early return (reached via a zero-match
 * {@code bulkDeleteEdges}). All four repositories are mocked, so no database is required
 * except where the service talks to its own internally-constructed {@code Jdbc} — those
 * cases mock the {@link DataSource}/{@link Connection}/{@link PreparedStatement}/
 * {@link ResultSet} chain directly.
 */
class DefaultKnowledgeGraphServiceDelegatesTest {

    private KgNodeRepository nodes;
    private KgEdgeRepository edges;
    private KgProposalRepository proposals;
    private KgRejectionRepository rejections;
    private DataSource dataSource;
    private DefaultKnowledgeGraphService service;

    @BeforeEach
    void setUp() {
        nodes = mock( KgNodeRepository.class );
        edges = mock( KgEdgeRepository.class );
        proposals = mock( KgProposalRepository.class );
        rejections = mock( KgRejectionRepository.class );
        dataSource = mock( DataSource.class );
        service = new DefaultKnowledgeGraphService( nodes, edges, proposals, rejections, dataSource );
    }

    private static KgNode nodeWithProps( final Map< String, Object > props ) {
        return new KgNode( UUID.randomUUID(), "N", "concept", null,
            Provenance.HUMAN_AUTHORED, props, null, null, "human", null );
    }

    @Test
    void discoverSchema_collectsDistinctStatusValues() {
        when( nodes.getDistinctNodeTypes() ).thenReturn( List.of() );
        when( edges.getDistinctRelationshipTypes() ).thenReturn( List.of() );
        when( nodes.queryNodes( any(), any(), eq( Integer.MAX_VALUE ), eq( 0 ) ) ).thenReturn( List.of(
            nodeWithProps( Map.of( "status", "active" ) ),
            nodeWithProps( Map.of( "status", "archived" ) ) ) );
        when( nodes.countNodes() ).thenReturn( 2L );
        when( edges.countEdges() ).thenReturn( 0L );
        when( proposals.countPendingBreakdown() ).thenReturn( SchemaDescription.PendingBreakdown.EMPTY );

        final SchemaDescription schema = service.discoverSchema();
        assertEquals( List.of( "active", "archived" ), schema.statusValues() );
    }

    @Test
    void getNodeAndGetNodeByName_adminBypassOverloadsDelegate() {
        final UUID id = UUID.randomUUID();
        final KgNode n = nodeWithProps( Map.of() );
        when( nodes.getNode( id, true ) ).thenReturn( n );
        when( nodes.getNodeByName( "Foo", true ) ).thenReturn( n );

        assertEquals( n, service.getNode( id, true ) );
        assertEquals( n, service.getNodeByName( "Foo", true ) );
    }

    @Test
    void setEngineAndOtherSimpleReadDelegatesAreExercised() {
        final com.wikantik.api.core.Engine engine = mock( com.wikantik.api.core.Engine.class );
        service.setEngine( engine );   // must not throw; also pushes the engine into snapshotBuilder

        final KgNode n = nodeWithProps( Map.of() );
        when( nodes.getNodeByName( "Foo" ) ).thenReturn( n );
        assertEquals( n, service.getNodeByName( "Foo" ) );

        final UUID nodeId = UUID.randomUUID();
        final List< KgEdge > edgeList = List.of();
        when( edges.getEdgesForNode( nodeId, "both" ) ).thenReturn( edgeList );
        assertEquals( edgeList, service.getEdgesForNode( nodeId, "both" ) );

        final Set< UUID > ids = Set.of( nodeId );
        final Map< UUID, String > names = Map.of( nodeId, "Foo" );
        when( nodes.getNodeNames( ids ) ).thenReturn( names );
        assertEquals( names, service.getNodeNames( ids ) );
    }

    @Test
    void clearAll_sqlExceptionIsCaughtLoggedAndRethrownAsRuntimeException() throws SQLException {
        final Connection conn = mock( Connection.class );
        when( dataSource.getConnection() ).thenReturn( conn );
        when( conn.createStatement() ).thenThrow( new SQLException( "ddl boom" ) );

        final RuntimeException ex = org.junit.jupiter.api.Assertions.assertThrows(
            RuntimeException.class, () -> service.clearAll() );
        assertTrue( ex.getCause() instanceof SQLException );
    }

    @Test
    void mergeNodes_copiesBothOutboundAndInboundEdges() {
        final UUID source = UUID.randomUUID();
        final UUID target = UUID.randomUUID();
        final UUID outboundPeer = UUID.randomUUID();
        final UUID inboundPeer = UUID.randomUUID();
        when( nodes.getNode( source ) ).thenReturn( nodeWithProps( Map.of() ) );
        when( nodes.getNode( target ) ).thenReturn( nodeWithProps( Map.of() ) );

        final KgEdge outbound = new KgEdge( UUID.randomUUID(), source, outboundPeer, "related_to",
            Provenance.HUMAN_AUTHORED, Map.of(), null, null, "human", null );
        final KgEdge inbound = new KgEdge( UUID.randomUUID(), inboundPeer, source, "related_to",
            Provenance.HUMAN_AUTHORED, Map.of(), null, null, "human", null );
        when( edges.getEdgesForNode( source, "outbound" ) ).thenReturn( List.of( outbound ) );
        when( edges.getEdgesForNode( source, "inbound" ) ).thenReturn( List.of( inbound ) );

        service.mergeNodes( source, target );

        verify( edges ).upsertEdge( target, outboundPeer, "related_to", Provenance.HUMAN_AUTHORED, Map.of() );
        verify( edges ).upsertEdge( inboundPeer, target, "related_to", Provenance.HUMAN_AUTHORED, Map.of() );
        verify( nodes ).deleteNode( source );
    }

    @Test
    void queryNodesAndCountsAndOrphanReads_allDelegate() {
        final Map< String, Object > filters = Map.of( "type", "concept" );
        final Set< Provenance > pf = Set.of( Provenance.HUMAN_AUTHORED );
        final List< KgNode > list = List.of( nodeWithProps( Map.of() ) );

        when( nodes.queryNodes( filters, pf, 10, 0 ) ).thenReturn( list );
        when( nodes.queryNodes( filters, pf, 10, 0, true ) ).thenReturn( list );
        when( nodes.countNodesWithFilter( filters, pf ) ).thenReturn( 3L );
        when( nodes.listOrphanedNodes( filters, 10, 0 ) ).thenReturn( list );
        when( nodes.countOrphanedNodes( filters ) ).thenReturn( 4L );
        when( nodes.countStubNodes() ).thenReturn( 5L );

        assertEquals( list, service.queryNodes( filters, pf, 10, 0 ) );
        assertEquals( list, service.queryNodes( filters, pf, 10, 0, true ) );
        assertEquals( 3L, service.countNodes( filters, pf ) );
        assertEquals( list, service.listOrphanedNodes( filters, 10, 0 ) );
        assertEquals( 4L, service.countOrphanedNodes( filters ) );
        assertEquals( 5L, service.countStubNodes() );
    }

    @Test
    void getPageSlice_blankOrNullPageNameReturnsEmptySliceWithoutQuerying() {
        assertEquals( new PageKnowledgeSlice( List.of(), List.of() ), service.getPageSlice( null ) );
        assertEquals( new PageKnowledgeSlice( List.of(), List.of() ), service.getPageSlice( "" ) );
        assertEquals( new PageKnowledgeSlice( List.of(), List.of() ), service.getPageSlice( "   " ) );
    }

    @Test
    void getPageSlice_sqlExceptionOnEntityIdLookupYieldsEmptySlice() throws SQLException {
        when( dataSource.getConnection() ).thenThrow( new SQLException( "boom" ) );
        final PageKnowledgeSlice slice = service.getPageSlice( "SomePage" );
        assertEquals( List.of(), slice.entities() );
        assertEquals( List.of(), slice.edges() );
    }

    @Test
    void getPageSlice_unresolvedNodeIsSkippedAndEdgeFailureDegradesGracefully() throws SQLException {
        final UUID entityId = UUID.randomUUID();
        wireOneRowPageSliceQuery( entityId );
        // The mentioned node id no longer resolves in kg_nodes -> resolveEntities logs + skips it.
        when( nodes.getNode( entityId ) ).thenReturn( null );
        // Edge collection then throws -> getPageSlice's own RuntimeException catch degrades to
        // an empty edge list rather than propagating.
        when( edges.getEdgesForNode( entityId, "both" ) ).thenThrow( new RuntimeException( "edges boom" ) );

        final PageKnowledgeSlice slice = service.getPageSlice( "SomePage" );
        assertTrue( slice.entities().isEmpty(), "unresolved node must be skipped, not included" );
        assertTrue( slice.edges().isEmpty(), "edge resolution failure must degrade to no edges" );
    }

    /** Wires the DataSource/Connection/PreparedStatement/ResultSet chain to return one row
     *  (a single node id) from the getPageSlice union query. */
    private void wireOneRowPageSliceQuery( final UUID entityId ) throws SQLException {
        final Connection conn = mock( Connection.class );
        final PreparedStatement ps = mock( PreparedStatement.class );
        final ResultSet rs = mock( ResultSet.class );
        when( dataSource.getConnection() ).thenReturn( conn );
        when( conn.prepareStatement( anyString() ) ).thenReturn( ps );
        when( ps.executeQuery() ).thenReturn( rs );
        when( rs.next() ).thenReturn( true, false );
        when( rs.getObject( 1, UUID.class ) ).thenReturn( entityId );
    }

    @Test
    void diffAndRemoveStaleEdges_delegates() {
        final UUID source = UUID.randomUUID();
        final Set< Map.Entry< String, String > > current = Set.of();
        service.diffAndRemoveStaleEdges( source, current );
        verify( edges ).diffAndRemoveStaleEdges( source, current );
    }

    @Test
    void queryEdgesFourArgOverload_delegatesThroughToRepoWithNullEndpointKind() {
        final List< Map< String, Object > > rows = List.of( Map.of( "id", "x" ) );
        when( edges.queryEdgesWithNames( "related_to", "foo", null, 10, 0 ) ).thenReturn( rows );
        assertEquals( rows, service.queryEdges( "related_to", "foo", 10, 0 ) );
    }

    @Test
    void traverseByCoMentionMinTierOverload_delegatesWithExplicitTier() {
        // Exercised only through the explicit-Tier overload — the 3-arg overload always
        // passes Tier.MACHINE internally, so this covers the direct 4-arg call path.
        service.traverseByCoMention( "Alice", 2, 3, Tier.HUMAN );
        // No repository call to verify directly (traversal is a separate collaborator built
        // internally); reaching here without throwing exercises the delegate line.
    }

    @Test
    void searchKnowledgeAdminBypassOverload_delegates() {
        final List< KgNode > result = List.of( nodeWithProps( Map.of() ) );
        when( nodes.searchNodes( "query", Set.of(), 5, true ) ).thenReturn( result );
        assertEquals( result, service.searchKnowledge( "query", Set.of(), 5, true ) );
    }

    @Test
    void proposalAndRejectionReadDelegates() {
        final UUID proposalId = UUID.randomUUID();
        when( proposals.listProposals( "pending", "Page", 10, 0 ) ).thenReturn( List.of() );
        when( proposals.getProposal( proposalId ) ).thenReturn( null );
        when( proposals.listProposalsFiltered( "pending", "human", "unjudged", false, "Page", 10, 0 ) )
            .thenReturn( List.of() );
        when( proposals.countProposalsFiltered( "pending", "human", "unjudged", false, "Page" ) )
            .thenReturn( 7L );
        when( proposals.listReviews( proposalId ) ).thenReturn( List.of() );
        when( proposals.countPendingUnjudgedProposals() ).thenReturn( 9L );
        when( rejections.listRejections( "A", "B", "related_to" ) ).thenReturn( List.of() );
        when( rejections.isRejected( "A", "B", "related_to" ) ).thenReturn( true );

        assertEquals( List.of(), service.listProposals( "pending", "Page", 10, 0 ) );
        assertEquals( null, service.getProposal( proposalId ) );
        assertEquals( List.of(), service.listProposals( "pending", "human", "unjudged", false, "Page", 10, 0 ) );
        assertEquals( 7L, service.countProposals( "pending", "human", "unjudged", false, "Page" ) );
        assertEquals( List.of(), service.listReviews( proposalId ) );
        assertEquals( 9L, service.countPendingUnjudgedProposals() );
        assertEquals( List.of(), service.listRejections( "A", "B", "related_to" ) );
        assertTrue( service.isRejected( "A", "B", "related_to" ) );
    }

    @Test
    void bulkDeleteEdges_zeroMatches_stillNoOpsFireKgChangeCleanly() {
        when( edges.countEdgesWithFilter( "related_to", "nomatch", null ) ).thenReturn( 0L );
        when( edges.findDistinctSourceIdsByFilter( "related_to", "nomatch", null ) ).thenReturn( List.of() );
        when( edges.bulkDeleteByFilter( "related_to", "nomatch", null ) ).thenReturn( 0 );

        final int deleted = service.bulkDeleteEdges( "related_to", "nomatch", 0 );

        assertEquals( 0, deleted );
        // No exception from fireKgChange(emptySet, emptySet) is the point of this test —
        // it exercises the "nothing to announce" early-return branch.
    }

    @Test
    void clearAll_deletesFromEveryTableThroughJdbc() throws SQLException {
        final Connection conn = mock( Connection.class );
        final Statement st = mock( Statement.class );
        when( dataSource.getConnection() ).thenReturn( conn );
        when( conn.createStatement() ).thenReturn( st );

        service.clearAll();

        verify( st, org.mockito.Mockito.times( 5 ) ).execute( anyString() );
    }
}
