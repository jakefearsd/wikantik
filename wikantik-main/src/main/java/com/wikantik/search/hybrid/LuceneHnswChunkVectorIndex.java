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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * In-process Lucene HNSW dense-retrieval backend. Holds a RAM-resident
 * ({@link ByteBuffersDirectory}) Lucene index with one document per chunk
 * carrying a {@link KnnFloatVectorField}. Built at boot from
 * {@code content_chunk_embeddings} (see the DataSource constructor, added in a
 * later task) and kept current via {@code upsertChunks}.
 *
 * <p>Score: Lucene's {@link VectorSimilarityFunction#COSINE} score is
 * {@code (1 + cos) / 2}; we map back to raw cosine ({@code 2*score - 1}) so the
 * "larger is better" scale matches {@link InMemoryChunkVectorIndex}.</p>
 *
 * <p>Fail-closed: query/refresh failures log {@code WARN} and yield an empty
 * result or {@code isReady()==false} so hybrid retrieval degrades to BM25.</p>
 *
 * <p>See {@code docs/superpowers/specs/2026-05-22-lucene-hnsw-dense-retrieval-design.md}.</p>
 */
public final class LuceneHnswChunkVectorIndex implements ChunkVectorIndex {

    private static final Logger LOG = LogManager.getLogger( LuceneHnswChunkVectorIndex.class );

    static final String FIELD_VEC      = "vec";
    static final String FIELD_CHUNK_ID = "chunk_id";
    static final String FIELD_PAGE     = "page_name";

    private final javax.sql.DataSource dataSource;   // null in the test-only constructor
    private final String modelCode;                  // null in the test-only constructor
    private int dim;
    private int efSearch;
    private Directory directory;
    private IndexWriter writer;
    private SearcherManager searcherManager;
    private volatile long lastRebuildMillis;
    // Live doc count cached on the writer thread in commitAndRefresh() so the
    // hot read path (isReady(), called by DenseRetriever before every query)
    // is an O(1) volatile read rather than a second SearcherManager acquire.
    private volatile int cachedSize;

    private static final String LOAD_SQL =
        "SELECT e.chunk_id, c.page_name, e.dim, e.vec "
      + "FROM content_chunk_embeddings e "
      + "JOIN kg_content_chunks c ON c.id = e.chunk_id "
      + "WHERE e.model_code = ?";

    private static final String LOAD_BY_IDS_SQL =
        "SELECT e.chunk_id, c.page_name, e.dim, e.vec "
      + "FROM content_chunk_embeddings e "
      + "JOIN kg_content_chunks c ON c.id = e.chunk_id "
      + "WHERE e.model_code = ? AND e.chunk_id = ANY (?)";

    /** Test seam: build an empty index of the given dimension, no DB load. */
    static LuceneHnswChunkVectorIndex forTesting( final int dim, final HnswParams params ) {
        return new LuceneHnswChunkVectorIndex( dim, params );
    }

    LuceneHnswChunkVectorIndex( final int dim, final HnswParams params ) {
        this.dataSource = null;
        this.modelCode = null;
        initMachinery( dim, params );
    }

    /**
     * Production constructor: builds the index from {@code content_chunk_embeddings}.
     * Fail-closed — a DB error during the initial load logs {@code WARN} and leaves
     * an empty index so hybrid retrieval degrades to BM25 rather than blocking boot.
     */
    public LuceneHnswChunkVectorIndex( final javax.sql.DataSource dataSource,
                                       final String modelCode,
                                       final int dim,
                                       final HnswParams params ) {
        if ( dataSource == null ) throw new IllegalArgumentException( "dataSource must not be null" );
        if ( modelCode == null || modelCode.isBlank() ) {
            throw new IllegalArgumentException( "modelCode must not be blank" );
        }
        this.dataSource = dataSource;
        this.modelCode = modelCode;
        initMachinery( dim, params );
        reload();
    }

    /** Public accessor for the configured model code (used by the factory test + metrics). */
    public String modelCode() {
        return modelCode;
    }

    private void initMachinery( final int dim, final HnswParams params ) {
        if ( dim <= 0 ) throw new IllegalArgumentException( "dim must be positive, got " + dim );
        java.util.Objects.requireNonNull( params, "params must not be null" );
        this.dim = dim;
        this.efSearch = params.efSearch();
        try {
            this.directory = new ByteBuffersDirectory();
            final IndexWriterConfig cfg = new IndexWriterConfig().setCodec( hnswCodec( params ) );
            this.writer = new IndexWriter( directory, cfg );
            this.writer.commit();
            this.searcherManager = new SearcherManager( directory, null );
        } catch ( final java.io.IOException e ) {
            throw new IllegalStateException( "Failed to initialise Lucene HNSW index", e );
        }
    }

