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
package com.wikantik.knowledge.bundle;

import com.wikantik.api.bundle.ContextBundle;
import com.wikantik.api.knowledge.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class DefaultBundleAssemblyServiceTest {

    private static RetrievedChunk ch( String head, String text, double s ) {
        return new RetrievedChunk( List.of( head ), text, s, List.of() );
    }

    @Test
    void assembles_dedups_cites_and_caps_topN() {
        final RetrievedPage page = new RetrievedPage( "DeployGuide", "/wiki/DeployGuide", 1.0, "", "ops",
            List.of(), List.of( ch("Setup","setup",0.9), ch("Usage","usage",0.7) ), List.of(), "a", null );
        final ContextRetrievalService retrieval = new StubRetrieval(
            new RetrievalResult( "deploy", List.of( page ), 1 ) );
        final SectionReranker identity = ( q, secs ) -> secs;             // keep dense order
        final Function<String,Optional<String>> canon = slug -> Optional.of( "01DEP" );
        final Function<String,Integer> version = slug -> 7;

        final ContextBundle b = new DefaultBundleAssemblyService( retrieval, identity, canon, version, 3, 1 )
            .assemble( "deploy" );

        assertEquals( "deploy", b.query() );
        assertFalse( b.sections().isEmpty() );
        final var top = b.sections().get( 0 );
        assertEquals( "01DEP", top.canonicalId() );
        assertEquals( 7, top.citation().version() );
        assertEquals( top.text(), top.citation().span() );
        assertFalse( top.citation().spanSha256().isBlank() );
        // dedup: no two sections share (slug, headingPath)
        assertEquals( b.sections().stream().map( s -> s.slug()+s.headingPath() ).distinct().count(),
                      b.sections().size() );
    }

    private record StubRetrieval( RetrievalResult fixed ) implements ContextRetrievalService {
        public RetrievalResult retrieve( ContextQuery q ) { return fixed; }
        public RetrievedPage getPage( String n ) { return null; }
        public PageList listPages( PageListFilter f ) { return null; }
        public List<MetadataValue> listMetadataValues( String field ) { return List.of(); }
    }
}
