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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Unit tests for {@link DerivedReflowService}.
 *
 * <p>All seams (page-metadata reader, page lister, attachment-bytes reader, and
 * {@link DerivedPageIngestionService}) are faked or mocked so no wiki engine is needed.
 */
class DerivedReflowServiceTest {

    private static final String DERIVED_PAGE     = "MyReport";
    private static final String SOURCE_FILENAME  = "MyReport.pdf";
    private static final String CONTENT_TYPE     = "application/pdf";
    private static final byte[] SOURCE_BYTES     = "PDF content bytes".getBytes();

    /** Stale version — one below current. */
    private static final int STALE_VERSION = DerivedPageIngestionService.CURRENT_EXTRACTOR_VERSION - 1;
    /** Current version — equal to current. */
    private static final int CURRENT_VERSION = DerivedPageIngestionService.CURRENT_EXTRACTOR_VERSION;

    // Seam collaborators
    private DerivedPageIngestionService mockIngestionService;
    private Map< String, Map< String, Object > > pageMetadata; // pageName → metadata
    private List< String > allPageNames;
    private Map< String, byte[] > attachmentBytes; // "pageName/filename" → bytes

    private DerivedReflowService service;

    @BeforeEach
    void setUp() {
        mockIngestionService = Mockito.mock( DerivedPageIngestionService.class );
        pageMetadata = new HashMap<>();
        allPageNames = new ArrayList<>();
        attachmentBytes = new HashMap<>();

        // Default stub: ingestion returns UPDATED
        Mockito.when( mockIngestionService.ingest(
                any( byte[].class ), anyString(), anyString(), any( IngestOptions.class ) ) )
               .thenReturn( IngestResult.updated( DERIVED_PAGE ) );

        service = new DerivedReflowService(
            pageName -> Optional.ofNullable( pageMetadata.get( pageName ) ),
            () -> allPageNames,
            ( pageName, filename ) -> {
                final byte[] bytes = attachmentBytes.get( pageName + "/" + filename );
                if ( bytes == null ) {
                    throw new IllegalArgumentException( "No attachment: " + pageName + "/" + filename );
                }
                return bytes;
            },
            mockIngestionService );
    }

    // -----------------------------------------------------------------------
    // reflow(pageName) — delegation and correct attachment read
    // -----------------------------------------------------------------------

    @Test
    void reflow_derivedPage_readsCorrectAttachmentAndCallsIngest() throws Exception {
        // Arrange: derived page with stale version + a hand-added tag
        pageMetadata.put( DERIVED_PAGE, new HashMap<>( Map.of(
            DerivedPage.DERIVED_FROM,              SOURCE_FILENAME,
            DerivedPage.DERIVED_EXTRACTOR_VERSION, STALE_VERSION,
            DerivedPage.DERIVED_SOURCE_SHA,        "oldhash",
            "tags",                                "finance"
        ) ) );
        attachmentBytes.put( DERIVED_PAGE + "/" + SOURCE_FILENAME, SOURCE_BYTES );

        // Act
        final IngestResult result = service.reflow( DERIVED_PAGE );

        // Assert ingestion called with the right args
        final ArgumentCaptor< byte[] > bytesCaptor = ArgumentCaptor.forClass( byte[].class );
        final ArgumentCaptor< String > filenameCaptor = ArgumentCaptor.forClass( String.class );
        final ArgumentCaptor< IngestOptions > optsCaptor = ArgumentCaptor.forClass( IngestOptions.class );

        Mockito.verify( mockIngestionService ).ingest(
            bytesCaptor.capture(),
            filenameCaptor.capture(),
            anyString(),
            optsCaptor.capture() );

        assertArrayEquals( SOURCE_BYTES, bytesCaptor.getValue(), "Must read back the stored attachment bytes" );
        assertEquals( SOURCE_FILENAME, filenameCaptor.getValue(), "Filename must match derived_from value" );
        assertTrue( optsCaptor.getValue().force(), "Reflow must pass force=true" );
        assertNotNull( result );
    }

