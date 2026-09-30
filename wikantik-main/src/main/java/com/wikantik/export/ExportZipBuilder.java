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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.auth.permissions.PermissionFilter;

/**
 * Does the actual work of {@link ExportService#stream}: plans the vault layout, converts every
 * page, writes attachments, and finishes the zip with a manifest + readme. Split out of
 * {@code ExportService} purely to keep that class's size in check (PMD's {@code -Pcomplexity-gate}
 * God Class rule) — the two together are one algorithm, sharing {@code ExportService}'s
 * dependencies via constructor injection rather than a shared base class. Package-private: an
 * implementation detail of {@link ExportService}, not part of the export package's public surface.
 */
final class ExportZipBuilder {

    private static final Logger LOG = LogManager.getLogger( ExportZipBuilder.class );
    private static final ObsidianPageConverter CONVERTER = new ObsidianPageConverter();

    private final PageManager pages;
    private final AttachmentManager attachments;
    private final StructuralIndexService index;
    private final PermissionFilter permissions;
    private final Session session;
    private final String baseUrl;
    private final Clock clock;

    /**
     * @param session the exporting caller's session ({@link ExportService.PreparedExport#session()}) —
     *                used for every ACL decision made while streaming, including out-of-export
     *                link/citation targets. Never a fixed guest fallback: whoever called
     *                {@code ExportService.prepare} is who these checks run as.
     */
    ExportZipBuilder( final PageManager pages, final AttachmentManager attachments, final StructuralIndexService index,
                      final PermissionFilter permissions, final Session session, final String baseUrl, final Clock clock ) {
        this.pages = pages;
        this.attachments = attachments;
        this.index = index;
        this.permissions = permissions;
        this.session = session;
        this.baseUrl = baseUrl;
        this.clock = clock;
    }

    void writeZip( final ExportService.PreparedExport export, final OutputStream out ) throws IOException {
        final Map< AttachmentRef, Attachment > attachmentByRef = collectAttachments( export.pages() );
        final VaultLayout layout = VaultLayout.plan( export.pages(), attachmentByRef.keySet() );
        final Set< String > included = export.pages().stream()
                .map( PageDescriptor::slug ).collect( Collectors.toCollection( LinkedHashSet::new ) );
        final ExportLinkContext ctx =
                new LinkContext( included, layout, attachmentByRef, export.selection().unresolved() );

        final List< ExportManifest.PageEntry > pageEntries = new ArrayList<>();
        final List< ExportManifest.AttachmentEntry > attachmentEntries = new ArrayList<>();
        final List< AttachmentRef > attachmentRefs = new ArrayList<>();
        final List< String > warnings = new ArrayList<>();

        try ( ObsidianVaultWriter writer = new ObsidianVaultWriter( out ) ) {
            convertAndWritePages( export, layout, ctx, writer, pageEntries, attachmentRefs, warnings );
            writeAttachments( writer, layout, attachmentByRef, attachmentRefs, attachmentEntries, warnings );
            final Instant now = clock.instant();
            final ExportManifest manifest = new ExportManifest( ExportManifest.FORMAT_VERSION, baseUrl, now.toString(),
                    selectionMap( export.selection() ), pageEntries, attachmentEntries, warnings );
            writer.finish( manifest, buildReadme( export.selection(), pageEntries.size(), attachmentEntries.size(), warnings, now ) );
        }
    }

    // ---- attachment collection & page conversion ----------------------------------------------

    private Map< AttachmentRef, Attachment > collectAttachments( final List< PageDescriptor > descriptors ) {
        final Map< AttachmentRef, Attachment > byRef = new LinkedHashMap<>();
        for ( final PageDescriptor d : descriptors ) {
            final Page page = pages.getPage( d.slug() );
            if ( page == null ) {
                continue;
            }
            try {
                for ( final Attachment att : attachments.listAttachments( page ) ) {
                    byRef.put( new AttachmentRef( d.slug(), att.getFileName() ), att );
                }
            } catch ( final ProviderException e ) {
                LOG.warn( "Export: listing attachments for {} failed: {}", d.slug(), e.getMessage(), e );
            }
        }
        return byRef;
    }

