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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 *   <li><b>Extraction timeout</b>: {@code parser.parse()} runs on a dedicated
 *       thread; if it does not complete within the configured timeout the thread
 *       is interrupted, the executor is shut down, and an {@link ExtractionException}
 *       is thrown.</li>
 * </ol>
 */
public class TikaSourceExtractor implements SourceExtractor {

    private static final Logger LOG = LogManager.getLogger( TikaSourceExtractor.class );

    /** Default maximum number of characters Tika is allowed to write (~10 MB of text). */
    public static final int DEFAULT_WRITE_LIMIT_CHARS = 10_000_000;

    /** Default wall-clock timeout for a single parse call, in seconds. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /** MIME types supported in v1. Case-insensitive. */
    private static final Set< String > SUPPORTED_TYPES = Set.of(
        "application/pdf",
        "text/plain",
        "text/markdown",
        "text/x-markdown",
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
        if ( contentType == null ) { return false; }
        return SUPPORTED_TYPES.contains( contentType.toLowerCase( Locale.ROOT ) );
    }

    @Override
    public ExtractionResult extract( final InputStream source, final String contentType, final String filename )
            throws ExtractionException {

        final AutoDetectParser parser  = new AutoDetectParser();
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

        boolean truncated = false;

        final ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            final Future< Void > future = exec.submit( () -> {
                try ( source ) {
                    parser.parse( source, bounded, md, new ParseContext() );
                }
                return null;
            } );

            try {
                future.get( timeoutSeconds, TimeUnit.SECONDS );
            } catch ( final TimeoutException e ) {
                future.cancel( true );
                LOG.warn( "Tika extraction timed out after {}s for '{}' (type={})",
                    timeoutSeconds, filename, contentType );
                throw new ExtractionException(
                    "extraction timed out after " + timeoutSeconds + "s for '" + filename + "'" );
            } catch ( final ExecutionException e ) {
                final Throwable cause = e.getCause();
                // WriteLimitReachedException is a SAXException — detect it before re-throwing
                if ( WriteLimitReachedException.isWriteLimitReached( cause ) ) {
                    truncated = true;
                    LOG.warn( "Tika write limit ({} chars) reached for '{}' (type={}): returning truncated content",
                        writeLimitChars, filename, contentType );
                } else if ( cause instanceof IOException ioe ) {
                    LOG.warn( "Tika parse failed (IO) for '{}' (type={}): {}", filename, contentType, ioe.getMessage() );
                    throw new ExtractionException( "Failed to parse document '" + filename + "': " + ioe.getMessage(), ioe );
                } else if ( cause instanceof SAXException saxe ) {
                    LOG.warn( "Tika parse failed (SAX) for '{}' (type={}): {}", filename, contentType, saxe.getMessage() );
                    throw new ExtractionException( "Failed to parse document '" + filename + "': " + saxe.getMessage(), saxe );
                } else if ( cause instanceof TikaException te ) {
                    LOG.warn( "Tika parse failed for '{}' (type={}): {}", filename, contentType, te.getMessage() );
                    throw new ExtractionException( "Failed to parse document '" + filename + "': " + te.getMessage(), te );
                } else {
                    final String msg = cause != null ? cause.getMessage() : e.getMessage();
                    LOG.warn( "Tika parse failed (unexpected) for '{}' (type={}): {}", filename, contentType, msg );
                    throw new ExtractionException( "Failed to parse document '" + filename + "': " + msg,
                        cause != null ? cause : e );
                }
            } catch ( final InterruptedException e ) {
                Thread.currentThread().interrupt();
                throw new ExtractionException( "Extraction interrupted for '" + filename + "'", e );
            }
        } finally {
            exec.shutdownNow();
        }

        // xhtml.toString() returns whatever was written before truncation (or the full content).
        final String rawXhtml = xhtml.toString();
        final String markdown  = FlexmarkHtmlConverter.builder().build().convert( rawXhtml ).strip();
        final String title     = md.get( TikaCoreProperties.TITLE );

        final Map< String, String > meta = truncated
            ? Map.of( "wikantik.ingest.truncated", "true" )
            : Map.of();

        return new ExtractionResult( markdown, title, meta );
    }
}
