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

import com.wikantik.search.hybrid.ChunkVectorIndex;
import com.wikantik.search.hybrid.HnswParams;
import com.wikantik.search.hybrid.InMemoryChunkVectorIndex;
import com.wikantik.search.hybrid.LuceneHnswChunkVectorIndex;
import com.wikantik.search.hybrid.PgVectorChunkVectorIndex;
import com.wikantik.search.hybrid.RefreshableChunkVectorIndex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.Properties;

/**
 * The supported values of {@code wikantik.search.dense.backend}, each knowing how to build
 * its {@link ChunkVectorIndex} from the wiki properties.
 *
 * <p>Both {@link SearchWiringHelper} (the real wiring path) and {@link SearchSubsystemFactory}
 * (the degraded fallback) build through {@link #build}; they differ only in the default they
 * apply when the property is absent, which they pass in explicitly (pinned by
 * {@code DenseBackendResolutionTest}). Whether the built index needs refreshing after
 * embedding writes is a property of its type: it is a {@link RefreshableChunkVectorIndex}
 * or it is not.
 */
enum DenseBackend {

    /** In-memory brute force. The only backend that needs no real {@code DataSource}. */
    INMEMORY( "inmemory" ) {
        @Override
        ChunkVectorIndex build( final DataSource ds, final String modelCode, final Properties props ) {
            final InMemoryChunkVectorIndex index = new InMemoryChunkVectorIndex( ds, modelCode );
            LOG.info( "Dense retrieval backend: in-memory brute-force (model={}, size={})",
                modelCode, index.size() );
            return index;
        }
    },

    /** pgvector HNSW — reads through to the database, so there is no snapshot to refresh. */
    PGVECTOR( "pgvector" ) {
        @Override
        ChunkVectorIndex build( final DataSource ds, final String modelCode, final Properties props ) {
            final int efSearch = Integer.parseInt( props.getProperty( EF_SEARCH_KEY, "100" ) );
            final ChunkVectorIndex index = new PgVectorChunkVectorIndex( ds, modelCode, efSearch );
            LOG.info( "Dense retrieval backend: pgvector HNSW (model={}, ef_search={})",
                modelCode, efSearch );
            return index;
        }
    },

    /** Lucene HNSW — RAM-backed ANN with incremental upserts; the production default. */
    LUCENE_HNSW( "lucene-hnsw" ) {
        @Override
        ChunkVectorIndex build( final DataSource ds, final String modelCode, final Properties props ) {
            final HnswParams params = HnswParams.fromProperties( props );
            final LuceneHnswChunkVectorIndex index = new LuceneHnswChunkVectorIndex(
                ds, modelCode, PgVectorChunkVectorIndex.EMBEDDING_DIM, params );
            LOG.info( "Dense retrieval backend: Lucene HNSW (model={}, m={}, ef_construction={}, "
                    + "ef_search={}, quantization={}, size={})",
                modelCode, params.m(), params.efConstruction(), params.efSearch(),
                params.quantization(), index.size() );
            return index;
        }
    };

    private static final Logger LOG = LogManager.getLogger( DenseBackend.class );
    private static final String EF_SEARCH_KEY = "wikantik.search.dense.pgvector.ef_search";

    private final String wire;

    DenseBackend( final String wire ) {
        this.wire = wire;
    }

    /** @return the value this backend has in {@code wikantik.search.dense.backend}. */
    String wire() {
        return wire;
    }

    /**
     * Resolves a configured value, ignoring case.
     *
     * @param value the configured backend name.
     * @return the matching backend.
     * @throws IllegalArgumentException naming every supported value and quoting {@code value}.
     */
    static DenseBackend parse( final String value ) {
        for ( final DenseBackend backend : values() ) {
            if ( backend.wire.equalsIgnoreCase( value ) ) {
                return backend;
            }
        }
        throw new IllegalArgumentException(
            "wikantik.search.dense.backend must be one of "
          + String.join( ", ", Arrays.stream( values() ).map( b -> "'" + b.wire + "'" ).toList() )
          + ", got: '" + value + "'" );
    }

    /**
     * Builds this backend's index and logs its startup line.
     *
     * @param ds        the wiki datasource; callers that cannot supply one must not call this
     *                  for a backend that reads from it.
     * @param modelCode the embedding model whose vectors the index serves.
     * @param props     wiki properties carrying the backend's tuning keys.
     * @return a {@link RefreshableChunkVectorIndex} for the in-process backends, a plain
     *         {@link ChunkVectorIndex} for pgvector.
     */
    abstract ChunkVectorIndex build( DataSource ds, String modelCode, Properties props );
}
