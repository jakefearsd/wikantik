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
package com.wikantik.derived;

import com.wikantik.api.connectors.IngestOutcome;
import com.wikantik.api.connectors.SourceItem;
import com.wikantik.ingest.ExtractionResult;
import com.wikantik.ingest.SourceExtractor;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DerivedPageSinkAdapterTest {

    private static final SourceExtractor STUB_EXTRACTOR = new SourceExtractor() {
        @Override
        public ExtractionResult extract( final InputStream source, final String contentType, final String filename ) {
            return new ExtractionResult( "# B\n\nbody", "B", null );
        }

        @Override
        public boolean supports( final String contentType ) {
            return true;
        }
    };

    @Test void ingestSetsDerivedFromToSourceUriAndUsesFlatPageName() {
        final Map< String, Object > captured = new HashMap<>();
        final List< String > writtenPages = new ArrayList<>();
        final DerivedPageIngestionService ingestion = new DerivedPageIngestionService(
            STUB_EXTRACTOR, ( p, f, b ) -> { }, p -> Optional.empty(),
            ( page, body, meta, author ) -> { writtenPages.add( page ); captured.putAll( meta ); },
            p -> { } );

        final DerivedPageSinkAdapter adapter = new DerivedPageSinkAdapter( ingestion, p -> { }, "sync-bot" );
        final IngestOutcome out = adapter.ingest( new SourceItem(
            "file:docs/a.md", "raw".getBytes(), "text/markdown", Map.of(), List.of( "group:docs" ), "hash" ) );

        assertEquals( IngestOutcome.Status.CREATED, out.status() );
        assertEquals( "file:docs/a.md", captured.get( "derived_from" ) );    // DoD #1: provenance = source URI
        // DerivedPage.pageNameFor -> MarkupParser.cleanLink capitalizes the leading letter of the
        // flattened name ("docs-a" -> "Docs-a"); verified against the real cleanLink behavior.
        assertEquals( "Docs-a", out.pageName() );                            // flat, collision-free (basename of "docs-a.md")
        assertEquals( List.of( "Docs-a" ), writtenPages.stream().distinct().toList() );
    }

    @Test void deleteDelegatesToDeleter() {
        final List< String > deleted = new ArrayList<>();
        final DerivedPageIngestionService ingestion = new DerivedPageIngestionService(
            STUB_EXTRACTOR, ( p, f, b ) -> { },
            p -> Optional.empty(), ( a, b, c, d ) -> { }, p -> { } );
        new DerivedPageSinkAdapter( ingestion, deleted::add, "sync-bot" ).delete( "docs-a" );
        assertEquals( List.of( "docs-a" ), deleted );
    }
}
