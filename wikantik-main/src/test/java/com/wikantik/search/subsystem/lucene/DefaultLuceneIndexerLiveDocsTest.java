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
package com.wikantik.search.subsystem.lucene;

import org.apache.lucene.analysis.classic.ClassicAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultLuceneIndexer#getIndexedPageNames()} feeds the missing-page
 * sweep. It must report the pages search can actually match — the LIVE documents
 * — not every stored-fields slot.
 */
class DefaultLuceneIndexerLiveDocsTest {

    /**
     * A document deleted from a Lucene index keeps its stored fields on disk
     * until a merge reclaims the segment. Scanning {@code reader.maxDoc()} therefore
     * still sees the deleted page's id, so the sweep concludes the page is indexed
     * while the searcher — which honours {@code liveDocs} — cannot match it. The
     * page is then never re-indexed and is invisible to search indefinitely, with
     * the sweep reporting "0 pages missing" the whole time.
     *
     * <p>Observed in production 2026-09-10: the live index carried 173 deletions
     * across three segments, and the sweep counted one fully-deleted page
     * ({@code CpuInference}) as present.</p>
     *
     * <p>{@link NoMergePolicy} is load-bearing. Without it a three-document index
     * merges the deletion away immediately, {@code maxDoc() == numDocs()}, and the
     * test would pass against the very {@code maxDoc()} scan it exists to catch.
     * The precondition below fails the test rather than letting it go vacuous if
     * that ever stops holding.</p>
     */
    @Test
    void getIndexedPageNames_reportsLiveDocumentsOnly( @TempDir final Path tmp ) throws Exception {
        try ( Directory dir = FSDirectory.open( tmp );
              IndexWriter writer = new IndexWriter( dir,
                      new IndexWriterConfig( new ClassicAnalyzer() )
                              .setMergePolicy( NoMergePolicy.INSTANCE ) ) ) {
            for ( final String name : List.of( "AlphaPage", "BetaPage", "GammaPage" ) ) {
                final Document doc = new Document();
                doc.add( new Field( DefaultLuceneIndexer.LUCENE_ID, name, StringField.TYPE_STORED ) );
                writer.addDocument( doc );
            }
            writer.commit();
            writer.deleteDocuments( new Term( DefaultLuceneIndexer.LUCENE_ID, "BetaPage" ) );
            writer.commit();
        }

        try ( Directory dir = FSDirectory.open( tmp );
              DirectoryReader reader = DirectoryReader.open( dir ) ) {
            assertTrue( reader.maxDoc() > reader.numDocs(),
                    "precondition: the deleted document must still occupy a maxDoc slot, "
                    + "otherwise this test cannot detect a maxDoc-based scan (maxDoc="
                    + reader.maxDoc() + ", numDocs=" + reader.numDocs() + ")" );
        }

        final DefaultLuceneIndexer indexer = new DefaultLuceneIndexer(
                tmp::toString, null, null, null, null, new ArrayList<>() );

        assertEquals( Set.of( "AlphaPage", "GammaPage" ), indexer.getIndexedPageNames(),
                "the sweep must not count a deleted-but-unmerged document as an indexed page" );
    }
}