    @Test
    void reflow_delegatesToIngestionService_curating_is_ingestionsJob() throws Exception {
        // The curation-preservation (tags survive) is ingestion's responsibility (Task 4).
        // Here we only assert that reflow delegates without re-implementing merge.
        pageMetadata.put( DERIVED_PAGE, new HashMap<>( Map.of(
            DerivedPage.DERIVED_FROM,              SOURCE_FILENAME,
            DerivedPage.DERIVED_EXTRACTOR_VERSION, STALE_VERSION,
            "tags",                                "important"
        ) ) );
        attachmentBytes.put( DERIVED_PAGE + "/" + SOURCE_FILENAME, SOURCE_BYTES );

        service.reflow( DERIVED_PAGE );

        // Exactly one ingest call, no extra page-write logic here
        Mockito.verify( mockIngestionService, Mockito.times( 1 ) )
               .ingest( any(), any(), any(), any() );
    }

    // -----------------------------------------------------------------------
    // reflow(pageName) — non-derived page is skipped
    // -----------------------------------------------------------------------

    @Test
    void reflow_nonDerivedPage_returnsSkippedWithoutCallingIngest() throws Exception {
        pageMetadata.put( "PlainPage", new HashMap<>( Map.of(
            "title", "Just a wiki page"
            // no derived_from key
        ) ) );

        final IngestResult result = service.reflow( "PlainPage" );

        Mockito.verify( mockIngestionService, Mockito.never() ).ingest( any(), any(), any(), any() );
        assertNotNull( result );
        assertEquals( IngestResult.Status.UNCHANGED, result.status(),
            "Non-derived page reflow must return UNCHANGED (skipped)" );
    }

    @Test
    void reflow_absentPage_returnsSkippedWithoutCallingIngest() throws Exception {
        // pageMetadata has no entry for "GhostPage"
        final IngestResult result = service.reflow( "GhostPage" );

        Mockito.verify( mockIngestionService, Mockito.never() ).ingest( any(), any(), any(), any() );
        assertEquals( IngestResult.Status.UNCHANGED, result.status() );
    }

    // -----------------------------------------------------------------------
    // staleCount() / status()
    // -----------------------------------------------------------------------

    @Test
    void staleCount_countsOnlyPagesBelowCurrentVersion() {
        allPageNames.addAll( List.of( "Page1", "Page2", "Page3", "Page4" ) );

        // Page1: derived, stale
        pageMetadata.put( "Page1", new HashMap<>( Map.of(
            DerivedPage.DERIVED_FROM, "Page1.pdf",
            DerivedPage.DERIVED_EXTRACTOR_VERSION, STALE_VERSION ) ) );
        // Page2: derived, current
        pageMetadata.put( "Page2", new HashMap<>( Map.of(
            DerivedPage.DERIVED_FROM, "Page2.pdf",
            DerivedPage.DERIVED_EXTRACTOR_VERSION, CURRENT_VERSION ) ) );
        // Page3: NOT derived
        pageMetadata.put( "Page3", new HashMap<>( Map.of( "title", "manual" ) ) );
        // Page4: derived, stale
        pageMetadata.put( "Page4", new HashMap<>( Map.of(
            DerivedPage.DERIVED_FROM, "Page4.pdf",
            DerivedPage.DERIVED_EXTRACTOR_VERSION, STALE_VERSION ) ) );

        assertEquals( 2, service.staleCount(),
            "Only the two pages with version < CURRENT_EXTRACTOR_VERSION should be stale" );
    }

    @Test
    void staleCount_zeroWhenAllCurrent() {
        allPageNames.addAll( List.of( "P1", "P2" ) );
        pageMetadata.put( "P1", new HashMap<>( Map.of(
            DerivedPage.DERIVED_FROM, "P1.pdf",
            DerivedPage.DERIVED_EXTRACTOR_VERSION, CURRENT_VERSION ) ) );
        pageMetadata.put( "P2", new HashMap<>( Map.of( "title", "plain" ) ) );

        assertEquals( 0, service.staleCount() );
    }

