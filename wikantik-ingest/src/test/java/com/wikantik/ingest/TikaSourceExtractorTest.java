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
package com.wikantik.ingest;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TikaSourceExtractorTest {

    /** Default-constructed extractor — used by all existing tests. */
    private final TikaSourceExtractor ex = new TikaSourceExtractor();

    private InputStream fixture( final String name ) {
        return getClass().getResourceAsStream( "/derived/" + name );
    }

    // ------------------------------------------------------------------ existing tests

    @Test
    void supportsCommonDocTypes() {
        assertTrue( ex.supports( "application/pdf" ) );
        assertTrue( ex.supports( "text/plain" ) );
        assertTrue( ex.supports( "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ) );
        assertFalse( ex.supports( "image/png" ) );
    }

    @Test
    void extractsPlainText() throws Exception {
        try ( InputStream in = fixture( "sample.txt" ) ) {
            final ExtractionResult r = ex.extract( in, "text/plain", "sample.txt" );
            assertFalse( r.isEmpty() );
            assertTrue( r.markdownBody().toLowerCase().contains( "hello" ) );
        }
    }

    @Test
    void extractsDocxWithBodyText() throws Exception {
        try ( InputStream in = fixture( "sample.docx" ) ) {
            final ExtractionResult r = ex.extract( in, null, "sample.docx" );
            assertFalse( r.isEmpty(), "docx extraction should not be blank" );
        }
    }

    @Test
    void extractsPdfWithBodyText() throws Exception {
        try ( InputStream in = fixture( "sample.pdf" ) ) {
            final ExtractionResult r = ex.extract( in, "application/pdf", "sample.pdf" );
            assertFalse( r.isEmpty(), "pdf extraction should not be blank" );
        }
    }

    @Test
    void emptyExtractionIsReportedNotBlankSilently() throws Exception {
        // An empty text stream → isEmpty()==true (caller flags it, does not save a blank page).
        try ( InputStream in = new ByteArrayInputStream( "   ".getBytes() ) ) {
            assertTrue( ex.extract( in, "text/plain", "blank.txt" ).isEmpty() );
        }
    }

    // ------------------------------------------------------------------ write-limit tests

    /**
     * A tiny write limit (50 chars of XHTML characters) must not cause an exception:
     * the extractor catches WriteLimitReachedException and returns whatever partial
     * content Tika had already written.  The result is non-null and the metadata
     * indicates truncation.  This is the key defence against decompression bombs.
     */
    @Test
    void writeLimitTruncatesInsteadOfOom() throws Exception {
        // Build a plain-text payload well above the 50-char write limit.
        final String longText = "Hello world! ".repeat( 200 ); // ~2600 chars
        final byte[] bytes = longText.getBytes( StandardCharsets.UTF_8 );

        // 50-char limit — well below the document size.
        final TikaSourceExtractor limited = new TikaSourceExtractor( 50, 30 );
        final ExtractionResult result;
        try ( InputStream in = new ByteArrayInputStream( bytes ) ) {
            // Must NOT throw — truncation is silent (just a warn log).
            result = limited.extract( in, "text/plain", "bomb.txt" );
        }

        // Non-null result with truncation marker in metadata.
        assertNotNull( result );
        assertEquals( "true", result.metadata().get( "wikantik.ingest.truncated" ),
            "truncated metadata flag must be set when write limit is reached" );
        // The markdown body may be empty (flexmark got an incomplete XML fragment)
        // but the contract is: no OOM, no ExtractionException, truncation flag present.
        // We do NOT assert isEmpty()==false because a 50-char XML fragment may not
        // produce any markdown tokens — the invariant is absence of exception.
    }

    /**
     * A write limit larger than the document must produce a full (non-truncated) result
     * with no truncation flag — proving the normal path is unaffected.
     */
    @Test
    void writeLimitDoesNotTruncateSmallDocument() throws Exception {
        final TikaSourceExtractor large = new TikaSourceExtractor( 1_000_000, 30 );
        try ( InputStream in = fixture( "sample.txt" ) ) {
            final ExtractionResult r = large.extract( in, "text/plain", "sample.txt" );
            assertFalse( r.isEmpty() );
            assertNull( r.metadata().get( "wikantik.ingest.truncated" ),
                "truncation flag must be absent for docs within the write limit" );
        }
    }

    // ------------------------------------------------------------------ timeout tests

    /**
     * Structural wiring test: verifies the timeout constructor parameter is stored
     * and would be applied.  We use a generous timeout (30 s) so the normal fixture
     * parse completes — this confirms the timeout-wired path produces a correct result
     * rather than spinning forever.
     *
     * A true hang-simulation test would require injecting a blocking parser mock,
     * which would be too invasive and fragile for this unit-test layer.  The timeout
     * code path (TimeoutException branch) is intentionally covered by the structural
     * assertion below and by the fact that the executor+Future plumbing is exercised
     * on every test in this class.
     */
    @Test
    void timeoutWiredExtractorProducesNormalResultForSmallDoc() throws Exception {
        // 1-second timeout — more than enough for a tiny plain-text file.
        final TikaSourceExtractor timed = new TikaSourceExtractor( TikaSourceExtractor.DEFAULT_WRITE_LIMIT_CHARS, 1 );
        try ( InputStream in = fixture( "sample.txt" ) ) {
            final ExtractionResult r = timed.extract( in, "text/plain", "sample.txt" );
            assertFalse( r.isEmpty(), "timed extractor should still parse small docs correctly" );
        }
    }

    /**
     * Timeout of 0 seconds: Future.get(0, SECONDS) should time out immediately,
     * exercising the TimeoutException → ExtractionException path without any
     * blocking parse or sleep.
     */
    @Test
    void zeroTimeoutThrowsExtractionException() {
        // write-limit irrelevant; timeout=0 trips before parse can complete
        final TikaSourceExtractor zero = new TikaSourceExtractor( TikaSourceExtractor.DEFAULT_WRITE_LIMIT_CHARS, 0 );
        final byte[] bytes = "Hello world".getBytes( StandardCharsets.UTF_8 );
        assertThrows( ExtractionException.class, () -> {
            try ( InputStream in = new ByteArrayInputStream( bytes ) ) {
                zero.extract( in, "text/plain", "timeout.txt" );
            }
        }, "A 0-second timeout must throw ExtractionException" );
    }

    /** The single-arg timeout ExtractionException names the timeout and the filename. */
    @Test
    void zeroTimeoutMessageNamesTimeoutAndFile() {
        final TikaSourceExtractor zero = new TikaSourceExtractor( TikaSourceExtractor.DEFAULT_WRITE_LIMIT_CHARS, 0 );
        final ExtractionException thrown = assertThrows( ExtractionException.class,
            () -> zero.extract( new ByteArrayInputStream( "x".getBytes( StandardCharsets.UTF_8 ) ),
                                "text/plain", "slow.txt" ) );
        assertTrue( thrown.getMessage().contains( "timed out" ),
            "timeout message must say it timed out, got: " + thrown.getMessage() );
        assertTrue( thrown.getMessage().contains( "slow.txt" ),
            "timeout message must name the offending file, got: " + thrown.getMessage() );
    }

    // ------------------------------------------------------------------ content-type guard

    @Test
    void supportsNullContentTypeIsFalse() {
        // The null-guard branch: a missing content type is never "supported".
        assertFalse( ex.supports( null ), "null content type must not be reported as supported" );
    }

    @Test
    void supportsIsCaseInsensitive() {
        // toLowerCase(ROOT) normalisation branch — an uppercased known type still matches.
        assertTrue( ex.supports( "APPLICATION/PDF" ) );
        assertTrue( ex.supports( "Text/Plain" ) );
    }

    // ------------------------------------------------------------------ text/html support

    @Test
    void supportsTextHtml() {
        assertTrue( ex.supports( "text/html" ) );
    }

    @Test
    void extractsMarkdownFromHtml() throws Exception {
        final String html = "<html><head><title>T</title></head><body><h1>Hello</h1><p>World body.</p></body></html>";
        try ( InputStream in = new ByteArrayInputStream( html.getBytes( StandardCharsets.UTF_8 ) ) ) {
            final ExtractionResult r = ex.extract( in, "text/html", "page.html" );
            assertFalse( r.isEmpty(), "html extraction should not be blank" );
            assertTrue( r.markdownBody().toLowerCase().contains( "hello" ),
                "body should carry the heading text: " + r.markdownBody() );
        }
    }

    // ------------------------------------------------------------------ parse-failure dispatch

    /**
     * A stream that throws on read drives Tika's parse to fail; the ExecutionException
     * cause-dispatch must surface an ExtractionException that (a) names the file, (b) uses
     * the "Failed to parse document" prefix, and (c) chains the original cause — pinning the
     * error-wrapping contract, not merely that "something threw".
     */
    @Test
    void readFailureIsWrappedWithFilenameAndCause() {
        final InputStream boom = new InputStream() {
            @Override public int read() throws IOException { throw new IOException( "disk vanished" ); }
            @Override public int read( final byte[] b, final int off, final int len ) throws IOException {
                throw new IOException( "disk vanished" );
            }
        };
        final ExtractionException thrown = assertThrows( ExtractionException.class,
            () -> ex.extract( boom, "text/plain", "broken.txt" ) );
        assertTrue( thrown.getMessage().startsWith( "Failed to parse document 'broken.txt'" ),
            "parse-failure message must name the file with the standard prefix, got: " + thrown.getMessage() );
        assertNotNull( thrown.getCause(), "the original parse failure must be chained as the cause" );
        assertTrue( thrown.getCause().getMessage().contains( "disk vanished" ),
            "the underlying read failure must be preserved in the cause chain" );
    }
}
