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

import com.wikantik.ingest.ExtractionResult;
import com.wikantik.ingest.SourceExtractor;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies {@link IngestOptions#derivedFrom()} overrides the {@code derived_from} provenance metadata. */
class DerivedPageIngestionDerivedFromTest {

    private static final SourceExtractor EXTRACTOR = new SourceExtractor() {
        @Override
        public ExtractionResult extract( final InputStream source, final String contentType, final String filename ) {
            return new ExtractionResult( "# Body\n\ntext", null, Map.of() );
        }
        @Override
        public boolean supports( final String contentType ) { return true; }
    };

    private static DerivedPageIngestionService svc( final Map< String, Object > captured ) {
        return new DerivedPageIngestionService(
            EXTRACTOR,
            ( page, filename, bytes ) -> { },                         // AttachmentStore no-op
            page -> Optional.empty(),                                 // PageReader: page absent
            ( page, body, meta, author ) -> captured.putAll( meta ),  // PageWriter: capture metadata
            page -> { } );                                            // PageDeleter no-op
    }

    @Test
    void derivedFromOverrideIsHonored() {
        final Map< String, Object > meta = new HashMap<>();
        svc( meta ).ingest( "x".getBytes(), "a-x.md", "text/markdown",
            new IngestOptions( false, "sync", "file:a/x.md" ) );
        assertEquals( "file:a/x.md", meta.get( "derived_from" ) );
    }

    @Test
    void nullDerivedFromFallsBackToFilename() {
        final Map< String, Object > meta = new HashMap<>();
        svc( meta ).ingest( "x".getBytes(), "a-x.md", "text/markdown",
            new IngestOptions( false, "sync" ) );                     // 2-arg convenience ctor
        assertEquals( "a-x.md", meta.get( "derived_from" ) );
    }
}
