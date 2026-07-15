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
package com.wikantik.knowledge.eval;

import com.wikantik.api.knowledge.*;
import com.wikantik.api.eval.BundleSection;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class ContextServiceBundleRetrieverTest {

    @Test
    void flattens_pages_into_sections_resolving_canonical_id() {
        final RetrievedChunk chunk = new RetrievedChunk( List.of( "Setup" ), "body", 1.0, List.of() );
        final RetrievedPage page = new RetrievedPage(
            "DeployGuide", "/wiki/DeployGuide", 1.0, "", "ops",
            List.of(), List.of( chunk ), List.of(), "admin", null, false );

        final ContextRetrievalService svc = new StubService(
            new RetrievalResult( "deploy", List.of( page ), 1 ) );

        final ContextServiceBundleRetriever retriever =
            new ContextServiceBundleRetriever( svc, slug -> Optional.of( "01DEP" ) );

        final List<BundleSection> sections = retriever.apply( "deploy" );
        assertEquals( 1, sections.size() );
        assertEquals( "01DEP", sections.get( 0 ).canonicalId() );
        assertEquals( List.of( "Setup" ), sections.get( 0 ).headingPath() );
    }

    @Test
    void skips_pages_whose_slug_does_not_resolve() {
        final RetrievedChunk chunk = new RetrievedChunk( List.of( "Intro" ), "text", 0.9, List.of() );
        final RetrievedPage page = new RetrievedPage(
            "UnknownPage", "/wiki/UnknownPage", 0.9, "", "misc",
            List.of(), List.of( chunk ), List.of(), "admin", null, false );

        final ContextRetrievalService svc = new StubService(
            new RetrievalResult( "query", List.of( page ), 1 ) );

        final ContextServiceBundleRetriever retriever =
            new ContextServiceBundleRetriever( svc, slug -> Optional.empty() );

        final List<BundleSection> sections = retriever.apply( "query" );
        assertTrue( sections.isEmpty(), "Pages with unresolvable slugs must be skipped" );
    }

    @Test
    void flattens_multiple_chunks_per_page_in_order() {
        final RetrievedChunk c1 = new RetrievedChunk( List.of( "A" ), "first", 1.0, List.of() );
        final RetrievedChunk c2 = new RetrievedChunk( List.of( "B" ), "second", 0.8, List.of() );
        final RetrievedPage page = new RetrievedPage(
            "MultiChunkPage", "/wiki/MultiChunkPage", 1.0, "", "ops",
            List.of(), List.of( c1, c2 ), List.of(), "admin", null, false );

        final ContextRetrievalService svc = new StubService(
            new RetrievalResult( "multi", List.of( page ), 1 ) );

        final ContextServiceBundleRetriever retriever =
            new ContextServiceBundleRetriever( svc, slug -> Optional.of( "MULTI" ) );

        final List<BundleSection> sections = retriever.apply( "multi" );
        assertEquals( 2, sections.size() );
        assertEquals( List.of( "A" ), sections.get( 0 ).headingPath() );
        assertEquals( List.of( "B" ), sections.get( 1 ).headingPath() );
        assertEquals( "MULTI", sections.get( 0 ).canonicalId() );
        assertEquals( "MULTI", sections.get( 1 ).canonicalId() );
    }

    /** Minimal stub returning a fixed result; other methods unused. */
    private record StubService( RetrievalResult fixed ) implements ContextRetrievalService {
        public RetrievalResult retrieve( ContextQuery q ) { return fixed; }
        public RetrievedPage getPage( String n ) { return null; }
        public PageList listPages( PageListFilter f ) { return null; }
        public List<MetadataValue> listMetadataValues( String field ) { return List.of(); }
    }
}
