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

import com.wikantik.TestEngine;
import com.wikantik.api.bundle.RetrievalMode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Properties;

/**
 * The context bundle's BM25 chunk sources need NO LLM — {@code wireHybridRetrieval}
 * must wire them even when there is no embedding client (hybrid disabled, or the
 * {@code wikantik.genai.mode} ceiling forcing embeddings off). The 2026-07-21
 * inference-host-offline validation found the method bailed out entirely before
 * {@code setBundleSectionSources}, leaving {@code /api/bundle} returning zero
 * sections although the lexical index was fully available.
 */
class BundleSourcesWithoutEmbedderTest {

    @Test
    void bundleLexicalSourcesWiredWhenGenAiModeNone() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:bundlesrc" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        try ( Connection c = h2.getConnection(); Statement st = c.createStatement() ) {
            st.execute( "CREATE TABLE kg_content_chunks ( id uuid PRIMARY KEY, page_name varchar(255), text clob )" );
            st.execute( "INSERT INTO kg_content_chunks VALUES ( random_uuid(), 'BundlePage', 'ontology layer content' )" );
        }

        final Properties props = TestEngine.getTestProperties();
        props.setProperty( "wikantik.genai.mode", "none" );
        props.setProperty( "wikantik.bundle.bm25.enabled", "true" );

        final TestEngine engine = new TestEngine( props );
        try {
            Assertions.assertNull( engine.bundleSectionSources(),
                    "precondition: plain unit TestEngine has no JNDI datasource, so nothing wired yet" );

            SearchWiringHelper.wireHybridRetrieval( props, h2,
                    /* chunkProjector */ null,
                    new com.wikantik.knowledge.chunking.ContentChunkRepository( h2 ),
                    /* fmCache */ null,
                    /* rebuildService */ null,
                    engine );

            final var sources = engine.bundleSectionSources();
            Assertions.assertNotNull( sources,
                    "bundle sources must be wired without an embedding client — BM25 needs no LLM" );
            Assertions.assertTrue( sources.containsKey( RetrievalMode.LEXICAL ),
                    "LEXICAL (BM25-only) source must be present" );
            Assertions.assertTrue( sources.containsKey( RetrievalMode.HYBRID ),
                    "HYBRID must be present (degrades to BM25-only ranking without query vectors)" );

            // SectionCandidates is package-private to com.wikantik.knowledge.bundle, so the
            // end-to-end "BM25 answers without an LLM" check lives in the runtime smoke
            // (/api/bundle against a genai.mode=none deployment); here we pin the wiring seam.
        } finally {
            engine.stop();
        }
    }
}
