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
package com.wikantik.mcp.resources;

import com.google.gson.Gson;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Page;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Provides MCP Resource Templates for read-only wiki data access, following the MCP spec
 * recommendation that Resources are for data access while Tools are for actions.
 */
public class WikiResources {

    private static final Logger LOG = LogManager.getLogger( WikiResources.class );

    private final PageManager pageManager;
    private final ReferenceManager referenceManager;
    private final AttachmentManager attachmentManager;
    private final SystemPageRegistry systemPageRegistry;
    private final Gson gson = new Gson();

    public WikiResources( final PageManager pageManager,
                          final ReferenceManager referenceManager,
                          final AttachmentManager attachmentManager,
                          final SystemPageRegistry systemPageRegistry ) {
        this.pageManager = pageManager;
        this.referenceManager = referenceManager;
        this.attachmentManager = attachmentManager;
        this.systemPageRegistry = systemPageRegistry;
    }

    public List< McpServerFeatures.SyncResourceTemplateSpecification > resourceTemplates() {
        return List.of(
                pageTemplate(),
                pageVersionTemplate(),
                pageAttachmentsTemplate(),
                pageBacklinksTemplate()
        );
    }

    public List< McpServerFeatures.SyncResourceSpecification > staticResources() {
        return List.of(
                pagesListResource(),
                recentChangesResource()
        );
    }

    // --- Resource Templates (parameterized) ---

    private McpServerFeatures.SyncResourceTemplateSpecification pageTemplate() {
        final McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
                .uriTemplate( "wiki://pages/{pageName}" )
                .name( "Wiki Page" )
                .description( "Read a wiki page's content and metadata" )
                .mimeType( "application/json" )
                .build();

        return new McpServerFeatures.SyncResourceTemplateSpecification( template, ( exchange, request ) -> {
            final String pageName = extractParam( request.uri(), "wiki://pages/", null );
            return readPage( pageName, PageProvider.LATEST_VERSION );
        } );
    }

    private McpServerFeatures.SyncResourceTemplateSpecification pageVersionTemplate() {
        final McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
                .uriTemplate( "wiki://pages/{pageName}/version/{version}" )
                .name( "Wiki Page Version" )
                .description( "Read a specific version of a wiki page" )
                .mimeType( "application/json" )
                .build();

        return new McpServerFeatures.SyncResourceTemplateSpecification( template, ( exchange, request ) -> {
            final String uri = request.uri();
            // Parse: wiki://pages/{pageName}/version/{version}
            final String withoutPrefix = uri.substring( "wiki://pages/".length() );
            final int versionIdx = withoutPrefix.indexOf( "/version/" );
            final String pageName = withoutPrefix.substring( 0, versionIdx );
            final int version = Integer.parseInt( withoutPrefix.substring( versionIdx + "/version/".length() ) );
            return readPage( pageName, version );
        } );
    }

    private McpServerFeatures.SyncResourceTemplateSpecification pageAttachmentsTemplate() {
        final McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
                .uriTemplate( "wiki://pages/{pageName}/attachments" )
                .name( "Page Attachments" )
                .description( "List attachments for a wiki page" )
                .mimeType( "application/json" )
                .build();

        return new McpServerFeatures.SyncResourceTemplateSpecification( template, ( exchange, request ) -> {
            final String pageName = extractParam( request.uri(), "wiki://pages/", "/attachments" );
            return readAttachments( pageName );
        } );
    }

    private McpServerFeatures.SyncResourceTemplateSpecification pageBacklinksTemplate() {
        final McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
                .uriTemplate( "wiki://pages/{pageName}/backlinks" )
                .name( "Page Backlinks" )
                .description( "Find pages that link to a given page" )
                .mimeType( "application/json" )
                .build();

        return new McpServerFeatures.SyncResourceTemplateSpecification( template, ( exchange, request ) -> {
            final String pageName = extractParam( request.uri(), "wiki://pages/", "/backlinks" );
            return readBacklinks( pageName );
        } );
    }

    // --- Static Resources ---

    private McpServerFeatures.SyncResourceSpecification pagesListResource() {
        final McpSchema.Resource resource = McpSchema.Resource.builder()
                .uri( "wiki://pages" )
                .name( "All Wiki Pages" )
                .description( "List all wiki pages" )
                .mimeType( "application/json" )
                .build();

        return new McpServerFeatures.SyncResourceSpecification( resource, ( exchange, request ) -> {
            try {
                final Collection< Page > allPages = pageManager.getAllPages();
                final List< Map< String, Object > > pages = allPages.stream()
                        .sorted( Comparator.comparing( Page::getName ) )
                        .map( p -> {
                            final Map< String, Object > entry = new LinkedHashMap<>();
                            entry.put( "name", p.getName() );
                            entry.put( "lastModified", p.getLastModified() != null
                                    ? p.getLastModified().toInstant().toString() : null );
                            entry.put( "author", p.getAuthor() );
                            if ( systemPageRegistry != null ) {
                                entry.put( "systemPage", systemPageRegistry.isSystemPage( p.getName() ) );
                            }
                            return entry;
                        } )
                        .collect( Collectors.toList() );

                return textResource( "wiki://pages", gson.toJson( Map.of( "pages", pages ) ) );
            } catch ( final Exception e ) {
                LOG.error( "Failed to list pages: {}", e.getMessage(), e );
                return textResource( "wiki://pages", gson.toJson( Map.of( "error", e.getMessage() ) ) );
            }
        } );
    }

