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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DerivedPageIngestionServiceTest {

    private static final String FILENAME     = "MyReport.pdf";
    private static final String CONTENT_TYPE = "application/pdf";
    private static final byte[] SOURCE       = "Hello world PDF content".getBytes();

    private final List< String >       storedFilenames = new ArrayList<>();
    private final List< String >       writtenPageNames = new ArrayList<>();
    private final List< String >       writtenBodies    = new ArrayList<>();
    private final List< Map< String, Object > > writtenMetadata = new ArrayList<>();

    /* Seam fakes */
    private final DerivedPageIngestionService.AttachmentStore attachmentStore =
        ( pageName, filename, bytes ) -> {
            storedFilenames.add( pageName + "/" + filename );
        };

    private Map< String, Object > pageReaderResult = null; // null = absent

    private final DerivedPageIngestionService.PageReader pageReader =
        pageName -> Optional.ofNullable( pageReaderResult );

    private final DerivedPageIngestionService.PageWriter pageWriter =
        ( pageName, body, metadata, author ) -> {
            writtenPageNames.add( pageName );
            writtenBodies.add( body );
            writtenMetadata.add( new HashMap<>( metadata ) );
        };

    /* Stub extractor returning a fixed result */
    private ExtractionResult extractorResult = new ExtractionResult(
        "# My Report\n\nExtracted body.", "My Report Title", Map.of() );

    private final SourceExtractor extractor = new SourceExtractor() {
        @Override
        public ExtractionResult extract( InputStream source, String contentType, String filename ) {
            return extractorResult;
        }
        @Override
        public boolean supports( String contentType ) { return true; }
    };

    private DerivedPageIngestionService service;

    @BeforeEach
    void setUp() {
        storedFilenames.clear();
        writtenPageNames.clear();
        writtenBodies.clear();
        writtenMetadata.clear();
        pageReaderResult = null;
        extractorResult = new ExtractionResult(
            "# My Report\n\nExtracted body.", "My Report Title", Map.of() );

        service = new DerivedPageIngestionService( extractor, attachmentStore, pageReader, pageWriter );
    }

    // (a) attachment stored with the source bytes
    @Test
    void attachmentStoredOnIngest() throws Exception {
        IngestResult result = service.ingest( SOURCE, FILENAME, CONTENT_TYPE, new IngestOptions( false, "bot" ) );

        assertEquals( IngestResult.Status.CREATED, result.status() );
        assertEquals( 1, storedFilenames.size() );
        assertEquals( "MyReport/" + FILENAME, storedFilenames.get( 0 ) );
    }

    // (b) pageWriter.write called with body == extracted markdown + all derived_* keys in metadata
    @Test
    void pageWrittenWithExtractedBodyAndDerivedMetadata() throws Exception {
        String sha = DerivedPage.sha256( SOURCE );

        IngestResult result = service.ingest( SOURCE, FILENAME, CONTENT_TYPE, new IngestOptions( false, "bot" ) );

        assertEquals( IngestResult.Status.CREATED, result.status() );
        assertEquals( "MyReport", result.pageName() );
        assertEquals( 1, writtenPageNames.size() );
        assertEquals( "MyReport", writtenPageNames.get( 0 ) );
        assertEquals( "# My Report\n\nExtracted body.", writtenBodies.get( 0 ) );

        Map< String, Object > meta = writtenMetadata.get( 0 );
        assertEquals( FILENAME,   meta.get( DerivedPage.DERIVED_FROM ) );
        assertEquals( "tika",     meta.get( DerivedPage.DERIVED_EXTRACTOR ) );
        assertNotNull( meta.get( DerivedPage.DERIVED_EXTRACTOR_VERSION ) );
        assertEquals( sha,        meta.get( DerivedPage.DERIVED_SOURCE_SHA ) );
        // type + title set because page was absent (new page)
        assertEquals( "reference", meta.get( "type" ) );
        assertEquals( "My Report Title", meta.get( "title" ) );
    }

    // (c) re-ingest with same sha → UNCHANGED, pageWriter NOT called
    @Test
    void sameShaNonForce_returnsUnchanged() throws Exception {
        String sha = DerivedPage.sha256( SOURCE );
        // Simulate the page already exists with the same sha
        pageReaderResult = new HashMap<>( Map.of(
            DerivedPage.DERIVED_FROM,             FILENAME,
            DerivedPage.DERIVED_SOURCE_SHA,       sha,
            DerivedPage.DERIVED_EXTRACTOR,        "tika",
            DerivedPage.DERIVED_EXTRACTOR_VERSION, 1
        ) );

        IngestResult result = service.ingest( SOURCE, FILENAME, CONTENT_TYPE, new IngestOptions( false, "bot" ) );

        assertEquals( IngestResult.Status.UNCHANGED, result.status() );
        assertTrue( writtenPageNames.isEmpty(), "pageWriter must not be called on unchanged" );
    }

    // (d) re-ingest with changed sha → UPDATED, pageWriter called
    @Test
    void differentSha_returnsUpdated() throws Exception {
        // existing page has a stale sha
        pageReaderResult = new HashMap<>( Map.of(
            DerivedPage.DERIVED_FROM,             FILENAME,
            DerivedPage.DERIVED_SOURCE_SHA,       "oldshavalue",
            DerivedPage.DERIVED_EXTRACTOR,        "tika",
            DerivedPage.DERIVED_EXTRACTOR_VERSION, 1
        ) );

        IngestResult result = service.ingest( SOURCE, FILENAME, CONTENT_TYPE, new IngestOptions( false, "bot" ) );

        assertEquals( IngestResult.Status.UPDATED, result.status() );
        assertEquals( 1, writtenPageNames.size() );
    }

    // (e) empty extraction → FAILED, pageWriter NOT called
    @Test
    void emptyExtraction_returnsFailed() throws Exception {
        extractorResult = new ExtractionResult( "", null, Map.of() );

        IngestResult result = service.ingest( SOURCE, FILENAME, CONTENT_TYPE, new IngestOptions( false, "bot" ) );

        assertEquals( IngestResult.Status.FAILED, result.status() );
        assertTrue( writtenPageNames.isEmpty(), "pageWriter must not be called on empty extraction" );
        // attachment is still stored (step 3 happens before extraction)
        assertEquals( 1, storedFilenames.size() );
    }

    // (f) existing human tags/type in metadata survive the update
    @Test
    void existingHumanMetadataSurvivesUpdate() throws Exception {
        pageReaderResult = new HashMap<>( Map.of(
            DerivedPage.DERIVED_FROM,             FILENAME,
            DerivedPage.DERIVED_SOURCE_SHA,       "oldshavalue",
            DerivedPage.DERIVED_EXTRACTOR,        "tika",
            DerivedPage.DERIVED_EXTRACTOR_VERSION, 1,
            "tags",                               "finance",
            "type",                               "report",   // human-set type
            "title",                              "Human Title" // human-set title
        ) );

        service.ingest( SOURCE, FILENAME, CONTENT_TYPE, new IngestOptions( false, "bot" ) );

        assertEquals( 1, writtenMetadata.size() );
        Map< String, Object > meta = writtenMetadata.get( 0 );
        // human-set type/title must NOT be clobbered
        assertEquals( "report",      meta.get( "type" ) );
        assertEquals( "Human Title", meta.get( "title" ) );
        // hand-added tags must survive
        assertEquals( "finance", meta.get( "tags" ) );
        // derived keys updated
        assertNotEquals( "oldshavalue", meta.get( DerivedPage.DERIVED_SOURCE_SHA ) );
    }

    // force=true with same sha must re-ingest
    @Test
    void forceOverridesUnchanged() throws Exception {
        String sha = DerivedPage.sha256( SOURCE );
        pageReaderResult = new HashMap<>( Map.of(
            DerivedPage.DERIVED_FROM,             FILENAME,
            DerivedPage.DERIVED_SOURCE_SHA,       sha,
            DerivedPage.DERIVED_EXTRACTOR,        "tika",
            DerivedPage.DERIVED_EXTRACTOR_VERSION , 1
        ) );

        IngestResult result = service.ingest( SOURCE, FILENAME, CONTENT_TYPE, new IngestOptions( true, "bot" ) );

        assertEquals( IngestResult.Status.UPDATED, result.status() );
        assertEquals( 1, writtenPageNames.size() );
    }

    // attachmentStore exception → FAILED, logged, not rethrown
    @Test
    void attachmentStoreException_returnsFailed() throws Exception {
        DerivedPageIngestionService.AttachmentStore failStore =
            ( pn, fn, b ) -> { throw new RuntimeException( "disk full" ); };
        DerivedPageIngestionService svc2 = new DerivedPageIngestionService(
            extractor, failStore, pageReader, pageWriter );

        IngestResult result = svc2.ingest( SOURCE, FILENAME, CONTENT_TYPE, new IngestOptions( false, "bot" ) );

        assertEquals( IngestResult.Status.FAILED, result.status() );
        assertTrue( writtenPageNames.isEmpty() );
    }
}