    @Test
    void status_returnsCorrectCounts() {
        allPageNames.addAll( List.of( "D1", "D2", "Plain" ) );
        pageMetadata.put( "D1", new HashMap<>( Map.of(
            DerivedPage.DERIVED_FROM, "D1.pdf",
            DerivedPage.DERIVED_EXTRACTOR_VERSION, STALE_VERSION ) ) );
        pageMetadata.put( "D2", new HashMap<>( Map.of(
            DerivedPage.DERIVED_FROM, "D2.pdf",
            DerivedPage.DERIVED_EXTRACTOR_VERSION, CURRENT_VERSION ) ) );
        pageMetadata.put( "Plain", new HashMap<>( Map.of( "title", "manual" ) ) );

        final DerivedReflowService.ReflowStatus status = service.status();

        assertEquals( 2, status.derivedTotal(), "Two derived pages" );
        assertEquals( 1, status.staleCount(), "One stale" );
        assertEquals( CURRENT_VERSION, status.currentExtractorVersion() );
    }

    // -----------------------------------------------------------------------
    // reflowAll()
    // -----------------------------------------------------------------------

    @Test
    void reflowAll_reflowsOnlyDerivedPages() throws Exception {
        allPageNames.addAll( List.of( DERIVED_PAGE, "PlainWiki" ) );

        pageMetadata.put( DERIVED_PAGE, new HashMap<>( Map.of(
            DerivedPage.DERIVED_FROM, SOURCE_FILENAME,
            DerivedPage.DERIVED_EXTRACTOR_VERSION, STALE_VERSION ) ) );
        pageMetadata.put( "PlainWiki", new HashMap<>( Map.of( "title", "plain" ) ) );

        attachmentBytes.put( DERIVED_PAGE + "/" + SOURCE_FILENAME, SOURCE_BYTES );

        Mockito.when( mockIngestionService.ingest( any(), any(), any(), any() ) )
               .thenReturn( IngestResult.updated( DERIVED_PAGE ) );

        final DerivedReflowService.ReflowSummary summary = service.reflowAll( "bot" );

        assertEquals( 1, summary.reflowed(), "One derived page reflowed" );
        assertEquals( 1, summary.skipped(), "PlainWiki is not derived — counted as skipped" );
        assertEquals( 0, summary.failed(), "Zero failures" );

        Mockito.verify( mockIngestionService, Mockito.times( 1 ) )
               .ingest( any(), any(), any(), any() );
    }

    @Test
    void reflowAll_failedSinglePageLogsAndContinues() throws Exception {
        allPageNames.addAll( List.of( "GoodPage", "BadPage" ) );

        pageMetadata.put( "GoodPage", new HashMap<>( Map.of(
            DerivedPage.DERIVED_FROM, "GoodPage.pdf",
            DerivedPage.DERIVED_EXTRACTOR_VERSION, STALE_VERSION ) ) );
        pageMetadata.put( "BadPage", new HashMap<>( Map.of(
            DerivedPage.DERIVED_FROM, "BadPage.pdf",
            DerivedPage.DERIVED_EXTRACTOR_VERSION, STALE_VERSION ) ) );

        attachmentBytes.put( "GoodPage/GoodPage.pdf", SOURCE_BYTES );
        // BadPage attachment missing → will throw

        Mockito.when( mockIngestionService.ingest( any(), any(), any(), any() ) )
               .thenReturn( IngestResult.updated( "GoodPage" ) );

        final DerivedReflowService.ReflowSummary summary = service.reflowAll( "bot" );

        // GoodPage: reflowed; BadPage: failed (attachment missing) but did not blow up reflowAll
        assertEquals( 1, summary.reflowed() );
        assertEquals( 1, summary.failed() );
    }
}
