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
import com.wikantik.ontology.OntologyShaclValidator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Corpus-wide drift sweep: runs the schema validator over every page and counts SHACL
 * conformance violations, persisting aggregate {@code (family, code, severity, count)}
 * snapshots via {@link DriftSnapshotRepository}. Single-flight: one sweep at a time.
 *
 * <p>Family {@code frontmatter} uses the validator's violation codes plus {@code yaml.parse}
 * for whole-block parse failures (same code the save path emits). Family {@code shacl} uses
 * the violated shape's property path as the code, severity {@code ERROR}.</p>
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
                                boolean shaclChecked, List< DriftCount > counts ) {}

    private record CountKey( String family, String code, String severity ) {}

    private final PageManager pageManager;
    private final SchemaDrivenFrontmatterValidator validator;
    private final ValidationCtx ctx;
    /** Null when the ontology subsystem is disabled — the shacl family is then skipped. */
    private final Supplier< List< OntologyShaclValidator.Violation > > shaclSource;
    private final DriftSnapshotRepository repository;
    private final AtomicBoolean running = new AtomicBoolean( false );

    public DriftSweepService( final PageManager pageManager,
                              final SchemaDrivenFrontmatterValidator validator,
                              final ValidationCtx ctx,
                              final Supplier< List< OntologyShaclValidator.Violation > > shaclSource,
                              final DriftSnapshotRepository repository ) {
        this.pageManager = pageManager;
        this.validator = validator;
        this.ctx = ctx;
        this.shaclSource = shaclSource;
        this.repository = repository;
    }

    public boolean isRunning() {
        return running.get();
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
            final Map< CountKey, Integer > counts = new LinkedHashMap<>();
            final int pagesScanned = sweepFrontmatter( counts );
            final boolean shaclChecked = sweepShacl( counts );

            final long durationMs = System.currentTimeMillis() - startedAt;
            final List< DriftCount > rows = counts.entrySet().stream()
                    .map( e -> new DriftCount( e.getKey().family(), e.getKey().code(),
                            e.getKey().severity(), e.getValue() ) )
                    .toList();
            final long sweepId = repository.insertSweep(
                    sweepStart, pagesScanned, durationMs, triggeredBy, shaclChecked, rows );
            LOG.info( "drift sweep complete (id={}, trigger={}, pages={}, codes={}, shacl={})",
                    sweepId, triggeredBy, pagesScanned, rows.size(), shaclChecked );
            return new SweepOutcome( sweepId, pagesScanned, durationMs, shaclChecked, rows );
        } finally {
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
        if ( "shacl".equals( family ) ) {
            if ( shaclSource == null ) {
                return List.of();
            }
            return shaclSource.get().stream()
                    .filter( v -> v.path().equals( code ) )
                    // For SHACL rows the shape's property path serves as both field and code —
                    // there is no finer-grained field for an edge-level violation.
                    .map( v -> new PageViolation( v.focusNode(), v.path(), "ERROR", v.path(),
                            v.message(), null ) )
                    .toList();
        }
        final List< PageViolation > out = new ArrayList<>();
        forEachParsedPage( "drift drill-down", ( name, parsedOrNull, parseError ) -> {
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
        forEachParsedPage( "drift sweep", ( name, parsedOrNull, parseError ) -> {
            scanned[ 0 ]++;
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

    private boolean sweepShacl( final Map< CountKey, Integer > counts ) {
        if ( shaclSource == null ) {
            return false;
        }
        try {
            for ( final OntologyShaclValidator.Violation v : shaclSource.get() ) {
                bump( counts, "shacl", v.path(), "ERROR" );
            }
            return true;
        } catch ( final RuntimeException e ) {
            LOG.warn( "drift sweep: SHACL conformance check unavailable, persisting without it: {}",
                    e.getMessage(), e );
            return false;
        }
    }

    @FunctionalInterface
    private interface PageVisitor {
        /** parseError != null → strict parse failed; parsedOrNull == null && parseError == null → no frontmatter. */
        void visit( String name, ParsedPage parsedOrNull, FrontmatterParseException parseError );
    }

    private void forEachParsedPage( final String context, final PageVisitor visitor ) {
        final Collection< Page > pages;
        try {
            pages = pageManager.getAllPages();
        } catch ( final ProviderException e ) {
            LOG.warn( "{}: page enumeration failed: {}", context, e.getMessage(), e );
            throw new IllegalStateException( context + ": page enumeration failed", e );
        }
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