    /** Codec that forces our HNSW build params on the vector field. */
    private static Codec hnswCodec( final HnswParams params ) {
        return new Lucene104Codec() {
            @Override
            public KnnVectorsFormat getKnnVectorsFormatForField( final String field ) {
                return new Lucene99HnswVectorsFormat( params.m(), params.efConstruction() );
            }
        };
    }

    /** Add or replace the vector for a chunk. Caller must {@link #commitAndRefresh()} to publish. */
    public void addOrReplace( final UUID chunkId, final String pageName, final float[] vec ) {
        if ( chunkId == null || vec == null ) {
            throw new IllegalArgumentException( "chunkId and vec must not be null" );
        }
        if ( vec.length != dim ) {
            LOG.warn( "Lucene HNSW: chunk {} dim={} differs from index dim={}, skipping",
                chunkId, vec.length, dim );
            return;
        }
        try {
            final Document doc = new Document();
            doc.add( new KnnFloatVectorField( FIELD_VEC, vec, VectorSimilarityFunction.COSINE ) );
            doc.add( new StringField( FIELD_CHUNK_ID, chunkId.toString(), Field.Store.YES ) );
            doc.add( new StoredField( FIELD_PAGE, pageName == null ? "" : pageName ) );
            writer.updateDocument( new Term( FIELD_CHUNK_ID, chunkId.toString() ), doc );
        } catch ( final IOException e ) {
            LOG.warn( "Lucene HNSW addOrReplace failed for chunk {}: {}", chunkId, e.getMessage(), e );
        }
    }

    /** Remove a chunk. Caller must {@link #commitAndRefresh()} to publish. */
    public void delete( final UUID chunkId ) {
        if ( chunkId == null ) return;
        try {
            writer.deleteDocuments( new Term( FIELD_CHUNK_ID, chunkId.toString() ) );
        } catch ( final IOException e ) {
            LOG.warn( "Lucene HNSW delete failed for chunk {}: {}", chunkId, e.getMessage(), e );
        }
    }

    /** Commit pending writes and refresh the searcher so queries see them. */
    public void commitAndRefresh() {
        try {
            writer.commit();
            searcherManager.maybeRefresh();
            cachedSize = size();
            lastRebuildMillis = System.currentTimeMillis();
        } catch ( final IOException e ) {
            LOG.warn( "Lucene HNSW commit/refresh failed: {}", e.getMessage(), e );
        }
    }

