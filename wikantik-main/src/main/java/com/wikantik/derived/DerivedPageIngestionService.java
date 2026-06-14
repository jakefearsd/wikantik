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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stores a source document as an attachment, extracts its body via a {@link SourceExtractor},
 * and saves (or updates) a derived wiki page carrying {@code derived_from} provenance frontmatter.
 *
 * <p>The service is designed for unit-testability through three narrow functional seams:
 * {@link AttachmentStore}, {@link PageReader}, and {@link PageWriter}.  Production wiring
 * (a later task) binds these to the real {@code AttachmentManager} and {@code PageSaveHelper};
 * tests supply lightweight fakes.
 */
public class DerivedPageIngestionService {

    /** Bumped whenever the extraction logic changes enough to warrant reflowing old derived pages. */
    public static final int CURRENT_EXTRACTOR_VERSION = 1;

    private static final Logger LOG = LogManager.getLogger( DerivedPageIngestionService.class );

    // -------------------------------------------------------------------------
    // Testability seams (nested functional interfaces)
    // -------------------------------------------------------------------------

    /** Reads the frontmatter metadata of an existing page; returns empty if the page is absent. */
    @FunctionalInterface
    public interface PageReader {
        Optional< Map< String, Object > > readMetadata( String pageName );
    }

    /** Writes (creates or overwrites) a page with the given body and metadata. */
    @FunctionalInterface
    public interface PageWriter {
        void write( String pageName, String body, Map< String, Object > metadata, String author )
            throws Exception;
    }

    /** Stores the raw source bytes as an attachment on the given page. */
    @FunctionalInterface
    public interface AttachmentStore {
        void store( String pageName, String filename, byte[] bytes ) throws Exception;
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final SourceExtractor   extractor;
    private final AttachmentStore   attachments;
    private final PageReader        pageReader;
    private final PageWriter        pageWriter;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * @param extractor   extracts a markdown body from the raw source bytes.
     * @param attachments stores the raw bytes as a named attachment on the page.
     * @param pageReader  reads current frontmatter metadata (empty = page absent).
     * @param pageWriter  creates or overwrites the page with body + metadata.
     */
    public DerivedPageIngestionService(
            final SourceExtractor extractor,
            final AttachmentStore attachments,
            final PageReader      pageReader,
            final PageWriter      pageWriter ) {
        this.extractor   = extractor;
        this.attachments = attachments;
        this.pageReader  = pageReader;
        this.pageWriter  = pageWriter;
    }

    // -------------------------------------------------------------------------
    // Core operation
    // -------------------------------------------------------------------------

    /**
     * Ingests a source document.
     *
     * <ol>
     *   <li>Compute the SHA-256 of {@code source} and derive the stable page name from {@code filename}.</li>
     *   <li>If the page already exists as a derived page with the same SHA and {@code opts.force()} is false,
     *       return {@link IngestResult#unchanged}.</li>
     *   <li>Store the raw bytes as an attachment on the page.</li>
     *   <li>Extract the markdown body via {@link SourceExtractor}; return {@link IngestResult#failed} on empty.</li>
     *   <li>Build frontmatter metadata (preserve existing body-independent curation on update;
     *       set {@code type} / {@code title} only when not already present).</li>
     *   <li>Write the page.</li>
     *   <li>Return {@link IngestResult#created} or {@link IngestResult#updated}.</li>
     * </ol>
     *
     * <p>Any exception from the seams is caught, logged at WARN, and returned as a {@link IngestResult#failed}.
     *
     * @param source      raw source bytes (e.g. a PDF).
     * @param filename    original filename — used as the attachment name and to derive the page name.
     * @param contentType MIME type passed to the extractor.
     * @param opts        ingestion options.
     * @return the outcome of the operation.
     */
    public IngestResult ingest(
            final byte[]       source,
            final String       filename,
            final String       contentType,
            final IngestOptions opts ) {

        final String sha      = DerivedPage.sha256( source );
        final String pageName = DerivedPage.pageNameFor( filename );

        try {
            // Step 2 — overwrite guard + dedup check
            final Optional< Map< String, Object > > existing = pageReader.readMetadata( pageName );
            final boolean pageExists = existing.isPresent();

            if ( pageExists ) {
                if ( !DerivedPage.isDerived( existing.get() ) ) {
                    // Security: refuse to overwrite a human-authored page.
                    // Ingest may only create a new page or update one it previously created.
                    LOG.warn( "DerivedPageIngestionService: refusing to overwrite non-derived page '{}'"
                        + " for filename='{}'", pageName, filename );
                    return IngestResult.failed( pageName,
                        "refusing to overwrite non-derived page '" + pageName + "'" );
                }
                // Existing derived page — dedup by SHA
                final Object existingSha = existing.get().get( DerivedPage.DERIVED_SOURCE_SHA );
                if ( sha.equals( existingSha ) && !opts.force() ) {
                    return IngestResult.unchanged( pageName );
                }
            }

            // Step 3 — extract (performed before page write and attachment; no I/O side-effects)
            final ExtractionResult er = extractor.extract(
                new ByteArrayInputStream( source ), contentType, filename );
            if ( er.isEmpty() ) {
                LOG.warn( "DerivedPageIngestionService: empty extraction for filename='{}', page='{}'",
                    filename, pageName );
                return IngestResult.failed( pageName, "empty extraction" );
            }

            // Step 4 — build metadata
            final Map< String, Object > metadata = buildMetadata( existing, filename, sha, er );

            // Step 5 — write page first.
            // The page must exist before an attachment can be stored against it (the
            // attachment manager enforces that the parent page exists). Writing first
            // ensures the parent is present regardless of whether it is a new page or
            // an update, and keeps the page store consistent even if the attachment
            // step below subsequently fails.
            pageWriter.write( pageName, er.markdownBody(), metadata, opts.author() );

            // Step 6 — store attachment (parent page now exists)
            attachments.store( pageName, filename, source );

            // Step 7 — return outcome
            return pageExists ? IngestResult.updated( pageName ) : IngestResult.created( pageName );

        } catch ( final Exception e ) {
            LOG.warn( "DerivedPageIngestionService: ingest failed for filename='{}', page='{}': {}",
                filename, pageName, e.getMessage(), e );
            return IngestResult.failed( pageName, e.getMessage() );
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map< String, Object > buildMetadata(
            final Optional< Map< String, Object > > existing,
            final String                            filename,
            final String                            sha,
            final ExtractionResult                  er ) {

        // Start from existing metadata to preserve body-independent curation (tags, etc.)
        final Map< String, Object > meta = existing
            .map( HashMap::new )
            .orElseGet( HashMap::new );

        // Always set the provenance keys (these are machine-owned)
        meta.put( DerivedPage.DERIVED_FROM,             filename );
        meta.put( DerivedPage.DERIVED_EXTRACTOR,        "tika" );
        meta.put( DerivedPage.DERIVED_EXTRACTOR_VERSION, CURRENT_EXTRACTOR_VERSION );
        meta.put( DerivedPage.DERIVED_SOURCE_SHA,       sha );

        // Set type + title only when not already present (respect human curation on update)
        if ( !meta.containsKey( "type" ) ) {
            meta.put( "type", "reference" );
        }
        if ( !meta.containsKey( "title" ) && er.extractedTitle() != null && !er.extractedTitle().isBlank() ) {
            meta.put( "title", er.extractedTitle() );
        }

        return meta;
    }
}