    private McpServerFeatures.SyncResourceSpecification recentChangesResource() {
        final McpSchema.Resource resource = McpSchema.Resource.builder()
                .uri( "wiki://recent-changes" )
                .name( "Recent Changes" )
                .description( "Recently modified wiki pages" )
                .mimeType( "application/json" )
                .build();

        return new McpServerFeatures.SyncResourceSpecification( resource, ( exchange, request ) -> {
            final Set< Page > recentChanges = pageManager.getRecentChanges();
            final List< Map< String, Object > > changes = recentChanges.stream()
                    .sorted( ( a, b ) -> {
                        if ( b.getLastModified() == null ) return -1;
                        if ( a.getLastModified() == null ) return 1;
                        return b.getLastModified().compareTo( a.getLastModified() );
                    } )
                    .limit( 50 )
                    .map( p -> {
                        final Map< String, Object > entry = new LinkedHashMap<>();
                        entry.put( "pageName", p.getName() );
                        entry.put( "author", p.getAuthor() );
                        entry.put( "lastModified", p.getLastModified() != null
                                ? p.getLastModified().toInstant().toString() : null );
                        entry.put( "changeNote", p.getAttribute( Page.CHANGENOTE ) );
                        return entry;
                    } )
                    .collect( Collectors.toList() );

            return textResource( "wiki://recent-changes", gson.toJson( Map.of( "changes", changes ) ) );
        } );
    }

    // --- Helper methods ---

    private McpSchema.ReadResourceResult readPage( final String pageName, final int version ) {
        final Page page = pageManager.getPage( pageName, version );
        if ( page == null ) {
            return textResource( "wiki://pages/" + pageName,
                    gson.toJson( Map.of( "exists", false, "pageName", pageName ) ) );
        }

        final String rawText = pageManager.getPureText( pageName, version );
        final ParsedPage parsed = FrontmatterParser.parse( rawText );

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "exists", true );
        result.put( "pageName", page.getName() );
        result.put( "content", parsed.body() );
        result.put( "metadata", parsed.metadata() );
        result.put( "version", Math.max( page.getVersion(), 1 ) );
        result.put( "author", page.getAuthor() );
        result.put( "lastModified", page.getLastModified() != null
                ? page.getLastModified().toInstant().toString() : null );

        return textResource( "wiki://pages/" + pageName, gson.toJson( result ) );
    }

    private McpSchema.ReadResourceResult readAttachments( final String pageName ) {
        final Page page = pageManager.getPage( pageName );
        if ( page == null ) {
            return textResource( "wiki://pages/" + pageName + "/attachments",
                    gson.toJson( Map.of( "exists", false, "pageName", pageName, "attachments", List.of() ) ) );
        }

        try {
            final List< Attachment > attachments = attachmentManager.listAttachments( page );
            final List< Map< String, Object > > items = attachments.stream()
                    .map( att -> {
                        final Map< String, Object > entry = new LinkedHashMap<>();
                        entry.put( "name", att.getFileName() );
                        entry.put( "size", att.getSize() );
                        entry.put( "lastModified", att.getLastModified() != null
                                ? att.getLastModified().toInstant().toString() : null );
                        return entry;
                    } )
                    .collect( Collectors.toList() );

            return textResource( "wiki://pages/" + pageName + "/attachments",
                    gson.toJson( Map.of( "exists", true, "pageName", pageName, "attachments", items ) ) );
        } catch ( final Exception e ) {
            LOG.error( "Failed to list attachments for {}: {}", pageName, e.getMessage(), e );
            return textResource( "wiki://pages/" + pageName + "/attachments",
                    gson.toJson( Map.of( "error", e.getMessage() ) ) );
        }
    }

    private McpSchema.ReadResourceResult readBacklinks( final String pageName ) {
        final Set< String > referrers = referenceManager.findReferrers( pageName );
        final List< String > backlinks = new ArrayList<>( referrers );
        Collections.sort( backlinks );

        return textResource( "wiki://pages/" + pageName + "/backlinks",
                gson.toJson( Map.of( "pageName", pageName, "backlinks", backlinks ) ) );
    }

    private static McpSchema.ReadResourceResult textResource( final String uri, final String text ) {
        return new McpSchema.ReadResourceResult( List.of(
                new McpSchema.TextResourceContents( uri, "application/json", text ) ) );
    }

    private static String extractParam( final String uri, final String prefix, final String suffix ) {
        String result = uri.substring( prefix.length() );
        if ( suffix != null && result.endsWith( suffix ) ) {
            result = result.substring( 0, result.length() - suffix.length() );
        }
        return result;
    }
}