    private void convertAndWritePages( final ExportService.PreparedExport export, final VaultLayout layout, final ExportLinkContext ctx,
                                        final ObsidianVaultWriter writer, final List< ExportManifest.PageEntry > pageEntries,
                                        final List< AttachmentRef > attachmentRefs, final List< String > warnings ) throws IOException {
        for ( final PageDescriptor d : export.pages() ) {
            final Page page = pages.getPage( d.slug() );
            if ( page == null ) {
                LOG.warn( "Export: page {} missing during stream, skipping", d.slug() );
                warnings.add( "Page " + d.slug() + " could not be read and was skipped" );
                continue;
            }
            final ConvertedPage converted = convertOrFallback( d, page, layout, ctx, warnings );
            final String path = layout.pagePath( d.slug() );
            final String sha = writer.writeText( path, converted.markdown() );
            pageEntries.add( new ExportManifest.PageEntry( d.slug(), d.canonicalId(), page.getVersion(), path, sha ) );
            warnings.addAll( converted.warnings() );
            attachmentRefs.addAll( converted.attachments() );
        }
    }

    /**
     * Converts one page, falling back to its raw markdown on any {@link RuntimeException} —
     * whether thrown by the converter itself or (rarer) by reading the page's text in the first
     * place. In the latter case there is no raw text to fall back to either, so the page still
     * gets a (empty-bodied) zip entry and manifest record rather than being silently dropped.
     */
    private ConvertedPage convertOrFallback( final PageDescriptor d, final Page page, final VaultLayout layout,
                                              final ExportLinkContext ctx, final List< String > warnings ) {
        String raw = null;
        try {
            raw = pages.getPureText( page );
            final Map< String, Object > extra = extraFrontmatter( d, page, raw, layout );
            return CONVERTER.convert( d.slug(), raw, extra, ctx );
        } catch ( final RuntimeException e ) {
            final boolean hadRaw = raw != null;
            LOG.warn( "Export: converting {} failed, writing {}: {}", d.slug(),
                    hadRaw ? "raw markdown" : "an empty placeholder (content unavailable)", e.getMessage(), e );
            warnings.add( "Converting " + d.slug() + " failed"
                    + ( hadRaw ? ", wrote raw markdown: " : ", content unavailable: " ) + e.getMessage() );
            return new ConvertedPage( hadRaw ? raw : "", List.of(), List.of() );
        }
    }

    private Map< String, Object > extraFrontmatter( final PageDescriptor d, final Page page, final String raw,
                                                      final VaultLayout layout ) {
        final Map< String, Object > metadata = FrontmatterParser.parse( raw ).metadata();
        final Map< String, Object > extra = new LinkedHashMap<>();
        extra.put( "wikantik_url", liveUrl( d.slug() ) );
        extra.put( "wikantik_version", String.valueOf( page.getVersion() ) );
        addAliases( extra, d, layout, metadata.get( "aliases" ) );
        addRelated( extra, metadata.get( "related" ), layout );
        return extra;
    }

    /**
     * Adds the page title and any layout alias to the page's own {@code aliases:} (scalar or
     * list) — existing entries first, de-duplicated. Only written when something is added, since
     * {@link FrontmatterPatcher} replaces the whole {@code aliases:} key.
     */
    private void addAliases( final Map< String, Object > extra, final PageDescriptor d, final VaultLayout layout,
                             final Object existingRaw ) {
        final LinkedHashSet< String > added = new LinkedHashSet<>();
        if ( d.title() != null && !d.title().equals( d.slug() ) ) {
            added.add( d.title() );
        }
        layout.aliasFor( d.slug() ).ifPresent( added::add );
        if ( added.isEmpty() ) {
            return;
        }
        final LinkedHashSet< String > aliases = new LinkedHashSet<>();
        asList( existingRaw ).forEach( a -> aliases.add( String.valueOf( a ) ) );
        aliases.addAll( added );
        extra.put( "aliases", List.copyOf( aliases ) );
    }

    /** Frontmatter {@code related:} as wikilinks — to the vault file an in-export page was written as. */
    private void addRelated( final Map< String, Object > extra, final Object relatedRaw, final VaultLayout layout ) {
        if ( relatedRaw == null ) {
            return;
        }
        extra.put( "related", asList( relatedRaw ).stream().map( item -> {
            final String name = String.valueOf( item );
            final String basename = layout.basename( name );
            return basename == null || basename.equals( name ) ? "[[" + name + "]]" : "[[" + basename + "|" + name + "]]";
        } ).toList() );
    }

