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

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
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
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
 * (OR of {@link TermQuery}s) so no queryparser dependency is needed. Spike scaffolding
 * behind {@code wikantik.bundle.bm25.enabled} (default off) — see the bundle wiring.
 */
public final class LuceneBm25ChunkIndex {

    private static final Logger LOG = LogManager.getLogger( LuceneBm25ChunkIndex.class );
    static final String F_ID = "chunk_id";
    static final String F_PAGE = "page_name";
    static final String F_TEXT = "text";
    private static final int MAX_QUERY_TERMS = 1024;

    /** Minimal chunk projection the index needs (id, owning page, body). */
    public record IndexedChunk( UUID id, String pageName, String text ) {}

    private final Analyzer analyzer;
    private final Directory directory;
    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final int size;

    public LuceneBm25ChunkIndex( final List< IndexedChunk > chunks ) {
        this.analyzer = new StandardAnalyzer();
        this.directory = new ByteBuffersDirectory();
        try {
            final IndexWriterConfig cfg = new IndexWriterConfig( analyzer ).setSimilarity( new BM25Similarity() );
            int n = 0;
            try ( IndexWriter writer = new IndexWriter( directory, cfg ) ) {
                for ( final IndexedChunk c : chunks ) {
                    if ( c == null || c.text() == null || c.text().isBlank() ) continue;
                    final Document doc = new Document();
                    doc.add( new StringField( F_ID, c.id().toString(), Field.Store.YES ) );
                    doc.add( new StringField( F_PAGE, c.pageName() == null ? "" : c.pageName(), Field.Store.YES ) );
                    doc.add( new TextField( F_TEXT, c.text(), Field.Store.NO ) );
                    writer.addDocument( doc );
                    n++;
                }
            }
            this.reader = DirectoryReader.open( directory );
            this.searcher = new IndexSearcher( reader );
            this.searcher.setSimilarity( new BM25Similarity() );
            this.size = n;
        } catch ( final IOException e ) {
            throw new IllegalStateException( "Failed to build BM25 chunk index", e );
        }
    }

    /** Loads every chunk's text from {@code kg_content_chunks} and builds the index. */
    public static LuceneBm25ChunkIndex fromDataSource( final DataSource ds ) {
        final List< IndexedChunk > chunks = new ArrayList<>();
        final String sql = "select id, page_name, text from kg_content_chunks";
        try ( Connection conn = ds.getConnection();
              Statement st = conn.createStatement();
              ResultSet rs = st.executeQuery( sql ) ) {
            while ( rs.next() ) {
                chunks.add( new IndexedChunk(
                    UUID.fromString( rs.getString( "id" ) ), rs.getString( "page_name" ), rs.getString( "text" ) ) );
            }
        } catch ( final java.sql.SQLException e ) {
            throw new IllegalStateException( "Failed to load chunks for BM25 index", e );
        }
        return new LuceneBm25ChunkIndex( chunks );
    }

    /** Top-{@code k} chunks by BM25 score, highest first. Never throws → empty on any failure. */
    public List< ScoredChunk > topKChunks( final String queryText, final int k ) {
        if ( queryText == null || queryText.isBlank() || size == 0 || k <= 0 ) return List.of();
        try {
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
        }
    }

    public int size() {
        return size;
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
