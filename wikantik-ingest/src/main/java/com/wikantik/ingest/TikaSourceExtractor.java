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

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.ToXMLContentHandler;
import org.apache.tika.sax.WriteOutContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Extracts markdown content from a document stream using Apache Tika.
 * Parses to XHTML via Tika's {@link AutoDetectParser} and then converts
 * the XHTML to markdown using flexmark-html2md-converter.
 *
 * <p>Two resource bounds are applied to defend against malicious or pathological inputs:
 * <ol>
 *   <li><b>Write limit</b>: caps the number of characters Tika writes via
 *       {@link WriteOutContentHandler}. When reached, Tika throws
 *       {@link WriteLimitReachedException}; this extractor catches it,
 *       logs a warning, and returns the partial (truncated) content.
 *       A truncated result is acceptable; an OOM is not.</li>
 *   <li><b>Extraction timeout</b>: {@code parser.parse()} runs on its own daemon thread; if
 *       it does not complete within the configured timeout the thread is interrupted and an
 *       {@link ExtractionException} (chaining the {@link TimeoutException}) is thrown. Tika
 *       parsing of a pathological document can be CPU-bound and simply ignore interruption, so
 *       the worker thread may keep running indefinitely after the timeout fires — being a
 *       daemon thread at least means it can't block JVM shutdown, but repeated timeouts (e.g. a
 *       connector sync walking a folder of bad documents) would otherwise still accumulate an
 *       unbounded number of these permanently-running orphans. Concurrent extractions
 *       themselves are <em>not</em> bounded (normal concurrent uploads/syncs must not be
 *       rejected); only orphaned (still running after a timeout + cancel) workers are counted,
 *       and once {@link #MAX_STUCK_EXTRACTIONS} of those are outstanding, further calls fail
 *       fast with {@link ExtractionException} instead of piling on more.</li>
 * </ol>
 */
public class TikaSourceExtractor implements SourceExtractor {

    private static final Logger LOG = LogManager.getLogger( TikaSourceExtractor.class );

    /** Default maximum number of characters Tika is allowed to write (~10 MB of text). */
    public static final int DEFAULT_WRITE_LIMIT_CHARS = 10_000_000;

    /** Default wall-clock timeout for a single parse call, in seconds. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /**
     * Maximum number of <em>orphaned</em> parses — timed out, cancelled, but still running
     * because the parse is CPU-bound and ignoring interruption — allowed to accumulate across
     * every {@link TikaSourceExtractor} instance (extractors are constructed per-use — see call
     * sites — so the bound lives on the class, not the instance). This is not a concurrency
     * limit: healthy parses (including many running at once, e.g. concurrent uploads or several
     * connector syncs) are never rejected. Only once this many parses are confirmed stuck do
     * further calls fail fast with {@link ExtractionException}, rather than accumulating an
     * unbounded number of permanently-running orphan threads.
     */
    static final int MAX_STUCK_EXTRACTIONS = 8;

    private static final ThreadFactory DAEMON_THREAD_FACTORY = new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread( final Runnable r ) {
            final Thread t = new Thread( r, "tika-extract-" + count.incrementAndGet() );
            t.setDaemon( true );
            return t;
        }
    };

    /**
     * One new daemon thread per extraction — deliberately unbounded (concurrency itself is not
     * the resource being guarded; see {@link #MAX_STUCK_EXTRACTIONS}). Threads are daemon and
     * descriptively named so a stuck one shows up in a thread dump as {@code tika-extract-N}
     * instead of blocking JVM shutdown anonymously.
     */
    private static final ExecutorService PER_CALL_EXECUTOR = Executors.newThreadPerTaskExecutor( DAEMON_THREAD_FACTORY );

    /** Count of parses currently known to be orphaned (timed out + still running after {@code
     *  cancel(true)}). Incremented when a timeout is confirmed stuck; decremented — at most once,
     *  guarded by the task's own flag — if and when that orphan eventually finishes on its own.
     *  Package-private so tests can observe/await it draining. */
    static final AtomicInteger STUCK_WORKERS = new AtomicInteger();

    private static final int TASK_RUNNING  = 0;
    private static final int TASK_STUCK    = 1;
    private static final int TASK_FINISHED = 2;

    /** MIME types supported in v1. Case-insensitive. */
    private static final Set< String > SUPPORTED_TYPES = Set.of(
        "application/pdf",
        "text/plain",
        "text/markdown",
        "text/x-markdown",
        "text/html",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    private final int writeLimitChars;
    private final int timeoutSeconds;

    /** No-arg constructor: uses {@link #DEFAULT_WRITE_LIMIT_CHARS} and {@link #DEFAULT_TIMEOUT_SECONDS}. */
    public TikaSourceExtractor() {
        this( DEFAULT_WRITE_LIMIT_CHARS, DEFAULT_TIMEOUT_SECONDS );
    }

    /**
     * Constructor with explicit resource bounds.
     *
     * @param writeLimitChars maximum characters Tika may write; documents exceeding this
     *                        are truncated (partial result returned, no exception thrown)
     * @param timeoutSeconds  wall-clock seconds allowed for a single parse; exceeded
     *                        parsing is aborted and an {@link ExtractionException} thrown
     */
    public TikaSourceExtractor( final int writeLimitChars, final int timeoutSeconds ) {
        this.writeLimitChars = writeLimitChars;
        this.timeoutSeconds  = timeoutSeconds;
    }

    @Override
    public boolean supports( final String contentType ) {
        return contentType != null && SUPPORTED_TYPES.contains( contentType.toLowerCase( Locale.ROOT ) );
    }

    @Override
    public ExtractionResult extract( final InputStream source, final String contentType, final String filename )
            throws ExtractionException {

        final Parser parser  = createParser();
        final ToXMLContentHandler xhtml = new ToXMLContentHandler();
        // WriteOutContentHandler decorates xhtml: it forwards SAX events until the
        // write limit is reached, at which point it throws WriteLimitReachedException.
        final WriteOutContentHandler bounded = new WriteOutContentHandler( xhtml, writeLimitChars );
        final Metadata md = new Metadata();

        if ( filename != null ) {
            md.set( TikaCoreProperties.RESOURCE_NAME_KEY, filename );
        }
        if ( contentType != null ) {
            md.set( HttpHeaders.CONTENT_TYPE, contentType );
        }

        final boolean truncated = parseWithTimeout( parser, source, bounded, md, filename, contentType );

        // xhtml.toString() returns whatever was written before truncation (or the full content).
        final String rawXhtml = xhtml.toString();
        final String markdown  = FlexmarkHtmlConverter.builder().build().convert( rawXhtml ).strip();
        final String title     = md.get( TikaCoreProperties.TITLE );

        final Map< String, String > meta = truncated
            ? Map.of( "wikantik.ingest.truncated", "true" )
            : Map.of();

        return new ExtractionResult( markdown, title, meta );
    }

    /** Factory seam for the Tika parser used by {@link #extract}. Overridable so tests can inject
     *  a parser that exercises specific timeout/failure behavior without needing an actual
     *  pathological document. */
    protected Parser createParser() {
        return new AutoDetectParser();
    }

    /** Runs the Tika parse on its own daemon thread, enforcing {@link #timeoutSeconds}. Returns
     *  {@code true} iff the write-limit truncated the output. Rejects up front only when {@link
     *  #MAX_STUCK_EXTRACTIONS} parses are already confirmed orphaned — never merely because many
     *  parses are running concurrently. */
    private boolean parseWithTimeout( final Parser parser, final InputStream source,
                                      final WriteOutContentHandler bounded, final Metadata md,
                                      final String filename, final String contentType )
            throws ExtractionException {
        if ( STUCK_WORKERS.get() >= MAX_STUCK_EXTRACTIONS ) {
            try {
                source.close();
            } catch ( final IOException ioe ) {
                LOG.warn( "Failed to close source stream for '{}' after extractor saturation: {}",
                    filename, ioe.getMessage(), ioe );
            }
            LOG.warn( "Tika extraction rejected for '{}' (type={}): extractor saturated "
                + "({} stuck parses already outstanding)", filename, contentType, STUCK_WORKERS.get() );
            throw new ExtractionException( "extractor saturated: " + STUCK_WORKERS.get()
                + " stuck parses, rejected '" + filename + "'" );
        }

        boolean truncated = false;
        final AtomicReference< Thread > worker = new AtomicReference<>();
        // Handshake between the timeout path and the task's finally, decided by a single CAS so
        // neither side can miss the other: the timeout path claims RUNNING -> STUCK (and counts
        // it), the task swaps in FINISHED on exit and un-counts only if it saw STUCK. A check-
        // then-set here would leak a count whenever the parse finished in between — and eight
        // leaked counts would reject every extraction until restart.
        final AtomicInteger state = new AtomicInteger( TASK_RUNNING );

        final Future< Void > future = PER_CALL_EXECUTOR.submit( () -> {
            worker.set( Thread.currentThread() );
            try {
                try ( source ) {
                    parser.parse( source, bounded, md, new ParseContext() );
                }
            } finally {
                if ( state.getAndSet( TASK_FINISHED ) == TASK_STUCK ) {
                    STUCK_WORKERS.decrementAndGet();
                }
            }
            return null;
        } );

        try {
            future.get( timeoutSeconds, TimeUnit.SECONDS );
        } catch ( final TimeoutException e ) {
            future.cancel( true );
            LOG.warn( "Tika extraction timed out after {}s for '{}' (type={})",
                timeoutSeconds, filename, contentType, e );
            final Thread w = worker.get();
            // A task cancelled before it started never runs its finally, so only a started
            // worker may be counted. (One that starts just after this read goes uncounted —
            // that only loosens the cap, it can never leak a count.)
            if ( w != null && state.compareAndSet( TASK_RUNNING, TASK_STUCK ) ) {
                STUCK_WORKERS.incrementAndGet();
                LOG.warn( "Tika extraction worker '{}' for '{}' is still running after cancellation — "
                    + "the parse is likely CPU-bound and ignoring interruption; it will keep "
                    + "running until it finishes on its own ({} stuck parse(s) now outstanding)",
                    w.getName(), filename, STUCK_WORKERS.get() );
            }
            throw new ExtractionException(
                "extraction timed out after " + timeoutSeconds + "s for '" + filename + "'", e );
        } catch ( final ExecutionException e ) {
            truncated = handleParseExecutionException( e, filename, contentType );
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new ExtractionException( "Extraction interrupted for '" + filename + "'", e );
        }
        return truncated;
    }

    /** Classifies the cause of a failed parse. Returns {@code true} (truncated, no throw) only for the
     *  write-limit case; every other cause is rethrown wrapped in an {@link ExtractionException}. */
    private boolean handleParseExecutionException( final ExecutionException e, final String filename,
                                                    final String contentType ) throws ExtractionException {
        final Throwable cause = e.getCause();
        // WriteLimitReachedException is a SAXException — detect it before re-throwing
        if ( WriteLimitReachedException.isWriteLimitReached( cause ) ) {
            LOG.warn( "Tika write limit ({} chars) reached for '{}' (type={}): returning truncated content",
                writeLimitChars, filename, contentType );
            return true;
        }
        if ( cause instanceof IOException ioe ) {
            LOG.warn( "Tika parse failed (IO) for '{}' (type={}): {}", filename, contentType, ioe.getMessage() );
            throw new ExtractionException( "Failed to parse document '" + filename + "': " + ioe.getMessage(), ioe );
        }
        if ( cause instanceof SAXException saxe ) {
            LOG.warn( "Tika parse failed (SAX) for '{}' (type={}): {}", filename, contentType, saxe.getMessage() );
            throw new ExtractionException( "Failed to parse document '" + filename + "': " + saxe.getMessage(), saxe );
        }
        if ( cause instanceof TikaException te ) {
            LOG.warn( "Tika parse failed for '{}' (type={}): {}", filename, contentType, te.getMessage() );
            throw new ExtractionException( "Failed to parse document '" + filename + "': " + te.getMessage(), te );
        }
        final String msg = cause != null ? cause.getMessage() : e.getMessage();
        LOG.warn( "Tika parse failed (unexpected) for '{}' (type={}): {}", filename, contentType, msg );
        throw new ExtractionException( "Failed to parse document '" + filename + "': " + msg,
            cause != null ? cause : e );
    }
}