    private static List< ? > asList( final Object raw ) {
        if ( raw == null ) {
            return List.of();
        }
        return raw instanceof List< ? > list ? list : List.of( raw );
    }

    // ---- attachment writing --------------------------------------------------------------------

    private void writeAttachments( final ObsidianVaultWriter writer, final VaultLayout layout,
                                    final Map< AttachmentRef, Attachment > byRef, final List< AttachmentRef > refs,
                                    final List< ExportManifest.AttachmentEntry > entries, final List< String > warnings ) throws IOException {
        final Set< AttachmentRef > written = new LinkedHashSet<>();
        for ( final AttachmentRef ref : refs ) {
            if ( written.add( ref ) ) {
                writeOneAttachment( writer, layout, byRef, ref, entries, warnings );
            }
        }
    }

    private void writeOneAttachment( final ObsidianVaultWriter writer, final VaultLayout layout,
                                      final Map< AttachmentRef, Attachment > byRef, final AttachmentRef ref,
                                      final List< ExportManifest.AttachmentEntry > entries, final List< String > warnings ) throws IOException {
        final Attachment att = byRef.get( ref );
        if ( att == null ) {
            LOG.warn( "Export: attachment {} on {} not found, skipping", ref.fileName(), ref.pageName() );
            warnings.add( "Attachment " + ref.fileName() + " on " + ref.pageName() + " could not be found" );
            return;
        }
        final InputStream in;
        try {
            in = attachments.getAttachmentStream( att );
        } catch ( final IOException | ProviderException e ) {
            LOG.warn( "Export: reading attachment {}/{} failed: {}", ref.pageName(), ref.fileName(), e.getMessage(), e );
            warnings.add( "Attachment " + ref.fileName() + " on " + ref.pageName() + " could not be read: " + e.getMessage() );
            return;
        }
        // Deliberately outside the catch above (spec §8): a failure opening/reading the source
        // attachment is a warning, but an IOException from here on (including from writeStream's
        // own zip-side write) propagates — it may mean the client disconnected mid-download.
        final String path = layout.attachmentPath( ref );
        try ( in ) {
            final String sha = writer.writeStream( path, in );
            entries.add( new ExportManifest.AttachmentEntry( ref.pageName(), ref.fileName(), path, sha ) );
        }
    }

    // ---- readme & manifest ---------------------------------------------------------------------

    private Map< String, Object > selectionMap( final ExportSelection selection ) {
        final Map< String, Object > map = new LinkedHashMap<>();
        map.put( "clusters", selection.clusters() );
        map.put( "includeSubClusters", selection.includeSubClusters() );
        map.put( "tags", selection.tags() );
        map.put( "type", selection.type().map( Enum::name ).orElse( null ) );
        map.put( "status", selection.status().orElse( null ) );
        map.put( "hops", selection.hops() );
        map.put( "unresolved", selection.unresolved().name() );
        return map;
    }

