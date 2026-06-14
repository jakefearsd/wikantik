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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Re-extracts derived wiki pages from their retained source attachments, clobbering the
 * machine-owned body while preserving human curation (tags, type, verification, etc.).
 *
 * <p>Reflow delegates entirely to {@link DerivedPageIngestionService#ingest} with
 * {@code force=true}, so all extraction + metadata-merge semantics are DRY (no
 * re-implementation of the merge logic here).
 *
 * <p>The service is designed for unit-testability through four narrow functional seams:
 * {@link PageMetadataReader}, {@link PageLister}, {@link AttachmentBytesReader}, and
 * the {@link DerivedPageIngestionService}.  Production wiring binds these to the real
 * managers; tests supply lightweight fakes.
 */
public class DerivedReflowService {

    private static final Logger LOG = LogManager.getLogger( DerivedReflowService.class );

    // -------------------------------------------------------------------------
    // Testability seams
    // -------------------------------------------------------------------------

    /** Reads the frontmatter metadata of an existing page; returns empty if the page is absent. */
    @FunctionalInterface
    public interface PageMetadataReader {
        Optional< Map< String, Object > > readMetadata( String pageName );
    }

    /**
     * Returns all known page names in the wiki.
     * The service filters for derived pages itself.
     */
    @FunctionalInterface
    public interface PageLister {
        List< String > listPageNames();
    }

    /**
     * Reads the raw bytes of a named attachment stored on a page.
     *
     * @param pageName the wiki page that owns the attachment.
     * @param filename the attachment filename (matches the {@code derived_from} frontmatter value).
     * @return the raw source bytes.
     * @throws Exception if the attachment cannot be read.
     */
    @FunctionalInterface
    public interface AttachmentBytesReader {
        byte[] read( String pageName, String filename ) throws Exception;
    }

    // -------------------------------------------------------------------------
    // Result records
    // -------------------------------------------------------------------------

    /**
     * Outcome of a {@link DerivedReflowService#reflowAll} call.
     *
     * @param reflowed number of derived pages successfully re-ingested.
     * @param skipped  number of pages skipped (not derived).
     * @param failed   number of pages that failed during reflow (logged; did not abort the run).
     */
    public record ReflowSummary( int reflowed, int skipped, int failed ) {}

    /**
     * Snapshot of the derived-page fleet health.
     *
     * @param derivedTotal           total number of derived pages.
     * @param staleCount             pages whose {@code derived_extractor_version} is below current.
     * @param currentExtractorVersion the version that up-to-date pages should carry.
     */
    public record ReflowStatus( int derivedTotal, int staleCount, int currentExtractorVersion ) {}

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final PageMetadataReader      metadataReader;
    private final PageLister              pageLister;
    private final AttachmentBytesReader   attachmentReader;
    private final DerivedPageIngestionService ingestionService;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * @param metadataReader  reads current frontmatter metadata for a page.
     * @param pageLister      enumerates all page names (service filters for derived pages).
     * @param attachmentReader reads raw attachment bytes for a named file on a page.
     * @param ingestionService the ingestion service — carries all extraction + merge logic.
     */
    public DerivedReflowService(
            final PageMetadataReader      metadataReader,
            final PageLister              pageLister,
            final AttachmentBytesReader   attachmentReader,
            final DerivedPageIngestionService ingestionService ) {
        this.metadataReader    = metadataReader;
        this.pageLister        = pageLister;
        this.attachmentReader  = attachmentReader;
        this.ingestionService  = ingestionService;
    }

    // -------------------------------------------------------------------------
    // Core operations
    // -------------------------------------------------------------------------

    /**
     * Reflows a single derived page: reads the retained source attachment and
     * re-ingests it with {@code force=true}, clobbering the body while preserving
     * human curation (delegated to {@link DerivedPageIngestionService#ingest}).
     *
     * <p>Returns {@link IngestResult#unchanged} if the page is not derived or absent.
     *
     * @param pageName wiki page name to reflow.
     * @param author   wiki login name recorded as the page author on re-save.
     * @return the ingest outcome, or an UNCHANGED result if skipped.
     */
    public IngestResult reflow( final String pageName, final String author ) {
        final Optional< Map< String, Object > > metaOpt = metadataReader.readMetadata( pageName );
        if ( metaOpt.isEmpty() || !DerivedPage.isDerived( metaOpt.get() ) ) {
            LOG.debug( "DerivedReflowService: skipping '{}' — not a derived page", pageName );
            return IngestResult.unchanged( pageName );
        }

        final String sourceFilename = DerivedPage.derivedFrom( metaOpt.get() ).orElseThrow();
        final String contentType    = guessContentType( sourceFilename );

        final byte[] sourceBytes;
        try {
            sourceBytes = attachmentReader.read( pageName, sourceFilename );
        } catch ( final Exception e ) {
            LOG.warn( "DerivedReflowService: could not read attachment '{}/{}' for reflow: {}",
                pageName, sourceFilename, e.getMessage(), e );
            return IngestResult.failed( pageName, "attachment read failed: " + e.getMessage() );
        }

        return ingestionService.ingest( sourceBytes, sourceFilename, contentType,
            new IngestOptions( true, author ) );
    }

    /**
     * Convenience overload that uses {@code null} as the author (recorded as system-triggered).
     */
    public IngestResult reflow( final String pageName ) {
        return reflow( pageName, null );
    }

    /**
     * Reflows all derived pages in the wiki.
     *
     * <p>A failure on any single page is logged at WARN and counted — it does not abort
     * the run.  Non-derived pages are silently skipped.
     *
     * @param author wiki login name recorded as the author on each re-saved page.
     * @return a {@link ReflowSummary} with counts of reflowed / skipped / failed pages.
     */
    public ReflowSummary reflowAll( final String author ) {
        final List< String > names = pageLister.listPageNames();
        int reflowed = 0;
        int skipped  = 0;
        int failed   = 0;

        for ( final String pageName : names ) {
            final Optional< Map< String, Object > > metaOpt = metadataReader.readMetadata( pageName );
            if ( metaOpt.isEmpty() || !DerivedPage.isDerived( metaOpt.get() ) ) {
                skipped++;
                continue;
            }

            final IngestResult result = reflow( pageName, author );
            switch ( result.status() ) {
                case CREATED, UPDATED -> reflowed++;
                case UNCHANGED        -> skipped++;
                case FAILED           -> {
                    failed++;
                    LOG.warn( "DerivedReflowService.reflowAll: failed page='{}' reason='{}'",
                        pageName, result.message() );
                }
            }
        }

        LOG.info( "DerivedReflowService.reflowAll: reflowed={} skipped={} failed={}",
            reflowed, skipped, failed );
        return new ReflowSummary( reflowed, skipped, failed );
    }

    // -------------------------------------------------------------------------
    // Status / staleness
    // -------------------------------------------------------------------------

    /**
     * Returns the number of derived pages whose {@code derived_extractor_version} is
     * below {@link DerivedPageIngestionService#CURRENT_EXTRACTOR_VERSION}.
     */
    public int staleCount() {
        return (int) pageLister.listPageNames().stream()
            .map( metadataReader::readMetadata )
            .filter( Optional::isPresent )
            .map( Optional::get )
            .filter( DerivedPage::isDerived )
            .filter( meta -> isStale( meta ) )
            .count();
    }

    /**
     * Returns a status snapshot of the derived-page fleet.
     */
    public ReflowStatus status() {
        final List< Map< String, Object > > derivedMetas = pageLister.listPageNames().stream()
            .map( metadataReader::readMetadata )
            .filter( Optional::isPresent )
            .map( Optional::get )
            .filter( DerivedPage::isDerived )
            .toList();

        final int derivedTotal = derivedMetas.size();
        final int stale = (int) derivedMetas.stream().filter( this::isStale ).count();
        return new ReflowStatus( derivedTotal, stale, DerivedPageIngestionService.CURRENT_EXTRACTOR_VERSION );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isStale( final Map< String, Object > meta ) {
        final Object vObj = meta.get( DerivedPage.DERIVED_EXTRACTOR_VERSION );
        if ( vObj == null ) {
            return true; // absent version treated as stale
        }
        try {
            return Integer.parseInt( vObj.toString() ) < DerivedPageIngestionService.CURRENT_EXTRACTOR_VERSION;
        } catch ( final NumberFormatException e ) {
            return true; // unparseable treated as stale
        }
    }

    /** Cheap content-type guess from the file extension — good enough for Tika dispatch. */
    private static String guessContentType( final String filename ) {
        if ( filename == null ) {
            return "application/octet-stream";
        }
        final String lower = filename.toLowerCase( java.util.Locale.ROOT );
        if ( lower.endsWith( ".pdf" ) )  { return "application/pdf"; }
        if ( lower.endsWith( ".docx" ) ) { return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; }
        if ( lower.endsWith( ".doc" ) )  { return "application/msword"; }
        if ( lower.endsWith( ".txt" ) )  { return "text/plain"; }
        if ( lower.endsWith( ".md" ) )   { return "text/markdown"; }
        if ( lower.endsWith( ".html" ) || lower.endsWith( ".htm" ) ) { return "text/html"; }
        return "application/octet-stream";
    }
}
