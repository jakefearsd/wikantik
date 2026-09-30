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
package com.wikantik.export;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.wikantik.WikiSubsystems;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.ClusterSummary;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.TagSummary;
import com.wikantik.auth.permissions.PermissionFilter;
import com.wikantik.util.TextUtil;

/**
 * Orchestrates an Obsidian-compatible content export end to end: resolves an
 * {@link ExportSelection} against the live wiki (ACL-filtered), previews its size, and streams
 * the resulting zip — converting each page with {@link ObsidianPageConverter}, planning the
 * vault layout with {@link VaultLayout}, and writing everything through
 * {@link ObsidianVaultWriter}. No job state is kept between calls; {@link #stream} is a single
 * synchronous pass. See {@code docs/superpowers/specs/2026-09-29-obsidian-export-design.md}.
 */
public final class ExportService {

    public static final String PROP_MAX_PAGES = "wikantik.export.maxPages";
    public static final int DEFAULT_MAX_PAGES = 2000;

    private static final Logger LOG = LogManager.getLogger( ExportService.class );
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern( "yyyyMMdd-HHmm" ).withZone( ZoneOffset.UTC );

    private final StructuralIndexService index;
    private final PageManager pages;
    private final ReferenceManager refs;
    private final AttachmentManager attachments;
    private final PermissionFilter permissions;
    private final String baseUrl;
    private final int maxPages;
    private final Clock clock;

    public ExportService( final StructuralIndexService index, final PageManager pages, final ReferenceManager refs,
                          final AttachmentManager attachments, final PermissionFilter permissions,
                          final String baseUrl, final int maxPages, final Clock clock ) {
        this.index = index;
        this.pages = pages;
        this.refs = refs;
        this.attachments = attachments;
        this.permissions = permissions;
        this.baseUrl = baseUrl;
        this.maxPages = maxPages;
        this.clock = clock;
    }

    /** Reads wikantik.baseURL and wikantik.export.maxPages from the engine properties. */
    public static ExportService fromSubsystems( final Engine engine, final WikiSubsystems subs ) {
        final String baseUrl = TextUtil.getStringProperty( engine.getWikiProperties(), "wikantik.baseURL", "" );
        final int maxPages = TextUtil.getIntegerProperty( engine.getWikiProperties(), PROP_MAX_PAGES, DEFAULT_MAX_PAGES );
        return new ExportService( subs.pageGraph().structuralIndexService(), subs.page().pages(),
                subs.pageGraph().referenceManager(), subs.page().attachments(), new PermissionFilter( engine ),
                baseUrl, maxPages, Clock.systemUTC() );
    }

    public ExportOptions options() {
        final List< String > clusters = index.listClusters().stream().map( ClusterSummary::name ).sorted().toList();
        final List< String > tags = index.listTags( 2 ).stream().map( TagSummary::tag ).limit( 300 ).toList();
        return new ExportOptions( clusters, tags );
    }

    public ExportPreview preview( final Session session, final ExportSelection selection ) {
        final ExportCatalog catalog = new EngineExportCatalog( index, pages, refs, permissions, session );
        final ResolvedSelection resolved = new ExportSelectionResolver().resolve( selection, catalog );
        final int unresolvedLinks = countUnresolvedLinks( resolved, catalog );
        final SizeEstimate sizes = estimateSizes( resolved );
        final List< String > sample = resolved.pages().stream().map( PageDescriptor::slug ).limit( 50 ).toList();
        return new ExportPreview( resolved.pages().size(), sizes.attachmentCount, sizes.bytes,
                unresolvedLinks, maxPages, resolved.pages().size() > maxPages, sample );
    }

    /** All work that can fail with a status code happens here, before a byte is streamed. */
    public PreparedExport prepare( final Session session, final ExportSelection selection ) throws ExportTooLargeException {
        final ExportCatalog catalog = new EngineExportCatalog( index, pages, refs, permissions, session );
        final ResolvedSelection resolved = new ExportSelectionResolver().resolve( selection, catalog );
        if ( resolved.pages().size() > maxPages ) {
            throw new ExportTooLargeException( resolved.pages().size(), maxPages );
        }
        final String fileName = "wikantik-export-" + FILE_TIMESTAMP.format( clock.instant() ) + ".zip";
        return new PreparedExport( selection, resolved.pages(), fileName, session );
    }

    /** Streams the zip. Never throws for per-page/attachment problems (they become warnings). */
    public void stream( final PreparedExport export, final OutputStream out ) throws IOException {
        new ExportZipBuilder( pages, attachments, index, permissions, export.session(), baseUrl, clock ).writeZip( export, out );
    }

    // ---- preview helpers --------------------------------------------------------------------

    private int countUnresolvedLinks( final ResolvedSelection resolved, final ExportCatalog catalog ) {
        final Set< String > included = resolved.pages().stream().map( PageDescriptor::slug ).collect( Collectors.toSet() );
        final Set< String > outbound = new LinkedHashSet<>();
        for ( final PageDescriptor d : resolved.pages() ) {
            outbound.addAll( catalog.outboundPages( d.slug() ) );
        }
        outbound.removeAll( included );
        return outbound.size();
    }

    private static final class SizeEstimate {
        int attachmentCount;
        long bytes;
    }

    private SizeEstimate estimateSizes( final ResolvedSelection resolved ) {
        final SizeEstimate sizes = new SizeEstimate();
        if ( resolved.pages().size() > maxPages ) {
            return sizes; // skip the size pass entirely — the export will be refused anyway
        }
        for ( final PageDescriptor d : resolved.pages() ) {
            final Page page = pages.getPage( d.slug() );
            if ( page == null ) {
                continue;
            }
            sizes.bytes += pages.getPureText( page ).getBytes( StandardCharsets.UTF_8 ).length;
            accumulateAttachmentSizes( page, sizes );
        }
        return sizes;
    }

    private void accumulateAttachmentSizes( final Page page, final SizeEstimate sizes ) {
        try {
            for ( final Attachment att : attachments.listAttachments( page ) ) {
                sizes.attachmentCount++;
                sizes.bytes += att.getSize();
            }
        } catch ( final ProviderException e ) {
            LOG.warn( "Export preview: listing attachments for {} failed: {}", page.getName(), e.getMessage(), e );
        }
    }

    /**
     * @param session the exporting caller's session, threaded through to {@link #stream} so every
     *                ACL decision made while streaming (including out-of-export link/citation
     *                targets) uses the same caller's view permission {@link #prepare} resolved
     *                the page set with — never a fixed guest fallback.
     */
    public record PreparedExport( ExportSelection selection, List< PageDescriptor > pages, String fileName, Session session ) {}
}