    private String buildReadme( final ExportSelection selection, final int pageCount, final int attachmentCount,
                                 final List< String > warnings, final Instant exportedAt ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "# Wikantik Export\n\n" );
        sb.append( "Exported: " ).append( exportedAt ).append( '\n' );
        sb.append( "Server: " ).append( baseUrl ).append( "\n\n" );
        sb.append( "## Selection\n\n" ).append( describeSelection( selection ) ).append( "\n\n" );
        sb.append( "## Counts\n\n- Pages: " ).append( pageCount )
                .append( "\n- Attachments: " ).append( attachmentCount ).append( "\n\n" );
        sb.append( "## What was changed\n\n" )
                .append( "- Wiki links were rewritten to Obsidian `[[wikilinks]]`\n" )
                .append( "- `[{ALLOW}]`, `[{DENY}]`, `[{SET}]`, and `[{TableOfContents}]` markup was stripped\n" )
                .append( "- Other wiki plugins were replaced with `> [!note]` callouts linking back to the live page\n\n" );
        sb.append( "Stripping `[{ALLOW}]` markup means an exported page no longer carries its access-control list. " )
                .append( "You could already view this content on the wiki, so nothing is disclosed that you could not already read.\n" );
        appendWarnings( sb, warnings );
        return sb.toString();
    }

    private void appendWarnings( final StringBuilder sb, final List< String > warnings ) {
        if ( warnings.isEmpty() ) {
            return;
        }
        sb.append( "\n## Warnings\n\n" );
        for ( final String w : warnings ) {
            sb.append( "- " ).append( w ).append( '\n' );
        }
    }

    private String describeSelection( final ExportSelection selection ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "- Clusters: " ).append( selection.clusters().isEmpty() ? "(all)" : selection.clusters() ).append( '\n' );
        sb.append( "- Include sub-clusters: " ).append( selection.includeSubClusters() ).append( '\n' );
        sb.append( "- Tags: " ).append( selection.tags().isEmpty() ? "(none)" : selection.tags() ).append( '\n' );
        sb.append( "- Type: " ).append( selection.type().map( Enum::name ).orElse( "(any)" ) ).append( '\n' );
        sb.append( "- Status: " ).append( selection.status().orElse( "(any)" ) ).append( '\n' );
        sb.append( "- Hops: " ).append( selection.hops() ).append( '\n' );
        sb.append( "- Unresolved links: " ).append( selection.unresolved() );
        return sb.toString();
    }

    // ---- shared link-resolution helpers --------------------------------------------------------

    private Map< String, String > loadHeadings( final String pageName ) {
        final Page page = pages.getPage( pageName );
        if ( page == null ) {
            return Map.of();
        }
        // Body only: a frontmatter block's closing "---" would turn its last line into a setext H2.
        return HeadingSlugs.headingsBySlug( FrontmatterParser.parse( pages.getPureText( page ) ).body() );
    }

    /**
     * View check for a page outside the export set, evaluated as the exporting caller
     * ({@link #session}) — the same session {@code ExportService.prepare} resolved the page set
     * with, never a guest fallback. Pages inside the export set never go through this (they
     * already passed that session's own ACL check during resolution).
     */
    private boolean isViewableToCaller( final String pageName ) {
        return session != null && permissions.canAccessQuietly( session, pageName, "view" );
    }

    /** Path-segment encoding: {@link URLEncoder} is form encoding (space → {@code +}), so re-map to {@code %20}. */
    private String liveUrl( final String pageName ) {
        return baseUrl + "/wiki/" + URLEncoder.encode( pageName, StandardCharsets.UTF_8 ).replace( "+", "%20" );
    }

    /** {@link ExportLinkContext} bound to one {@link #writeZip} call's resolved page/attachment set. */
    private final class LinkContext implements ExportLinkContext {
        private final Set< String > included;
        private final VaultLayout layout;
        private final Map< AttachmentRef, Attachment > attachmentByRef;
        private final UnresolvedLinkMode mode;
        private final Map< String, Map< String, String > > headingCache = new HashMap<>();

        LinkContext( final Set< String > included, final VaultLayout layout,
                     final Map< AttachmentRef, Attachment > attachmentByRef, final UnresolvedLinkMode mode ) {
            this.included = included;
            this.layout = layout;
            this.attachmentByRef = attachmentByRef;
            this.mode = mode;
        }

        @Override
        public boolean inExport( final String pageName ) {
            return included.contains( pageName );
        }

        @Override
        public Optional< String > headingText( final String pageName, final String slug ) {
            if ( !included.contains( pageName ) && !isViewableToCaller( pageName ) ) {
                return Optional.empty();
            }
            return Optional.ofNullable( headingCache.computeIfAbsent( pageName, ExportZipBuilder.this::loadHeadings ).get( slug ) );
        }

        @Override
        public Optional< String > attachmentTarget( final String pageName, final String fileName ) {
            final AttachmentRef ref = new AttachmentRef( pageName, fileName );
            return attachmentByRef.containsKey( ref ) ? Optional.of( layout.attachmentLinkTarget( ref ) ) : Optional.empty();
        }

        @Override
        public Optional< String > slugForCanonicalId( final String canonicalId ) {
            return index.resolveSlugFromCanonicalId( canonicalId )
                    .filter( slug -> included.contains( slug ) || isViewableToCaller( slug ) );
        }

        @Override
        public Optional< String > vaultBasename( final String pageName ) {
            return included.contains( pageName ) ? Optional.ofNullable( layout.basename( pageName ) ) : Optional.empty();
        }

        @Override
        public String liveUrl( final String pageName ) {
            return ExportZipBuilder.this.liveUrl( pageName );
        }

        @Override
        public UnresolvedLinkMode unresolvedMode() {
            return mode;
        }
    }
}
