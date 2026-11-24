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
package com.wikantik.livevalues;

import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.knowledge.chunking.Chunk;
import com.wikantik.knowledge.chunking.ContentChunker;
import com.wikantik.search.embedding.EmbeddingTextBuilder;
import com.wikantik.search.subsystem.lucene.DefaultLuceneIndexer;
import com.wikantik.search.subsystem.lucene.LuceneIndexLifecycle;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Persisted indexes keep the raw token; what is analysed and embedded, and what is served, is the expanded value. */
class IndexLiveValuesTest {

    private static final String TOKEN = StubLiveValues.TOKEN;
    private static final String VALUE = StubLiveValues.VALUE;
    private static final String PAGE = "---\nsummary: Over " + TOKEN + " articles\n---\n# Size\n\nWe hold " + TOKEN + " articles.\n";

    private final StubLiveValues stub = new StubLiveValues();

    private int hits( final Directory dir, final String field, final String query ) throws Exception {
        try( DirectoryReader reader = DirectoryReader.open( dir ) ) {
            return new IndexSearcher( reader ).search( new QueryParser( field, new StandardAnalyzer() ).parse( query ), 10 )
                    .scoreDocs.length;
        }
    }

    private Document indexPage( final Directory dir, final boolean withLiveValues ) throws Exception {
        final LuceneIndexLifecycle lifecycle = mock( LuceneIndexLifecycle.class );
        final Analyzer analyzer = new StandardAnalyzer();
        when( lifecycle.getAnalyzer() ).thenReturn( analyzer );
        final AttachmentManager am = mock( AttachmentManager.class );
        when( am.listAttachments( any() ) ).thenReturn( List.of() );
        final DefaultLuceneIndexer indexer = new DefaultLuceneIndexer( () -> "unused", lifecycle,
                mock( PageManager.class ), am, mock( SystemPageRegistry.class ), new ArrayList<>() );
        if( withLiveValues ) {
            indexer.setLiveValuesSource( () -> stub );
        }
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "Sizes" );
        try( IndexWriter writer = new IndexWriter( dir, new IndexWriterConfig( analyzer ) ) ) {
            final Document doc = indexer.luceneIndexPage( page, PAGE, writer );
            writer.commit();
            return doc;
        }
    }

    @Test
    void luceneAnalysesTheExpandedValueButStoresTheToken() throws Exception {
        final Directory dir = new ByteBuffersDirectory();
        final Document doc = indexPage( dir, true );

        assertEquals( 1, hits( dir, "contents", "\"1,100\"" ), "searching the value finds the page" );
        assertEquals( 1, hits( dir, "summary", "\"1,100\"" ), "the summary is analysed expanded too" );
        assertTrue( doc.get( "contents" ).contains( TOKEN ), "stored body keeps the token" );
        assertTrue( doc.get( "summary" ).contains( TOKEN ), "stored summary keeps the token" );
    }

    @Test
    void withoutTheServiceTheTokenIsAnalysedVerbatim() throws Exception {
        final Directory dir = new ByteBuffersDirectory();
        final Document doc = indexPage( dir, false );

        assertEquals( 0, hits( dir, "contents", "\"1,100\"" ) );
        assertTrue( doc.get( "summary" ).contains( TOKEN ) );
    }

    @Test
    void storedSearchTextIsServedExpanded() {
        assertEquals( "We hold " + VALUE + " articles.",
                com.wikantik.search.subsystem.lucene.DefaultLuceneSearcher.servedContents( "We hold " + TOKEN + " articles.", stub ) );
        assertEquals( "x " + TOKEN, com.wikantik.search.subsystem.lucene.DefaultLuceneSearcher.servedContents( "x " + TOKEN, null ) );
    }

    @Test
    void embeddingInputIsExpandedAndHeadingsToo() {
        final String text = EmbeddingTextBuilder.forDocument(
                new EmbeddingTextBuilder.PageContext( "Sizes", null, null ),
                List.of( "Over " + TOKEN ), "We hold " + TOKEN + " articles.", stub );

        assertTrue( text.contains( "Section: Over " + VALUE ), text );
        assertTrue( text.contains( "We hold " + VALUE + " articles." ), text );
        assertTrue( !text.contains( "{{wiki:" ), text );
        assertTrue( EmbeddingTextBuilder.forDocument( EmbeddingTextBuilder.PageContext.EMPTY, List.of(), "a " + TOKEN, null )
                .contains( TOKEN ), "no service embeds verbatim" );
    }

    @Test
    void chunkerStoresTheRawToken() {
        final List< Chunk > chunks = new ContentChunker( new ContentChunker.Config( 900, 8, 0, 0 ) )
                .chunk( "Sizes", FrontmatterParser.parse( PAGE ) );

        assertTrue( chunks.stream().anyMatch( c -> c.text().contains( TOKEN ) ), "chunk rows keep the token" );
        assertTrue( chunks.stream().noneMatch( c -> c.text().contains( VALUE ) ) );
    }
}
