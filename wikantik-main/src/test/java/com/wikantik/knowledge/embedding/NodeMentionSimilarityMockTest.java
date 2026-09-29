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
package com.wikantik.knowledge.embedding;

import com.wikantik.knowledge.embedding.NodeMentionSimilarity.ScoredName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mock-JDBC coverage for {@link NodeMentionSimilarity}'s defensive branches that a real
 * {@code PostgresTestDb} run never exercises: constructor validation, the SQLException
 * fallbacks on every query method (a checked exception {@link com.wikantik.jdbc.testing.FaultInjectingDataSource}
 * cannot inject, since it throws only unchecked faults), and the malformed-vector-bytes
 * branches of {@code decodeVector} (null {@code vec} column, byte-length/dim mismatch).
 * No database required.
 */
class NodeMentionSimilarityMockTest {

    @Test
    void constructorRejectsNullDataSource() {
        assertThrows( IllegalArgumentException.class,
            () -> new NodeMentionSimilarity( null, "model" ) );
    }

    @Test
    void constructorRejectsNullModelCode() {
        final DataSource ds = mock( DataSource.class );
        assertThrows( IllegalArgumentException.class,
            () -> new NodeMentionSimilarity( ds, null ) );
    }

    @Test
    void constructorRejectsBlankModelCode() {
        final DataSource ds = mock( DataSource.class );
        assertThrows( IllegalArgumentException.class,
            () -> new NodeMentionSimilarity( ds, "   " ) );
    }

    @Test
    void dimensionReturnsZeroOnSqlException() throws SQLException {
        final DataSource ds = mock( DataSource.class );
        when( ds.getConnection() ).thenThrow( new SQLException( "boom" ) );
        final NodeMentionSimilarity sim = new NodeMentionSimilarity( ds, "model" );

        assertEquals( 0, sim.dimension() );
        assertFalse( sim.isReady() );
    }

    @Test
    void vectorForReturnsEmptyOnSqlException() throws SQLException {
        final DataSource ds = mock( DataSource.class );
        when( ds.getConnection() ).thenThrow( new SQLException( "boom" ) );
        final NodeMentionSimilarity sim = new NodeMentionSimilarity( ds, "model" );

        assertEquals( Optional.empty(), sim.vectorFor( "Alice" ) );
    }

    @Test
    void mentionedNodeNamesReturnsEmptyListOnSqlException() throws SQLException {
        final DataSource ds = mock( DataSource.class );
        when( ds.getConnection() ).thenThrow( new SQLException( "boom" ) );
        final NodeMentionSimilarity sim = new NodeMentionSimilarity( ds, "model" );

        assertEquals( List.of(), sim.mentionedNodeNames() );
    }

    @Test
    void similarToReturnsEmptyListOnSqlExceptionDuringCentroidLoad() throws SQLException {
        final DataSource ds = mock( DataSource.class );
        when( ds.getConnection() ).thenThrow( new SQLException( "boom" ) );
        final NodeMentionSimilarity sim = new NodeMentionSimilarity( ds, "model" );

        // similarTo(vector, limit, excludes) calls loadAllCentroids() directly, so this
        // exercises the SQLException branch without needing vectorFor() to succeed first.
        final List< ScoredName > result =
            sim.similarTo( new float[]{ 1f, 0f }, 5, Set.of() );
        assertEquals( List.of(), result );
    }

    @Test
    void vectorForSkipsNullAndMalformedVectorRowsAndReturnsEmpty() throws SQLException {
        // One row with a null `vec` column, one row whose byte length doesn't match its
        // declared dim — both must be silently skipped (decodeVector's defensive branches),
        // leaving no usable vectors so vectorFor() returns empty.
        final DataSource ds = mock( DataSource.class );
        final Connection conn = mock( Connection.class );
        final PreparedStatement ps = mock( PreparedStatement.class );
        final ResultSet rs = mock( ResultSet.class );

        when( ds.getConnection() ).thenReturn( conn );
        when( conn.prepareStatement( anyString() ) ).thenReturn( ps );
        when( ps.executeQuery() ).thenReturn( rs );
        when( rs.next() ).thenReturn( true, true, false );
        when( rs.getInt( 1 ) ).thenReturn( 4, 4 );
        when( rs.getBytes( 2 ) ).thenReturn( null, new byte[]{ 1, 2, 3 } ); // wrong length for dim=4

        final NodeMentionSimilarity sim = new NodeMentionSimilarity( ds, "model" );
        assertEquals( Optional.empty(), sim.vectorFor( "Alice" ) );
    }

    @Test
    void allCentroidsSkipsNullAndMalformedVectorRows() throws SQLException {
        final DataSource ds = mock( DataSource.class );
        final Connection conn = mock( Connection.class );
        final PreparedStatement ps = mock( PreparedStatement.class );
        final ResultSet rs = mock( ResultSet.class );

        when( ds.getConnection() ).thenReturn( conn );
        when( conn.prepareStatement( anyString() ) ).thenReturn( ps );
        when( ps.executeQuery() ).thenReturn( rs );
        when( rs.next() ).thenReturn( true, true, true, false );
        when( rs.getString( 1 ) ).thenReturn( "Alice", "Bob", "Carol" );
        when( rs.getInt( 2 ) ).thenReturn( 4, 4, 2 );
        when( rs.getBytes( 3 ) ).thenReturn(
            null,                                   // Alice: null vec -> skipped
            new byte[]{ 1, 2, 3 },                  // Bob: wrong length for dim=4 -> skipped
            encode( new float[]{ 1f, 0f } ) );       // Carol: valid dim=2 vector

        final NodeMentionSimilarity sim = new NodeMentionSimilarity( ds, "model" );
        final Map< String, float[] > centroids = sim.allCentroids();

        assertEquals( Set.of( "Carol" ), centroids.keySet() );
        assertTrue( centroids.get( "Carol" ).length == 2 );
    }

    private static byte[] encode( final float[] vec ) {
        final ByteBuffer buf = ByteBuffer.allocate( vec.length * Float.BYTES )
            .order( ByteOrder.LITTLE_ENDIAN );
        for ( final float f : vec ) buf.putFloat( f );
        return buf.array();
    }
}
