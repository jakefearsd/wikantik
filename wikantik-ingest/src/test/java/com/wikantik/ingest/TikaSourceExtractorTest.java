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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.mime.MediaType;
import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

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

    // ------------------------------------------------------------------ stuck-worker / orphan-thread defects
    //
    // These simulate a CPU-bound parse that ignores Thread.interrupt() (a pathological document,
    // e.g. a decompression bomb) via a busy-spin on a flag — deliberately NOT Thread.sleep(), which
    // is itself interruptible and would not reproduce the defect.

    /** A {@link Parser} that spins on a flag instead of returning, ignoring interruption. Counts
     *  down {@code entered} as soon as it starts spinning so callers can know the worker thread is
     *  actually running before they act on it, records that thread for inspection, and counts down
     *  {@code finished} once released — used instead of {@link Thread#join} for detecting
     *  completion, because the worker runs on a shared {@code ThreadPoolExecutor}: the underlying
     *  pool thread is reused and never terminates just because one task finished. */
    private static final class BusyLoopParser implements Parser {
        private final AtomicBoolean release;
        private final CountDownLatch entered;
        private final CountDownLatch finished = new CountDownLatch( 1 );
        private final AtomicReference< Thread > workerThread = new AtomicReference<>();

        BusyLoopParser( final AtomicBoolean release, final CountDownLatch entered ) {
            this.release = release;
            this.entered = entered;
        }

        @Override
        public Set< MediaType > getSupportedTypes( final ParseContext context ) {
            return Set.of();
        }

        @Override
        public void parse( final InputStream stream, final ContentHandler handler,
                            final Metadata metadata, final ParseContext context ) {
            workerThread.set( Thread.currentThread() );
            entered.countDown();
            while ( !release.get() ) {
                // Deliberately not interruptible: no sleep/wait/blocking call, just a spin —
                // reproduces a CPU-bound Tika parse that Thread.interrupt() cannot stop.
            }
            finished.countDown();
        }
    }

    /** A {@link Parser} that counts down {@code started} as soon as it is entered, then blocks on
     *  {@code gate} (an ordinary, interruptible wait) until released — used to prove several
     *  extractions ran genuinely concurrently, as opposed to the busy-loop parser above which
     *  simulates one that never yields the CPU-bound thread back. */
    private static final class LatchGatedParser implements Parser {
        private final CountDownLatch started;
        private final CountDownLatch gate;

        LatchGatedParser( final CountDownLatch started, final CountDownLatch gate ) {
            this.started = started;
            this.gate = gate;
        }

        @Override
        public Set< MediaType > getSupportedTypes( final ParseContext context ) {
            return Set.of();
        }

        @Override
        public void parse( final InputStream stream, final ContentHandler handler,
                            final Metadata metadata, final ParseContext context ) {
            started.countDown();
            try {
                assertTrue( gate.await( 30, TimeUnit.SECONDS ), "test gate was never released" );
            } catch ( final InterruptedException e ) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A trivially successful {@link Parser} — used to prove the extractor works normally again
     *  once its stuck-worker count has drained back to zero. */
    private static final class ImmediateParser implements Parser {
        @Override
        public Set< MediaType > getSupportedTypes( final ParseContext context ) {
            return Set.of();
        }

        @Override
        public void parse( final InputStream stream, final ContentHandler handler,
                            final Metadata metadata, final ParseContext context ) {
            // no-op: succeeds immediately
        }
    }

    /** A {@link Parser} whose {@code parse} always fails with a {@link SAXException} — used to
     *  pin the SAX branch of the parse-failure cause dispatch. */
    private static final class SaxFailingParser implements Parser {
        @Override
        public Set< MediaType > getSupportedTypes( final ParseContext context ) {
            return Set.of();
        }

        @Override
        public void parse( final InputStream stream, final ContentHandler handler,
                            final Metadata metadata, final ParseContext context ) throws SAXException {
            throw new SAXException( "malformed markup" );
        }
    }

    /** A {@link Parser} whose {@code parse} always fails with a {@link TikaException} — used to
     *  pin the Tika-specific branch of the parse-failure cause dispatch. */
    private static final class TikaFailingParser implements Parser {
        @Override
        public Set< MediaType > getSupportedTypes( final ParseContext context ) {
            return Set.of();
        }

        @Override
        public void parse( final InputStream stream, final ContentHandler handler,
                            final Metadata metadata, final ParseContext context ) throws TikaException {
            throw new TikaException( "unsupported format" );
        }
    }

    /** A {@link Parser} whose {@code parse} fails with a cause outside the three explicitly
     *  classified ones (IO/SAX/Tika) — used to pin the fallback "unexpected" branch. */
    private static final class UnexpectedFailingParser implements Parser {
        @Override
        public Set< MediaType > getSupportedTypes( final ParseContext context ) {
            return Set.of();
        }

        @Override
        public void parse( final InputStream stream, final ContentHandler handler,
                            final Metadata metadata, final ParseContext context ) {
            throw new IllegalStateException( "totally unexpected" );
        }
    }

    /** Extractor whose {@link #createParser()} returns an injected parser instead of a real
     *  {@link org.apache.tika.parser.AutoDetectParser} — the seam this test suite uses to simulate
     *  pathological parse behavior without needing an actual malformed document. */
    private static final class InjectableExtractor extends TikaSourceExtractor {
        private final Parser injected;

        InjectableExtractor( final Parser injected, final int writeLimitChars, final int timeoutSeconds ) {
            super( writeLimitChars, timeoutSeconds );
            this.injected = injected;
        }

        @Override
        protected Parser createParser() {
            return injected;
        }
    }

    /** Flips the release flag, waits (bounded) for the busy loop to actually have exited, and
     *  waits for the (per-task-guarded) stuck-worker count to drain back to zero — so a test's
     *  simulated stuck parse never leaks into, or leaves a stale count for, a later test. Waits on
     *  {@code finished}, not {@link Thread#join}: the worker runs on a per-call daemon thread that
     *  this helper has no other handle on once the task itself has returned. */
    private static void release( final AtomicBoolean release, final CountDownLatch finished ) throws InterruptedException {
        release.set( true );
        assertTrue( finished.await( 5, TimeUnit.SECONDS ), "busy-loop parse must return once released" );
        awaitStuckWorkersDrained();
    }

    /** Polls (bounded) for {@link TikaSourceExtractor#STUCK_WORKERS} to return to zero. Test order
     *  is randomized by surefire, so every test that increments this static counter must drain it
     *  back to zero before finishing, rather than assuming a fresh baseline. */
    private static void awaitStuckWorkersDrained() throws InterruptedException {
        final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos( 5 );
        while ( TikaSourceExtractor.STUCK_WORKERS.get() != 0 && System.nanoTime() < deadlineNanos ) {
            Thread.sleep( 10 );
        }
        assertEquals( 0, TikaSourceExtractor.STUCK_WORKERS.get(), "stuck-worker count must drain back to zero" );
    }

    @Test
    void timeoutExceptionCauseIsChainedNotDropped() throws Exception {
        final AtomicBoolean release = new AtomicBoolean( false );
        final CountDownLatch entered = new CountDownLatch( 1 );
        final BusyLoopParser busy = new BusyLoopParser( release, entered );
        final TikaSourceExtractor stuck = new InjectableExtractor( busy, TikaSourceExtractor.DEFAULT_WRITE_LIMIT_CHARS, 1 );

        try {
            final ExtractionException thrown = assertThrows( ExtractionException.class, () -> {
                try ( InputStream in = new ByteArrayInputStream( "x".getBytes( StandardCharsets.UTF_8 ) ) ) {
                    stuck.extract( in, "text/plain", "stuck.txt" );
                }
            } );
            assertNotNull( thrown.getCause(), "timeout must chain a cause, not drop it" );
            assertInstanceOf( TimeoutException.class, thrown.getCause(),
                "the chained cause must be the TimeoutException, got: " + thrown.getCause() );
        } finally {
            assertTrue( entered.await( 5, TimeUnit.SECONDS ), "worker never started" );
            release( release, busy.finished );
        }
    }

    @Test
    void stuckWorkerThreadIsDaemonAndDescriptivelyNamed() throws Exception {
        final AtomicBoolean release = new AtomicBoolean( false );
        final CountDownLatch entered = new CountDownLatch( 1 );
        final BusyLoopParser busy = new BusyLoopParser( release, entered );
        final TikaSourceExtractor stuck = new InjectableExtractor( busy, TikaSourceExtractor.DEFAULT_WRITE_LIMIT_CHARS, 1 );

        try {
            assertThrows( ExtractionException.class, () -> {
                try ( InputStream in = new ByteArrayInputStream( "x".getBytes( StandardCharsets.UTF_8 ) ) ) {
                    stuck.extract( in, "text/plain", "stuck.txt" );
                }
            } );
            assertTrue( entered.await( 5, TimeUnit.SECONDS ), "worker never started" );
            final Thread worker = busy.workerThread.get();
            assertNotNull( worker, "the busy-loop parser must have recorded its worker thread" );
            assertTrue( worker.isDaemon(), "the Tika parse worker must be a daemon thread so it "
                + "cannot block JVM shutdown when it ignores interruption" );
            assertTrue( worker.getName().startsWith( "tika-extract-" ),
                "worker thread must be descriptively named, got: " + worker.getName() );
        } finally {
            release( release, busy.finished );
        }
    }

    /**
     * Healthy concurrent extractions must never be rejected just because many are in flight —
     * only confirmed-orphaned (timed-out-and-still-running) parses are bounded. Uses more callers
     * than the old (now-removed) fixed concurrency cap of 4, and more than {@link
     * TikaSourceExtractor#MAX_STUCK_EXTRACTIONS}, gated so they provably run at the same time
     * (all reach the parser before any is released) rather than merely queuing one after another.
     */
    @Test
    void healthyConcurrentExtractionsAreNeverRejectedByTheStuckWorkerCap() throws Exception {
        final int n = TikaSourceExtractor.MAX_STUCK_EXTRACTIONS + 2;
        final CountDownLatch allStarted = new CountDownLatch( n );
        final CountDownLatch releaseGate = new CountDownLatch( 1 );
        final Thread[] callers = new Thread[ n ];
        final Throwable[] failures = new Throwable[ n ];

        for ( int i = 0; i < n; i++ ) {
            final TikaSourceExtractor healthy = new InjectableExtractor(
                new LatchGatedParser( allStarted, releaseGate ), TikaSourceExtractor.DEFAULT_WRITE_LIMIT_CHARS, 30 );
            final int idx = i;
            callers[ i ] = new Thread( () -> {
                try ( InputStream in = new ByteArrayInputStream( "x".getBytes( StandardCharsets.UTF_8 ) ) ) {
                    healthy.extract( in, "text/plain", "healthy-" + idx + ".txt" );
                } catch ( final Throwable t ) {
                    failures[ idx ] = t;
                }
            }, "healthy-caller-" + i );
            callers[ i ].setDaemon( true );
            callers[ i ].start();
        }

        assertTrue( allStarted.await( 5, TimeUnit.SECONDS ),
            "all " + n + " healthy extractions must run concurrently — none may be rejected just "
                + "because many are in flight" );
        releaseGate.countDown();

        for ( final Thread caller : callers ) {
            caller.join( 5_000 );
            assertFalse( caller.isAlive(), "caller thread must have completed" );
        }
        for ( int i = 0; i < n; i++ ) {
            assertNull( failures[ i ], "healthy extraction " + i + " must not have been rejected/failed: " + failures[ i ] );
        }
        assertEquals( 0, TikaSourceExtractor.STUCK_WORKERS.get(), "no healthy extraction should ever count as stuck" );
    }

    /**
     * Once {@link TikaSourceExtractor#MAX_STUCK_EXTRACTIONS} parses are <em>confirmed</em> stuck
     * (each call's own timeout fired and its worker was still alive after {@code cancel(true)}),
     * a further call is rejected immediately, without ever submitting its parse. Once every stuck
     * worker is released and drains out, the extractor must work normally again.
     */
    @Test
    void furtherCallsAreRejectedOnceStuckCapIsReachedThenRecoverAfterDraining() throws Exception {
        final int bound = TikaSourceExtractor.MAX_STUCK_EXTRACTIONS;
        final AtomicBoolean release = new AtomicBoolean( false );
        final BusyLoopParser[] parsers = new BusyLoopParser[ bound ];
        final Thread[] callers = new Thread[ bound ];

        // Drive `bound` parses to a CONFIRMED timeout — each call's own short timeout fires and
        // its worker is still alive after cancel(true) — before the extractor is considered
        // saturated. This is a much stronger precondition than merely "N calls in flight".
        for ( int i = 0; i < bound; i++ ) {
            parsers[ i ] = new BusyLoopParser( release, new CountDownLatch( 1 ) );
            final TikaSourceExtractor stuck =
                new InjectableExtractor( parsers[ i ], TikaSourceExtractor.DEFAULT_WRITE_LIMIT_CHARS, 1 );
            callers[ i ] = new Thread( () -> {
                try ( InputStream in = new ByteArrayInputStream( "x".getBytes( StandardCharsets.UTF_8 ) ) ) {
                    stuck.extract( in, "text/plain", "stuck-" + Thread.currentThread().getName() + ".txt" );
                } catch ( final Exception ignored ) {
                    // Expected: each call times out once its own worker is confirmed stuck.
                }
            }, "saturation-caller-" + i );
            callers[ i ].setDaemon( true );
            callers[ i ].start();
        }

        try {
            for ( final Thread caller : callers ) {
                caller.join( 5_000 );
                assertFalse( caller.isAlive(), "caller thread must have returned once its own timeout fired" );
            }
            assertEquals( bound, TikaSourceExtractor.STUCK_WORKERS.get(),
                "all " + bound + " parses must be confirmed stuck before the extractor is considered saturated" );

            final CountDownLatch overflowEntered = new CountDownLatch( 1 );
            final TikaSourceExtractor overflow = new InjectableExtractor(
                new BusyLoopParser( release, overflowEntered ), TikaSourceExtractor.DEFAULT_WRITE_LIMIT_CHARS, 60 );
            final ExtractionException thrown = assertThrows( ExtractionException.class, () -> {
                try ( InputStream in = new ByteArrayInputStream( "x".getBytes( StandardCharsets.UTF_8 ) ) ) {
                    overflow.extract( in, "text/plain", "overflow.txt" );
                }
            } );
            assertTrue( thrown.getMessage().toLowerCase( Locale.ROOT ).contains( "saturated" ),
                "the bound-exceeded rejection must say the extractor is saturated, got: " + thrown.getMessage() );
            assertEquals( 1, overflowEntered.getCount(),
                "a saturated call must be rejected before its parse is ever submitted" );
        } finally {
            release.set( true );
            for ( final BusyLoopParser p : parsers ) {
                assertTrue( p.finished.await( 5, TimeUnit.SECONDS ), "busy-loop parse must return once released" );
            }
            awaitStuckWorkersDrained();
        }

        // Once every stuck worker has actually drained out, the extractor must work normally again.
        final TikaSourceExtractor recovered =
            new InjectableExtractor( new ImmediateParser(), TikaSourceExtractor.DEFAULT_WRITE_LIMIT_CHARS, 5 );
        assertDoesNotThrow( () -> {
            try ( InputStream in = new ByteArrayInputStream( "x".getBytes( StandardCharsets.UTF_8 ) ) ) {
                recovered.extract( in, "text/plain", "recovered.txt" );
            }
        }, "a new call must succeed once the stuck-worker count has drained back to zero" );
    }

    // ------------------------------------------------------------------ parse-failure cause dispatch (SAX/Tika/unexpected)

    @Test
    void saxParseFailureIsWrappedWithFilenameAndCause() {
        final TikaSourceExtractor extractor =
            new InjectableExtractor( new SaxFailingParser(), TikaSourceExtractor.DEFAULT_WRITE_LIMIT_CHARS, 5 );
        final ExtractionException thrown = assertThrows( ExtractionException.class, () -> {
            try ( InputStream in = new ByteArrayInputStream( "x".getBytes( StandardCharsets.UTF_8 ) ) ) {
                extractor.extract( in, "text/html", "bad.html" );
            }
        } );
        assertTrue( thrown.getMessage().startsWith( "Failed to parse document 'bad.html'" ),
            "got: " + thrown.getMessage() );
        assertTrue( thrown.getMessage().contains( "malformed markup" ) );
        assertInstanceOf( SAXException.class, thrown.getCause(), "the SAX cause must be chained" );
    }

    @Test
    void tikaParseFailureIsWrappedWithFilenameAndCause() {
        final TikaSourceExtractor extractor =
            new InjectableExtractor( new TikaFailingParser(), TikaSourceExtractor.DEFAULT_WRITE_LIMIT_CHARS, 5 );
        final ExtractionException thrown = assertThrows( ExtractionException.class, () -> {
            try ( InputStream in = new ByteArrayInputStream( "x".getBytes( StandardCharsets.UTF_8 ) ) ) {
                extractor.extract( in, "application/pdf", "bad.pdf" );
            }
        } );
        assertTrue( thrown.getMessage().startsWith( "Failed to parse document 'bad.pdf'" ),
            "got: " + thrown.getMessage() );
        assertTrue( thrown.getMessage().contains( "unsupported format" ) );
        assertInstanceOf( TikaException.class, thrown.getCause(), "the Tika cause must be chained" );
    }

    @Test
    void unexpectedParseFailureIsWrappedWithFilenameAndCause() {
        final TikaSourceExtractor extractor =
            new InjectableExtractor( new UnexpectedFailingParser(), TikaSourceExtractor.DEFAULT_WRITE_LIMIT_CHARS, 5 );
        final ExtractionException thrown = assertThrows( ExtractionException.class, () -> {
            try ( InputStream in = new ByteArrayInputStream( "x".getBytes( StandardCharsets.UTF_8 ) ) ) {
                extractor.extract( in, "text/plain", "weird.txt" );
            }
        } );
        assertTrue( thrown.getMessage().startsWith( "Failed to parse document 'weird.txt'" ),
            "got: " + thrown.getMessage() );
        assertTrue( thrown.getMessage().contains( "totally unexpected" ) );
        assertInstanceOf( IllegalStateException.class, thrown.getCause(),
            "a cause not classified as IO/SAX/Tika must still be chained, not dropped" );
    }

    // ------------------------------------------------------------------ waiting-thread interruption

    @Test
    void interruptedWhileWaitingIsWrappedAndInterruptStatusRestored() throws Exception {
        final TikaSourceExtractor extractor =
            new InjectableExtractor( new ImmediateParser(), TikaSourceExtractor.DEFAULT_WRITE_LIMIT_CHARS, 5 );
        Thread.currentThread().interrupt();
        try {
            final ExtractionException thrown = assertThrows( ExtractionException.class, () -> {
                try ( InputStream in = new ByteArrayInputStream( "x".getBytes( StandardCharsets.UTF_8 ) ) ) {
                    extractor.extract( in, "text/plain", "interrupted.txt" );
                }
            } );
            assertTrue( thrown.getMessage().contains( "interrupted" ), "got: " + thrown.getMessage() );
            assertTrue( thrown.getMessage().contains( "interrupted.txt" ), "got: " + thrown.getMessage() );
            assertTrue( Thread.currentThread().isInterrupted(),
                "Thread.interrupt() must be re-asserted on the caller, never silently swallowed" );
        } finally {
            // Clear the flag so it can't leak into later tests (surefire reuses the JVM thread).
            Thread.interrupted();
        }
    }

    // ------------------------------------------------------------------ saturation-rejection close failure

    /**
     * Once the stuck-worker cap is reached, an overflow call is rejected before its parse ever
     * runs — but the extractor still owns the caller's source stream and must attempt to close
     * it. A stream whose {@code close()} itself throws must not prevent (or replace) the
     * saturation {@link ExtractionException}; the close failure is logged, not propagated.
     */
    @Test
    void closeFailureDuringSaturationRejectionIsLoggedNotThrown() throws Exception {
        final int bound = TikaSourceExtractor.MAX_STUCK_EXTRACTIONS;
        final AtomicBoolean release = new AtomicBoolean( false );
        final BusyLoopParser[] parsers = new BusyLoopParser[ bound ];
        final Thread[] callers = new Thread[ bound ];

        for ( int i = 0; i < bound; i++ ) {
            parsers[ i ] = new BusyLoopParser( release, new CountDownLatch( 1 ) );
            final TikaSourceExtractor stuck =
                new InjectableExtractor( parsers[ i ], TikaSourceExtractor.DEFAULT_WRITE_LIMIT_CHARS, 1 );
            callers[ i ] = new Thread( () -> {
                try ( InputStream in = new ByteArrayInputStream( "x".getBytes( StandardCharsets.UTF_8 ) ) ) {
                    stuck.extract( in, "text/plain", "stuck-" + Thread.currentThread().getName() + ".txt" );
                } catch ( final Exception ignored ) {
                    // Expected: each call times out once its own worker is confirmed stuck.
                }
            }, "close-fail-saturation-caller-" + i );
            callers[ i ].setDaemon( true );
            callers[ i ].start();
        }

        try {
            for ( final Thread caller : callers ) {
                caller.join( 5_000 );
                assertFalse( caller.isAlive(), "caller thread must have returned once its own timeout fired" );
            }
            assertEquals( bound, TikaSourceExtractor.STUCK_WORKERS.get(),
                "all " + bound + " parses must be confirmed stuck before the extractor is considered saturated" );

            final AtomicBoolean closeAttempted = new AtomicBoolean( false );
            final InputStream failingToClose = new InputStream() {
                @Override public int read() { return -1; }
                @Override public void close() throws IOException {
                    closeAttempted.set( true );
                    throw new IOException( "close boom" );
                }
            };
            final TikaSourceExtractor overflow = new InjectableExtractor(
                new ImmediateParser(), TikaSourceExtractor.DEFAULT_WRITE_LIMIT_CHARS, 5 );
            final ExtractionException thrown = assertThrows( ExtractionException.class,
                () -> overflow.extract( failingToClose, "text/plain", "overflow-close.txt" ) );
            assertTrue( thrown.getMessage().toLowerCase( Locale.ROOT ).contains( "saturated" ),
                "got: " + thrown.getMessage() );
            assertTrue( closeAttempted.get(), "the source stream close() must still be attempted on rejection" );
        } finally {
            release.set( true );
            for ( final BusyLoopParser p : parsers ) {
                assertTrue( p.finished.await( 5, TimeUnit.SECONDS ), "busy-loop parse must return once released" );
            }
            awaitStuckWorkersDrained();
        }
    }
}
