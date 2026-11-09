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
package com.wikantik.drift;

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.frontmatter.FrontmatterParseException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.managers.PageManager;
import com.wikantik.frontmatter.schema.SchemaDrivenFrontmatterValidator;
import com.wikantik.frontmatter.schema.ValidationCtx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Corpus-wide drift sweep: runs the schema validator over every page,
 * persisting aggregate {@code (family, code, severity, count)}
 * snapshots via {@link DriftSnapshotRepository}. Single-flight: one sweep at a time.
 *
 * <p>Family {@code frontmatter} uses the validator's violation codes plus {@code yaml.parse}
 * for whole-block parse failures (same code the save path emits). The former {@code shacl} family
 * (conformance of the retired Knowledge Graph's edge shapes) is gone. The {@code drift_sweeps.shacl_checked}
 * column is kept (no migration) and written as {@code false}, but is no longer surfaced.</p>
 */
public final class DriftSweepService {

    private static final Logger LOG = LogManager.getLogger( DriftSweepService.class );

    /** A sweep was requested while another is in flight. */
    public static final class SweepAlreadyRunningException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public SweepAlreadyRunningException() { super( "a drift sweep is already running" ); }
    }

    /** What one completed sweep produced (already persisted). */
    public record SweepOutcome( long sweepId, int pagesScanned, long durationMs,
                                List< DriftCount > counts ) {}

    /** Live, ephemeral progress of an in-flight sweep. Idle: (false, null, 0, 0). */
    public record SweepProgress( boolean running, String phase, int pagesScanned, int totalPages ) {}

    private record CountKey( String family, String code, String severity ) {}

    private final PageManager pageManager;
    private final SchemaDrivenFrontmatterValidator validator;
    private final ValidationCtx ctx;
    private final DriftSnapshotRepository repository;
    private final AtomicBoolean running = new AtomicBoolean( false );
    private volatile String progressPhase;
    private volatile int progressScanned;
    private volatile int progressTotal;
    /** Last sweep failure message; null when the last sweep succeeded or none failed since the latest trigger. */
    private volatile String lastError;

    public DriftSweepService( final PageManager pageManager,
                              final SchemaDrivenFrontmatterValidator validator,
                              final ValidationCtx ctx,
                              final DriftSnapshotRepository repository ) {
        this.pageManager = pageManager;
        this.validator = validator;
        this.ctx = ctx;
        this.repository = repository;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Snapshot of the current sweep's progress; idle values when no sweep is running. */
    public SweepProgress progress() {
        return new SweepProgress( running.get(), progressPhase, progressScanned, progressTotal );
    }

    /**
     * Message of the most recent sweep failure, or {@code null} if the last sweep succeeded or none has
     * failed since the latest trigger. Lets the dashboard surface a failed sweep instead of polling
     * forever — a failed sweep never advances {@code sweptAt}, so it is otherwise indistinguishable
     * from one still in flight.
     */
    public String lastError() {
        return lastError;
    }

    /** Read access for the REST layer — same repository the sweep persists into. */
    public DriftSnapshotRepository repository() {
        return repository;
    }

    /** Runs one sweep synchronously and persists it. */
    public SweepOutcome runSweep( final String triggeredBy ) {
        if ( !running.compareAndSet( false, true ) ) {
            throw new SweepAlreadyRunningException();
        }
        try {
            final long startedAt = System.currentTimeMillis();
            final java.time.Instant sweepStart = Instant.ofEpochMilli( startedAt );
            this.progressPhase = "frontmatter";
            this.progressScanned = 0;
            this.progressTotal = 0;
            final Map< CountKey, Integer > counts = new LinkedHashMap<>();
            final int pagesScanned = sweepFrontmatter( counts );
            this.progressPhase = "persisting";

            final long durationMs = System.currentTimeMillis() - startedAt;
            final List< DriftCount > rows = counts.entrySet().stream()
                    .map( e -> new DriftCount( e.getKey().family(), e.getKey().code(),
                            e.getKey().severity(), e.getValue() ) )
                    .toList();
            final long sweepId = repository.insertSweep(
                    sweepStart, pagesScanned, durationMs, triggeredBy, false, rows );
            LOG.info( "drift sweep complete (id={}, trigger={}, pages={}, codes={})",
                    sweepId, triggeredBy, pagesScanned, rows.size() );
            this.lastError = null;
            return new SweepOutcome( sweepId, pagesScanned, durationMs, rows );
        } catch ( final RuntimeException e ) {
            // Record the failure so /status can surface it — a failed sweep never advances
            // sweptAt, so the dashboard would otherwise poll forever. Rethrow unchanged.
            this.lastError = e.getMessage();
            throw e;
        } finally {
            this.progressPhase = null;
            this.progressScanned = 0;
            this.progressTotal = 0;
            running.set( false );
        }
    }

    /**
     * Starts a sweep on a daemon thread. The busy check is best-effort: concurrent callers can
     * both pass it and start threads, but {@link #runSweep}'s atomic guard ensures at most one
     * sweep actually runs (the loser is logged and discarded).
     *
     * @throws SweepAlreadyRunningException if a sweep is already known to be running
     */
    public void triggerAsync( final String triggeredBy ) {
        // Clear any prior failure the instant a new sweep is accepted, so a client polling /status
        // during the brief pre-running window never mistakes a stale error for this sweep's outcome.
        this.lastError = null;
        if ( running.get() ) {
            throw new SweepAlreadyRunningException();
        }
        final Thread t = new Thread( () -> {
            // runSweep's finally releases the running flag on ALL exits (including Error),
            // so nothing here can strand the guard.
            try {
                runSweep( triggeredBy );
            } catch ( final SweepAlreadyRunningException e ) {
                LOG.info( "drift sweep skipped — already running" );
            } catch ( final RuntimeException e ) {
                LOG.warn( "drift sweep failed: {}", e.getMessage(), e );
            }
        }, "wikantik-drift-sweep" );
        t.setDaemon( true );
        t.start();
    }

    /** Live offender list for one code — never persisted, always current. */
    public List< PageViolation > currentPageList( final String family, final String code ) {
        final List< PageViolation > out = new ArrayList<>();
        forEachParsedPage( "drift drill-down", total -> { }, ( name, parsedOrNull, parseError ) -> {
            if ( parseError != null ) {
                if ( "yaml.parse".equals( code ) ) {
                    out.add( new PageViolation( name, "__yaml__", "ERROR", "yaml.parse",
                            parseError.getMessage(), null ) );
                }
                return;
            }
            if ( parsedOrNull == null ) {
                return; // page without frontmatter — nothing to validate
            }
            for ( final FieldViolation v : validator.validate( parsedOrNull.metadata(), ctx ) ) {
                if ( v.code().equals( code ) ) {
                    out.add( new PageViolation( name, v.field(), v.severity().name(), v.code(),
                            v.message(), v.suggestion() ) );
                }
            }
        } );
        return out;
    }

    private int sweepFrontmatter( final Map< CountKey, Integer > counts ) {
        final int[] scanned = { 0 };
        forEachParsedPage( "drift sweep", total -> this.progressTotal = total,
                ( name, parsedOrNull, parseError ) -> {
            scanned[ 0 ]++;
            this.progressScanned = scanned[ 0 ];
            if ( parseError != null ) {
                bump( counts, "frontmatter", "yaml.parse", "ERROR" );
                return;
            }
            if ( parsedOrNull == null ) {
                return; // no frontmatter block — nothing to validate, but the page was scanned
            }
            for ( final FieldViolation v : validator.validate( parsedOrNull.metadata(), ctx ) ) {
                bump( counts, "frontmatter", v.code(), v.severity().name() );
            }
        } );
        return scanned[ 0 ];
    }

    @FunctionalInterface
    private interface PageVisitor {
        /** parseError != null → strict parse failed; parsedOrNull == null && parseError == null → no frontmatter. */
        void visit( String name, ParsedPage parsedOrNull, FrontmatterParseException parseError );
    }

    private void forEachParsedPage( final String context,
                                    final java.util.function.IntConsumer onEnumerated,
                                    final PageVisitor visitor ) {
        final Collection< Page > pages;
        try {
            pages = pageManager.getAllPages();
        } catch ( final ProviderException e ) {
            LOG.warn( "{}: page enumeration failed: {}", context, e.getMessage(), e );
            throw new IllegalStateException( context + ": page enumeration failed", e );
        }
        onEnumerated.accept( pages.size() );
        for ( final Page page : pages ) {
            final String name = page.getName();
            final String text;
            try {
                text = pageManager.getPureText( page );
            } catch ( final RuntimeException e ) {
                LOG.warn( "{}: failed to read '{}', skipping: {}", context, name, e.getMessage() );
                continue;
            }
            if ( text == null || text.isEmpty()
                    || ( !text.startsWith( "---\n" ) && !text.startsWith( "---\r\n" ) ) ) {
                visitor.visit( name, null, null );
                continue;
            }
            try {
                visitor.visit( name, FrontmatterParser.parseStrict( text ), null );
            } catch ( final FrontmatterParseException e ) {
                visitor.visit( name, null, e );
            }
        }
    }

    private static void bump( final Map< CountKey, Integer > counts, final String family,
                              final String code, final String severity ) {
        counts.merge( new CountKey( family, code, severity ), 1, Integer::sum );
    }
}
