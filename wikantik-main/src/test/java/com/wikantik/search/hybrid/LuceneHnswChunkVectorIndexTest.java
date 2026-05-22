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
package com.wikantik.search.hybrid;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LuceneHnswChunkVectorIndexTest {

    private static float[] unit( final float... v ) { return v; }

    @Test
    void ranksNearestVectorFirstAndMapsCosineScore() {
        final HnswParams params = new HnswParams( 16, 64, 100 );
        final LuceneHnswChunkVectorIndex idx = LuceneHnswChunkVectorIndex.forTesting( 3, params );

        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        idx.addOrReplace( a, "PageA", unit( 1f, 0f, 0f ) );
        idx.addOrReplace( b, "PageB", unit( 0f, 1f, 0f ) );
        idx.commitAndRefresh();

        assertTrue( idx.isReady() );
        assertEquals( 2, idx.size() );
        assertEquals( 3, idx.dimension() );

        final List< ScoredChunk > top = idx.topKChunks( unit( 1f, 0f, 0f ), 2 );
        assertEquals( a, top.get( 0 ).chunkId() );
        assertEquals( "PageA", top.get( 0 ).pageName() );
        assertEquals( 1.0, top.get( 0 ).score(), 1e-4 );
        assertEquals( 0.0, top.get( 1 ).score(), 1e-4 );
    }

    @Test
    void upsertReplacesVectorForSameChunkId() {
        final LuceneHnswChunkVectorIndex idx =
            LuceneHnswChunkVectorIndex.forTesting( 3, new HnswParams( 16, 64, 100 ) );
        final UUID a = UUID.randomUUID();
        idx.addOrReplace( a, "PageA", unit( 1f, 0f, 0f ) );
        idx.commitAndRefresh();
        idx.addOrReplace( a, "PageA", unit( 0f, 0f, 1f ) );
        idx.commitAndRefresh();

        assertEquals( 1, idx.size() );
        final List< ScoredChunk > top = idx.topKChunks( unit( 0f, 0f, 1f ), 1 );
        assertEquals( a, top.get( 0 ).chunkId() );
        assertEquals( 1.0, top.get( 0 ).score(), 1e-4 );
    }

    @Test
    void deleteRemovesChunk() {
        final LuceneHnswChunkVectorIndex idx =
            LuceneHnswChunkVectorIndex.forTesting( 3, new HnswParams( 16, 64, 100 ) );
        final UUID a = UUID.randomUUID();
        idx.addOrReplace( a, "PageA", unit( 1f, 0f, 0f ) );
        idx.commitAndRefresh();
        idx.delete( a );
        idx.commitAndRefresh();
        assertEquals( 0, idx.size() );
        assertFalse( idx.isReady() );
    }

    @Test
    void emptyIndexFailsClosed() {
        final LuceneHnswChunkVectorIndex idx =
            LuceneHnswChunkVectorIndex.forTesting( 3, new HnswParams( 16, 64, 100 ) );
        assertFalse( idx.isReady() );
        assertTrue( idx.topKChunks( unit( 1f, 0f, 0f ), 5 ).isEmpty() );
    }

    private static byte[] le( final float... v ) {
        final java.nio.ByteBuffer bb =
            java.nio.ByteBuffer.allocate( v.length * Float.BYTES ).order( java.nio.ByteOrder.LITTLE_ENDIAN );
        for ( final float f : v ) bb.putFloat( f );
        return bb.array();
    }

    @org.junit.jupiter.api.Test
    void buildsFromDatabaseRows() throws Exception {
        final java.util.UUID a = java.util.UUID.randomUUID();
        final java.sql.ResultSet rs = org.mockito.Mockito.mock( java.sql.ResultSet.class );
        org.mockito.Mockito.when( rs.next() ).thenReturn( true, false );
        org.mockito.Mockito.when( rs.getObject( 1, java.util.UUID.class ) ).thenReturn( a );
        org.mockito.Mockito.when( rs.getString( 2 ) ).thenReturn( "PageA" );
        org.mockito.Mockito.when( rs.getInt( 3 ) ).thenReturn( 3 );
        org.mockito.Mockito.when( rs.getBytes( 4 ) ).thenReturn( le( 1f, 0f, 0f ) );

        final java.sql.PreparedStatement ps = org.mockito.Mockito.mock( java.sql.PreparedStatement.class );
        org.mockito.Mockito.when( ps.executeQuery() ).thenReturn( rs );
        final java.sql.Connection conn = org.mockito.Mockito.mock( java.sql.Connection.class );
        org.mockito.Mockito.when( conn.prepareStatement( org.mockito.ArgumentMatchers.anyString() ) ).thenReturn( ps );
        final javax.sql.DataSource ds = org.mockito.Mockito.mock( javax.sql.DataSource.class );
        org.mockito.Mockito.when( ds.getConnection() ).thenReturn( conn );

        final LuceneHnswChunkVectorIndex idx =
            new LuceneHnswChunkVectorIndex( ds, "qwen3-embedding-0.6b", 3, new HnswParams( 16, 64, 100 ) );

        assertEquals( 1, idx.size() );
        assertTrue( idx.isReady() );
        assertEquals( a, idx.topKChunks( new float[]{ 1f, 0f, 0f }, 1 ).get( 0 ).chunkId() );
    }

    @org.junit.jupiter.api.Test
    void corruptRowIsSkippedNotFatal() throws Exception {
        // Row's dim column says 3 but the vec bytes are only 2 floats long: decode()
        // throws IllegalStateException. The backend must skip the row and stay up,
        // not crash the constructor (fail-closed).
        final java.util.UUID bad = java.util.UUID.randomUUID();
        final java.sql.ResultSet rs = org.mockito.Mockito.mock( java.sql.ResultSet.class );
        org.mockito.Mockito.when( rs.next() ).thenReturn( true, false );
        org.mockito.Mockito.when( rs.getObject( 1, java.util.UUID.class ) ).thenReturn( bad );
        org.mockito.Mockito.when( rs.getString( 2 ) ).thenReturn( "PageBad" );
        org.mockito.Mockito.when( rs.getInt( 3 ) ).thenReturn( 3 );
        org.mockito.Mockito.when( rs.getBytes( 4 ) ).thenReturn( le( 1f, 0f ) ); // 2 floats, not 3

        final java.sql.PreparedStatement ps = org.mockito.Mockito.mock( java.sql.PreparedStatement.class );
        org.mockito.Mockito.when( ps.executeQuery() ).thenReturn( rs );
        final java.sql.Connection conn = org.mockito.Mockito.mock( java.sql.Connection.class );
        org.mockito.Mockito.when( conn.prepareStatement( org.mockito.ArgumentMatchers.anyString() ) ).thenReturn( ps );
        final javax.sql.DataSource ds = org.mockito.Mockito.mock( javax.sql.DataSource.class );
        org.mockito.Mockito.when( ds.getConnection() ).thenReturn( conn );

        final LuceneHnswChunkVectorIndex idx = assertDoesNotThrow( () ->
            new LuceneHnswChunkVectorIndex( ds, "qwen3-embedding-0.6b", 3, new HnswParams( 16, 64, 100 ) ) );
        assertEquals( 0, idx.size(), "corrupt row skipped" );
        assertFalse( idx.isReady() );
    }

    @org.junit.jupiter.api.Test
    void constructorFailsClosedOnDbError() throws Exception {
        final javax.sql.DataSource ds = org.mockito.Mockito.mock( javax.sql.DataSource.class );
        org.mockito.Mockito.when( ds.getConnection() ).thenThrow( new java.sql.SQLException( "boom" ) );
        final LuceneHnswChunkVectorIndex idx =
            new LuceneHnswChunkVectorIndex( ds, "qwen3-embedding-0.6b", 3, new HnswParams( 16, 64, 100 ) );
        assertFalse( idx.isReady(), "DB failure leaves an empty, queryable index (BM25 fallback)" );
        assertTrue( idx.topKChunks( new float[]{ 1f, 0f, 0f }, 1 ).isEmpty() );
    }
}
