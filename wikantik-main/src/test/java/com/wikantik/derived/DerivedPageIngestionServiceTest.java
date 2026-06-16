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
import java.util.concurrent.atomic.AtomicReference;

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

    /** Tracks page names passed to the no-op page deleter (for rollback assertions). */
    private final List< String > deletedPageNames = new ArrayList<>();

    /** No-op page deleter that records invocations for assertions. */
    private final DerivedPageIngestionService.PageDeleter pageDeleter =
        pageName -> deletedPageNames.add( pageName );

    private DerivedPageIngestionService service;

    @BeforeEach
    void setUp() {
        storedFilenames.clear();
        writtenPageNames.clear();
        writtenBodies.clear();
        writtenMetadata.clear();
        deletedPageNames.clear();
        pageReaderResult = null;
        extractorResult = new ExtractionResult(
            "# My Report\n\nExtracted body.", "My Report Title", Map.of() );

        service = new DerivedPageIngestionService(
            extractor, attachmentStore, pageReader, pageWriter, pageDeleter );
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

    // (e) empty extraction → FAILED, neither pageWriter NOR attachmentStore called
    @Test
    void emptyExtraction_returnsFailed() throws Exception {
        extractorResult = new ExtractionResult( "", null, Map.of() );

        IngestResult result = service.ingest( SOURCE, FILENAME, CONTENT_TYPE, new IngestOptions( false, "bot" ) );

        assertEquals( IngestResult.Status.FAILED, result.status() );
        assertTrue( writtenPageNames.isEmpty(), "pageWriter must not be called on empty extraction" );
        // extraction happens before page write and attachment store — neither is called on empty extraction
        assertTrue( storedFilenames.isEmpty(), "attachmentStore must not be called on empty extraction" );
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

    // -------------------------------------------------------------------------
    // Security: refuse to overwrite a non-derived page (fix C)
    // -------------------------------------------------------------------------

    /**
     * If the target page exists and is NOT derived (no derived_from key),
     * ingest must return FAILED and must NOT call pageWriter or attachmentStore.
     */
    @Test
    void existingNonDerivedPage_refusesToOverwrite() throws Exception {
        // Simulate a human-authored page (type=article, no derived_from)
        pageReaderResult = new HashMap<>( Map.of( "type", "article" ) );

        final IngestResult result = service.ingest(
            SOURCE, FILENAME, CONTENT_TYPE, new IngestOptions( false, "bot" ) );

        assertEquals( IngestResult.Status.FAILED, result.status(),
            "ingest must refuse to overwrite a non-derived page" );
        assertTrue( writtenPageNames.isEmpty(),
            "pageWriter must NOT be called when target is a non-derived page" );
        assertTrue( storedFilenames.isEmpty(),
            "attachmentStore must NOT be called when target is a non-derived page" );
    }

    /**
     * If the target page exists and IS derived (has derived_from key),
     * ingest should proceed normally and return UPDATED.
     */
    @Test
    void existingDerivedPage_updatesNormally() throws Exception {
        // Simulate a pre-existing derived page with an old sha
        pageReaderResult = new HashMap<>( Map.of(
            DerivedPage.DERIVED_FROM,             FILENAME,
            DerivedPage.DERIVED_SOURCE_SHA,       "oldshavalue",
            DerivedPage.DERIVED_EXTRACTOR,        "tika",
            DerivedPage.DERIVED_EXTRACTOR_VERSION, 1
        ) );

        final IngestResult result = service.ingest(
            SOURCE, FILENAME, CONTENT_TYPE, new IngestOptions( false, "bot" ) );

        assertEquals( IngestResult.Status.UPDATED, result.status(),
            "ingest must update an existing derived page" );
        assertEquals( 1, writtenPageNames.size(), "pageWriter must be called for a derived update" );
    }

    // -------------------------------------------------------------------------
    // Rollback: attachment store failure on a NEW page must delete the page
    // -------------------------------------------------------------------------

    /**
     * When attachment storage fails for a <em>new</em> page (pageReader returns empty),
     * the service must return FAILED and invoke the pageDeleter to roll back the
     * orphaned page write.
     */
    @Test
    void attachmentStoreFailure_newPage_rollsBackAndReturnsFailed() throws Exception {
        // pageReaderResult is null → page does not exist (new ingest)
        final DerivedPageIngestionService.AttachmentStore failStore =
            ( pn, fn, b ) -> { throw new RuntimeException( "disk full" ); };
        final DerivedPageIngestionService svc = new DerivedPageIngestionService(
            extractor, failStore, pageReader, pageWriter, pageDeleter );

        final IngestResult result = svc.ingest( SOURCE, FILENAME, CONTENT_TYPE, new IngestOptions( false, "bot" ) );

        assertEquals( IngestResult.Status.FAILED, result.status(),
            "must be FAILED when attachment storage throws" );
        // pageWriter is called (parent-first ordering) before the attachment fails
        assertEquals( 1, writtenPageNames.size(),
            "pageWriter must be called before the attachment step" );
        // pageDeleter must have been invoked with the page name (rollback of new page)
        assertEquals( 1, deletedPageNames.size(),
            "pageDeleter must be called to roll back the newly-created page" );
        assertEquals( "MyReport", deletedPageNames.get( 0 ),
            "pageDeleter must be called with the derived page name" );
    }

    /**
     * When attachment storage fails for an <em>existing</em> derived page (update path),
     * the service must return FAILED but must NOT invoke pageDeleter — the pre-existing
     * page must not be destroyed.
     */
    @Test
    void attachmentStoreFailure_existingPage_noRollback_returnsFailed() throws Exception {
        // Simulate an existing derived page (update path)
        pageReaderResult = new HashMap<>( Map.of(
            DerivedPage.DERIVED_FROM,             FILENAME,
            DerivedPage.DERIVED_SOURCE_SHA,       "oldshavalue",
            DerivedPage.DERIVED_EXTRACTOR,        "tika",
            DerivedPage.DERIVED_EXTRACTOR_VERSION, 1
        ) );
        final DerivedPageIngestionService.AttachmentStore failStore =
            ( pn, fn, b ) -> { throw new RuntimeException( "disk full" ); };
        final DerivedPageIngestionService svc = new DerivedPageIngestionService(
            extractor, failStore, pageReader, pageWriter, pageDeleter );

        final IngestResult result = svc.ingest( SOURCE, FILENAME, CONTENT_TYPE, new IngestOptions( false, "bot" ) );

        assertEquals( IngestResult.Status.FAILED, result.status(),
            "must be FAILED when attachment storage throws on update" );
        // pageWriter is called (the update is written before the attachment step)
        assertEquals( 1, writtenPageNames.size(),
            "pageWriter must be called on the update path" );
        // pageDeleter must NOT be called — do not destroy the pre-existing page
        assertTrue( deletedPageNames.isEmpty(),
            "pageDeleter must NOT be called when rolling back would destroy a pre-existing page" );
    }
}
