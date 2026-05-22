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
}
