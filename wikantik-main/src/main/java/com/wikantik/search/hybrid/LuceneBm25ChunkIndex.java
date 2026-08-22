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

import com.wikantik.jdbc.Jdbc;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * In-RAM Lucene BM25 index over content chunks — the lexical half of chunk-level
 * hybrid retrieval. The shipped context bundle ranks chunks by dense vector
 * similarity only ({@link DenseChunkSectionSource}); this supplies a true BM25
 * lexical ranking so {@code HybridChunkSectionSource} can RRF-fuse the two
 * (exact terms, rare tokens, acronyms, identifiers — dense retrieval's blind spots).
 *
 * <p>One {@link ByteBuffersDirectory} doc per chunk: analysed {@code text} +
 * stored {@code chunk_id}/{@code page_name}. Queries are built from analysed tokens
 * (OR of {@link TermQuery}s) so no queryparser dependency is needed. Enabled by
 * {@code wikantik.bundle.bm25.enabled} — see the bundle wiring.
 *
 * <h2>Staying current</h2>
 *
 * <p>The index is maintained in place, not snapshotted. {@link #upsertChunks} is
 * driven from the chunk-projection seam on every save, so a page becomes lexically
 * retrievable as soon as it has been chunked. That deliberately does <em>not</em>
 * depend on the embedding backend: BM25 needs only chunk text, and it is precisely
 * the half that has to keep working when embeddings are unavailable.</p>
 *
 * <p>Reconciliation is <em>page-scoped</em> rather than id-scoped. The save-time
 * notification carries only the ids a page still has, so a page re-chunked from three
 * chunks down to one would otherwise leave the two dropped chunks searchable forever.
 * Rewriting every affected page wholesale is self-healing and bounded by page size.</p>
 *
 * <p>Reads go through a {@link SearcherManager}, so queries in flight during a refresh
 * finish against a consistent point-in-time view.</p>
 */
public final class LuceneBm25ChunkIndex {

    private static final Logger LOG = LogManager.getLogger( LuceneBm25ChunkIndex.class );
    static final String F_ID = "chunk_id";
    static final String F_PAGE = "page_name";
    static final String F_TEXT = "text";
    private static final int MAX_QUERY_TERMS = 1024;

    private static final String LOAD_ALL_SQL =
        "select id, page_name, text from kg_content_chunks";
    private static final String PAGES_FOR_IDS_SQL =
        "select distinct page_name from kg_content_chunks where id = any (?)";
    private static final String LOAD_BY_PAGES_SQL =
        "select id, page_name, text from kg_content_chunks where page_name = any (?)";

    /** Minimal chunk projection the index needs (id, owning page, body). */
    public record IndexedChunk( UUID id, String pageName, String text ) {}

    private final Analyzer analyzer;
    private final Jdbc jdbc;   // null for the in-list (test) constructors
    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;
    /**
     * Live doc count, refreshed on the writer thread. Cached rather than read through
     * the {@link SearcherManager} because {@link #topKChunks} consults it before every
     * query and an acquire/release pair per query is pure overhead.
     */
    private volatile int cachedSize;

    public LuceneBm25ChunkIndex( final List< IndexedChunk > chunks ) {
        this( chunks, new StandardAnalyzer() );
    }

    public LuceneBm25ChunkIndex( final List< IndexedChunk > chunks, final Analyzer analyzer ) {
        this( analyzer, null );
        replaceAll( chunks );
    }

    private LuceneBm25ChunkIndex( final Analyzer analyzer, final DataSource dataSource ) {
        this.analyzer = analyzer;
        this.jdbc = dataSource == null ? null : new Jdbc( dataSource );
        this.directory = new ByteBuffersDirectory();
        try {
            final IndexWriterConfig cfg = new IndexWriterConfig( analyzer ).setSimilarity( new BM25Similarity() );
            this.writer = new IndexWriter( directory, cfg );
            this.writer.commit();
            // BM25 similarity is applied by the SearcherFactory, once per searcher at
            // creation. It must NOT be set on the acquired searcher inside topKChunks:
            // SearcherManager hands the same IndexSearcher to every concurrent request
            // thread, so mutating it per query is a data race on shared state.
            this.searcherManager = new SearcherManager( directory, new SearcherFactory() {
                @Override
                public IndexSearcher newSearcher( final IndexReader reader, final IndexReader previous ) {
                    final IndexSearcher s = new IndexSearcher( reader );
                    s.setSimilarity( new BM25Similarity() );
                    return s;
                }
            } );
        } catch ( final IOException e ) {
            throw new IllegalStateException( "Failed to build BM25 chunk index", e );
        }
    }

    /**
     * Loads every chunk's text from {@code kg_content_chunks} and builds the index with the
     * given analyzer. The returned index keeps the {@link DataSource} so it can be refreshed
     * incrementally afterwards — see {@link #upsertChunks}.
     */
    public static LuceneBm25ChunkIndex fromDataSource( final DataSource ds, final Analyzer analyzer ) {
        final LuceneBm25ChunkIndex index = new LuceneBm25ChunkIndex( analyzer, ds );
        index.replaceAll( index.loadAll() );
        return index;
    }

    /**
     * Re-indexes every page touched by {@code chunkIds}, so content saved after the index
     * was built becomes lexically retrievable without a restart.
     *
     * <p>Called on the embedding executor's thread (never the save thread) for every batch
     * of changed chunks, whether or not the embedding attempt for that batch succeeded.</p>
     *
     * <p>Fail-closed: any SQL or IO failure logs at {@code WARN} and leaves the previous
     * contents in place, so a transient database blip degrades to staleness rather than
     * emptying the lexical half of the fusion mid-flight.</p>
     */
    public void upsertChunks( final Collection< UUID > chunkIds ) {
        if ( jdbc == null || chunkIds == null || chunkIds.isEmpty() ) return;
        final Set< UUID > targets = new LinkedHashSet<>( chunkIds );
        try {
            final Set< String > pages = pagesFor( targets );
            final List< IndexedChunk > current = pages.isEmpty() ? List.of() : loadByPages( pages );

            // Rewrite each affected page wholesale: delete every doc carrying its
            // page_name, then re-add the chunks the page currently has.
            for ( final String page : pages ) {
                writer.deleteDocuments( new Term( F_PAGE, page ) );
            }
            // Ids we were notified about that no longer exist anywhere — their page could
            // not be resolved above, so drop them by id or they would linger indefinitely.
            final Set< UUID > alive = new HashSet<>();
            for ( final IndexedChunk c : current ) alive.add( c.id() );
            for ( final UUID id : targets ) {
                if ( !alive.contains( id ) ) writer.deleteDocuments( new Term( F_ID, id.toString() ) );
            }
            for ( final IndexedChunk c : current ) addDocument( c );
            commitAndRefresh();
        } catch ( final SQLException | IOException e ) {
            LOG.warn( "BM25 chunk upsert failed ({} ids); prior index contents preserved: {}",
                targets.size(), e.getMessage(), e );
        }
    }

    /**
     * Full rebuild from the database. Fail-closed in the same way as {@link #upsertChunks}:
     * a load failure leaves the existing index untouched rather than blanking it.
     */
    public void reload() {
        if ( jdbc == null ) return;   // in-list (test) instance: nothing to reload from
        final List< IndexedChunk > chunks;
        try {
            chunks = loadAll();
        } catch ( final IllegalStateException e ) {
            LOG.warn( "BM25 chunk reload failed; prior index contents preserved: {}", e.getMessage(), e );
            return;
        }
        replaceAll( chunks );
        LOG.info( "BM25 chunk index reloaded: chunks={}", cachedSize );
    }

    private List< IndexedChunk > loadAll() {
        try {
            return jdbc.query( LOAD_ALL_SQL, ps -> { }, LuceneBm25ChunkIndex::readChunk );
        } catch ( final SQLException e ) {
            throw new IllegalStateException( "Failed to load chunks for BM25 index", e );
        }
    }

    private Set< String > pagesFor( final Set< UUID > ids ) throws SQLException {
        final UUID[] idArr = ids.toArray( new UUID[ 0 ] );
        return jdbc.withConnection( conn -> {
            final List< String > pages = jdbc.query( conn, PAGES_FOR_IDS_SQL,
                ps -> ps.setArray( 1, conn.createArrayOf( "uuid", idArr ) ),
                rs -> rs.getString( 1 ) );
            return new LinkedHashSet<>( pages );
        } );
    }

    private List< IndexedChunk > loadByPages( final Set< String > pages ) throws SQLException {
        final String[] pageArr = pages.toArray( new String[ 0 ] );
        return jdbc.withConnection( conn -> jdbc.query( conn, LOAD_BY_PAGES_SQL,
            ps -> ps.setArray( 1, conn.createArrayOf( "text", pageArr ) ),
            LuceneBm25ChunkIndex::readChunk ) );
    }

    private static IndexedChunk readChunk( final ResultSet rs ) throws SQLException {
        return new IndexedChunk(
            UUID.fromString( rs.getString( "id" ) ), rs.getString( "page_name" ), rs.getString( "text" ) );
    }

    /** Wipes the index and rebuilds it from {@code chunks}. Used by construction and {@link #reload}. */
    private void replaceAll( final List< IndexedChunk > chunks ) {
        try {
            writer.deleteAll();
            for ( final IndexedChunk c : chunks ) {
                addDocument( c );
            }
            commitAndRefresh();
        } catch ( final IOException e ) {
            throw new IllegalStateException( "Failed to build BM25 chunk index", e );
        }
    }

    /** Stages one chunk, replacing any existing doc with the same id. */
    private void addDocument( final IndexedChunk c ) throws IOException {
        if ( c == null || c.text() == null || c.text().isBlank() ) return;
        final Document doc = new Document();
        doc.add( new StringField( F_ID, c.id().toString(), Field.Store.YES ) );
        doc.add( new StringField( F_PAGE, c.pageName() == null ? "" : c.pageName(), Field.Store.YES ) );
        doc.add( new TextField( F_TEXT, c.text(), Field.Store.NO ) );
        writer.updateDocument( new Term( F_ID, c.id().toString() ), doc );
    }

    /** Publishes staged writes to readers and refreshes the cached doc count. */
    private void commitAndRefresh() throws IOException {
        writer.commit();
        searcherManager.maybeRefreshBlocking();
        final IndexSearcher searcher = searcherManager.acquire();
        try {
            cachedSize = searcher.getIndexReader().numDocs();
        } finally {
            searcherManager.release( searcher );
        }
    }

    /**
     * Analyzer selector. {@code "code"} → a code-aware analyzer that splits camelCase and
     * snake_case so natural-language queries ("actor system", "add conditional edges") match
     * identifier tokens ({@code ActorSystem}, {@code add_conditional_edges}); {@code preserveOriginal}
     * keeps the whole token too, so exact-symbol queries still match. Anything else → StandardAnalyzer.
     */
    public static Analyzer analyzerFor( final String name ) {
        if ( !"code".equalsIgnoreCase( name ) ) return new StandardAnalyzer();
        try {
            return CustomAnalyzer.builder()
                .withTokenizer( "whitespace" )
                .addTokenFilter( "worddelimitergraph",
                    "generateWordParts", "1", "generateNumberParts", "1",
                    "splitOnCaseChange", "1", "splitOnNumerics", "1",
                    "catenateWords", "0", "catenateNumbers", "0", "catenateAll", "0",
                    "preserveOriginal", "1" )
                .addTokenFilter( "lowercase" )
                .addTokenFilter( "flattengraph" )
                .build();
        } catch ( final IOException e ) {
            LOG.warn( "Failed to build code analyzer; falling back to StandardAnalyzer: {}", e.getMessage() );
            return new StandardAnalyzer();
        }
    }

    /** Top-{@code k} chunks by BM25 score, highest first. Never throws → empty on any failure. */
    public List< ScoredChunk > topKChunks( final String queryText, final int k ) {
        if ( queryText == null || queryText.isBlank() || cachedSize == 0 || k <= 0 ) return List.of();
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();   // already BM25-configured by the SearcherFactory
            final Query query = buildQuery( queryText );
            final TopDocs hits = searcher.search( query, k );
            final StoredFields stored = searcher.storedFields();
            final List< ScoredChunk > out = new ArrayList<>( hits.scoreDocs.length );
            for ( final ScoreDoc sd : hits.scoreDocs ) {
                final Document d = stored.document( sd.doc );
                out.add( new ScoredChunk( UUID.fromString( d.get( F_ID ) ), d.get( F_PAGE ), sd.score ) );
            }
            return out;
        } catch ( final IOException | RuntimeException e ) {
            LOG.warn( "BM25 chunk query failed for '{}': {}", queryText, e.getMessage() );
            return List.of();
        } finally {
            if ( searcher != null ) {
                try {
                    searcherManager.release( searcher );
                } catch ( final IOException e ) {
                    LOG.warn( "BM25 searcher release failed: {}", e.getMessage(), e );
                }
            }
        }
    }

    /** Live document count. */
    public int size() {
        return cachedSize;
    }

    /** OR of the analysed query terms — pure-core Lucene, no queryparser dependency. */
    private Query buildQuery( final String queryText ) throws IOException {
        final BooleanQuery.Builder b = new BooleanQuery.Builder();
        int n = 0;
        try ( TokenStream ts = analyzer.tokenStream( F_TEXT, queryText ) ) {
            final CharTermAttribute term = ts.addAttribute( CharTermAttribute.class );
            ts.reset();
            while ( ts.incrementToken() && n < MAX_QUERY_TERMS ) {
                b.add( new TermQuery( new Term( F_TEXT, term.toString() ) ), BooleanClause.Occur.SHOULD );
                n++;
            }
            ts.end();
        }
        return b.build();
    }
}