    @Override
    public List< ScoredChunk > topKChunks( final float[] queryVec, final int k ) {
        if ( queryVec == null ) throw new IllegalArgumentException( "queryVec must not be null" );
        if ( k <= 0 ) throw new IllegalArgumentException( "k must be positive, got " + k );
        if ( queryVec.length != dim ) {
            throw new IllegalStateException( "queryVec length " + queryVec.length
                + " does not match index dimension " + dim );
        }
        final int fetch = Math.max( k, efSearch );
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            if ( searcher.getIndexReader().numDocs() == 0 ) return List.of();
            final KnnFloatVectorQuery query = new KnnFloatVectorQuery( FIELD_VEC, queryVec, fetch );
            final TopDocs hits = searcher.search( query, fetch );
            final StoredFields stored = searcher.storedFields();
            final List< ScoredChunk > out = new ArrayList<>( Math.min( k, hits.scoreDocs.length ) );
            for ( final ScoreDoc sd : hits.scoreDocs ) {
                if ( out.size() >= k ) break;
                final Document doc = stored.document( sd.doc );
                final String idStr = doc.get( FIELD_CHUNK_ID );
                final String page = doc.get( FIELD_PAGE );
                if ( idStr == null ) continue;
                final double cosine = 2.0 * sd.score - 1.0;
                out.add( new ScoredChunk( UUID.fromString( idStr ), page, cosine ) );
            }
            return out;
        } catch ( final IOException e ) {
            LOG.warn( "Lucene HNSW topKChunks failed (k={}): {}", k, e.getMessage(), e );
            return List.of();
        } finally {
            if ( searcher != null ) {
                try {
                    searcherManager.release( searcher );
                } catch ( final IOException e ) {
                    LOG.warn( "Lucene HNSW searcher release failed: {}", e.getMessage(), e );
                }
            }
        }
    }

    @Override
    public boolean isReady() {
        // O(1) volatile read — refreshed by commitAndRefresh(). Avoids a second
        // SearcherManager acquire on the per-query hot path.
        return cachedSize > 0;
    }

    @Override
    public int dimension() {
        return dim;
    }

    /** Live document count. Intended for metrics/admin. */
    public int size() {
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            return searcher.getIndexReader().numDocs();
        } catch ( final IOException e ) {
            LOG.warn( "Lucene HNSW size() failed: {}", e.getMessage(), e );
            return 0;
        } finally {
            if ( searcher != null ) {
                try {
                    searcherManager.release( searcher );
                } catch ( final IOException e ) {
                    LOG.warn( "Lucene HNSW searcher release failed: {}", e.getMessage(), e );
                }
            }
        }
    }

    /** Wall-clock millis of the most recent successful {@link #commitAndRefresh()}. */
    public long lastRebuildMillis() {
        return lastRebuildMillis;
    }

    /** Full rebuild from the database. Fail-closed: SQL errors log WARN and leave the prior contents. */
    public void reload() {
        if ( dataSource == null ) return; // test-only instance
        try ( java.sql.Connection c = dataSource.getConnection();
              java.sql.PreparedStatement ps = c.prepareStatement( LOAD_SQL ) ) {
            ps.setString( 1, modelCode );
            ps.setFetchSize( 500 );
            try ( java.sql.ResultSet rs = ps.executeQuery() ) {
                writer.deleteAll();
                int loaded = 0;
                while ( rs.next() ) {
                    if ( addRow( rs ) ) loaded++;
                }
                commitAndRefresh();
                LOG.info( "Lucene HNSW index loaded: model={} rows={} dim={}", modelCode, loaded, dim );
            }
        } catch ( final java.sql.SQLException | java.io.IOException e ) {
            LOG.warn( "Lucene HNSW reload failed (model={}); prior index contents preserved: {}",
                modelCode, e.getMessage(), e );
        }
    }

    /** Incremental update for a handful of changed chunks (called from the async index listener). */
    public void upsertChunks( final java.util.Collection< java.util.UUID > chunkIds ) {
        if ( dataSource == null || chunkIds == null || chunkIds.isEmpty() ) return;
        final java.util.Set< java.util.UUID > targets = new java.util.HashSet<>( chunkIds );
        try ( java.sql.Connection c = dataSource.getConnection();
              java.sql.PreparedStatement ps = c.prepareStatement( LOAD_BY_IDS_SQL ) ) {
            ps.setString( 1, modelCode );
            ps.setArray( 2, c.createArrayOf( "uuid", targets.toArray( new java.util.UUID[ 0 ] ) ) );
            final java.util.Set< java.util.UUID > seen = new java.util.HashSet<>();
            try ( java.sql.ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    final java.util.UUID id = rs.getObject( 1, java.util.UUID.class );
                    if ( addRow( rs ) ) seen.add( id );
                }
            }
            for ( final java.util.UUID id : targets ) {
                if ( !seen.contains( id ) ) delete( id );
            }
            commitAndRefresh();
        } catch ( final java.sql.SQLException e ) {
            LOG.warn( "Lucene HNSW upsert failed (model={}, ids={}); falling back to reload: {}",
                modelCode, targets.size(), e.getMessage(), e );
            reload();
        }
    }

    /** Decode one result-set row and stage an addOrReplace. Returns false if the row was skipped. */
    private boolean addRow( final java.sql.ResultSet rs ) throws java.sql.SQLException {
        final java.util.UUID id = rs.getObject( 1, java.util.UUID.class );
        final String page = rs.getString( 2 );
        final int rowDim = rs.getInt( 3 );
        final byte[] raw = rs.getBytes( 4 );
        if ( rowDim != dim ) {
            LOG.warn( "Lucene HNSW: chunk {} dim={} differs from index dim={}, skipping", id, rowDim, dim );
            return false;
        }
        try {
            final float[] v = ChunkVectorBytes.decode( id, raw, rowDim );
            if ( v == null ) return false;
            addOrReplace( id, page, v );
            return true;
        } catch ( final IllegalStateException e ) {
            // Corrupt vector bytes (length mismatch) — skip the row rather than let
            // the unchecked exception escape reload()/the constructor and disable the
            // whole backend. Fail-closed: one bad row must not block boot.
            LOG.warn( "Lucene HNSW: skipping chunk {} with corrupt vector bytes: {}", id, e.getMessage() );
            return false;
        }
    }
}
