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
package com.wikantik.search.subsystem;

import com.wikantik.WikiEngine;
import com.wikantik.search.embedding.EmbeddingConfig;
import com.wikantik.search.hybrid.ChunkVectorIndex;
import com.wikantik.search.hybrid.LuceneHnswChunkVectorIndex;
import com.wikantik.search.hybrid.PgVectorChunkVectorIndex;
import com.wikantik.knowledge.chunking.ChunkProjector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the dense retrieval backend default. Regression guard for the perf
 * flip away from the brute-force {@code inmemory} scan (O(corpus) per query
 * and per save) to the RAM-backed, true-ANN {@code lucene-hnsw} backend,
 * which is also the prod (docker1) choice.
 */
class SearchWiringHelperTest {

    @Test
    void resolveDenseBackend_defaultsToLuceneHnswWhenPropertyAbsent() {
        final Properties props = new Properties();
        assertEquals( "lucene-hnsw", SearchWiringHelper.resolveDenseBackend( props ) );
    }

    @Test
    void resolveDenseBackend_respectsExplicitPropertyCaseInsensitively() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.search.dense.backend", "PgVector" );
        assertEquals( "pgvector", SearchWiringHelper.resolveDenseBackend( props ) );
    }

    @Test
    void resolveDenseBackend_respectsExplicitInmemory() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.search.dense.backend", "inmemory" );
        assertEquals( "inmemory", SearchWiringHelper.resolveDenseBackend( props ) );
    }

    // -- Single-instance-sharing regression tests (post d027a546da default flip) --
    //
    // SearchSubsystemFactory runs later in boot (WikiEngine.initialize():
    // buildSearchSubsystem(), AFTER initKnowledgeGraph() -> wireHybridRetrieval) and reads
    // engine.getManager(ChunkVectorIndex.class) to avoid building its own, orphaned, second
    // index instance. Before this fix, wireHybridRetrieval only registered that manager slot
    // for the "inmemory" backend — pgvector/lucene-hnsw left it unregistered, so the factory
    // always built (and the AsyncEmbeddingIndexListener upserts never reached) a second copy.

    @Test
    void wireHybridRetrieval_luceneHnswBackend_registersSharedChunkVectorIndex() throws SQLException {
        final Properties props = new Properties();
        props.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        props.setProperty( "wikantik.search.dense.backend", "lucene-hnsw" );

        final DataSource ds = mock( DataSource.class );
        when( ds.getConnection() ).thenThrow( new SQLException( "no db in unit test" ) );

        final ChunkProjector chunkProjector = mock( ChunkProjector.class );
        final WikiEngine engine = mock( WikiEngine.class );

        SearchWiringHelper.wireHybridRetrieval( props, ds, chunkProjector,
            /*chunkRepo=*/ null, /*fmCache=*/ null, /*rebuildService=*/ null, engine );

        final ArgumentCaptor< ChunkVectorIndex > captor = ArgumentCaptor.forClass( ChunkVectorIndex.class );
        verify( engine ).setManager( eq( ChunkVectorIndex.class ), captor.capture() );
        assertInstanceOf( LuceneHnswChunkVectorIndex.class, captor.getValue(),
            "the lucene-hnsw backend must register its ChunkVectorIndex, same as inmemory always did" );
    }

    @Test
    void wireHybridRetrieval_pgvectorBackend_registersSharedChunkVectorIndex() {
        final Properties props = new Properties();
        props.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        props.setProperty( "wikantik.search.dense.backend", "pgvector" );

        final DataSource ds = mock( DataSource.class );
        final ChunkProjector chunkProjector = mock( ChunkProjector.class );
        final WikiEngine engine = mock( WikiEngine.class );

        SearchWiringHelper.wireHybridRetrieval( props, ds, chunkProjector,
            /*chunkRepo=*/ null, /*fmCache=*/ null, /*rebuildService=*/ null, engine );

        final ArgumentCaptor< ChunkVectorIndex > captor = ArgumentCaptor.forClass( ChunkVectorIndex.class );
        verify( engine ).setManager( eq( ChunkVectorIndex.class ), captor.capture() );
        assertInstanceOf( PgVectorChunkVectorIndex.class, captor.getValue(),
            "the pgvector backend must register its ChunkVectorIndex, same as inmemory always did" );
    }
}
