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
package com.wikantik.mcp.tools;


import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Page;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.frontmatter.FrontmatterParser;
import com.wikantik.frontmatter.ParsedPage;
import com.wikantik.pages.PageManager;
import com.wikantik.pages.PageSaveHelper;
import com.wikantik.pages.SaveOptions;
import com.wikantik.parser.MarkdownLinkScanner;

import java.util.*;

/**
 * Compound MCP tool that adds a new article to an existing cluster in a single call.
 * Auto-discovers cluster members, creates the article, patches the hub body to list it,
 * updates related metadata on all siblings, optionally updates Main, and verifies.
 */
public class ExtendClusterTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( ExtendClusterTool.class );
    public static final String TOOL_NAME = "extend_cluster";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final PageSaveHelper pageSaveHelper;
    private final PageManager pageManager;

    private String defaultAuthor = "MCP";

    public ExtendClusterTool( final PageSaveHelper pageSaveHelper, final PageManager pageManager ) {
        this.pageSaveHelper = pageSaveHelper;
        this.pageManager = pageManager;
    }

    @Override
    public void setDefaultAuthor( final String defaultAuthor ) {
        this.defaultAuthor = defaultAuthor;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > articleSchema = new LinkedHashMap<>();
        articleSchema.put( "type", "object" );
        articleSchema.put( "properties", Map.of(
                "name", Map.of( "type", "string", "description", "CamelCase page name for the new article" ),
                "body", Map.of( "type", "string", "description", "Markdown body content (without frontmatter)" ),
                "metadata", Map.of( "type", "object", "description",
                        "Frontmatter fields (tags, summary, date, author, etc.). " +
                        "type, cluster, related, and status are auto-set if omitted." )
        ) );
        articleSchema.put( "required", List.of( "name", "body" ) );

        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "clusterName", Map.of( "type", "string", "description",
                "Existing cluster identifier (kebab-case)" ) );
        properties.put( "article", articleSchema );
        properties.put( "updateMain", Map.of( "type", "boolean", "description",
                "Add article to the Main page listing (default: true)" ) );
        properties.put( "mainSection", Map.of( "type", "string", "description",
                "Section heading on Main page where the article should be listed. " +
                "If omitted, auto-detects from existing cluster entries." ) );
        properties.put( "author", Map.of( "type", "string", "description",
                "Author name (defaults to MCP client name)" ) );
        properties.put( "changeNote", Map.of( "type", "string", "description",
                "Optional change note" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Add a new article to an existing cluster in a single call. " +
                        "Auto-discovers cluster members, creates the article with correct metadata, " +
                        "patches the hub body to list it, updates related metadata on all siblings, " +
                        "optionally updates the Main page, and verifies the result." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "clusterName", "article" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
                .build();
    }

    @SuppressWarnings( "unchecked" )
    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String clusterName = McpToolUtils.getString( arguments, "clusterName" );
        final Map< String, Object > articleSpec = ( Map< String, Object > ) arguments.get( "article" );
        final boolean updateMain = arguments.get( "updateMain" ) == null
                || Boolean.TRUE.equals( arguments.get( "updateMain" ) );
        final String mainSection = McpToolUtils.getString( arguments, "mainSection" );
        final String author = McpToolUtils.getString( arguments, "author" );
        final String changeNote = McpToolUtils.getString( arguments, "changeNote" );
        final String effectiveAuthor = author != null ? author : defaultAuthor;

        if ( clusterName == null || clusterName.isBlank() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "clusterName is required" );
        }
        if ( articleSpec == null ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "article is required" );
        }

        final String articleName = ( String ) articleSpec.get( "name" );
        final String articleBody = ( String ) articleSpec.get( "body" );
        final Map< String, Object > articleMeta = articleSpec.get( "metadata" ) != null
                ? new LinkedHashMap<>( ( Map< String, Object > ) articleSpec.get( "metadata" ) )
                : new LinkedHashMap<>();

        if ( articleName == null || articleBody == null ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "article.name and article.body are required" );
        }

        // 1. Discover existing cluster members
        final Collection< Page > allPages;
        try {
            allPages = pageManager.getAllPages();
        } catch ( final Exception e ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "Failed to list pages: " + e.getMessage() );
        }

        String hubName = null;
        final List< String > existingMembers = new ArrayList<>();
        final Map< String, Map< String, Object > > memberMetadata = new LinkedHashMap<>();

        for ( final Page page : allPages ) {
            final String rawText = pageManager.getPureText( page.getName(), PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( rawText );
            final Map< String, Object > meta = parsed.metadata();

            if ( clusterName.equals( meta.get( "cluster" ) ) ) {
                existingMembers.add( page.getName() );
                memberMetadata.put( page.getName(), meta );
                if ( "hub".equals( meta.get( "type" ) ) ) {
                    hubName = page.getName();
                }
            }
        }

        if ( existingMembers.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "No pages found for cluster: " + clusterName,
                    "Use publish_cluster to create a new cluster." );
        }

        // 2. Auto-set article metadata
        articleMeta.putIfAbsent( "type", "article" );
        articleMeta.put( "cluster", clusterName );
        articleMeta.putIfAbsent( "status", "active" );

        if ( !articleMeta.containsKey( "related" ) ) {
            final List< String > related = new ArrayList<>();
            if ( hubName != null ) {
                related.add( hubName );
            }
            for ( final String member : existingMembers ) {
                if ( !member.equals( hubName ) ) {
                    related.add( member );
                }
            }
            articleMeta.put( "related", related );
        }

        final Map< String, Object > output = new LinkedHashMap<>();
        output.put( "clusterName", clusterName );
        output.put( "hub", hubName );

        // 3. Create the new article
        try {
            final Page saved = pageSaveHelper.saveText( articleName, articleBody,
                    SaveOptions.builder()
                            .author( effectiveAuthor )
                            .changeNote( changeNote != null ? changeNote : "Extend cluster " + clusterName )
                            .markupSyntax( "markdown" )
                            .metadata( articleMeta )
                            .replaceMetadata( true )
                            .build() );
            output.put( "articleCreated", Map.of(
                    "pageName", articleName, "success", true,
                    "version", saved != null ? McpToolUtils.normalizeVersion( saved.getVersion() ) : 1 ) );
        } catch ( final Exception e ) {
            LOG.error( "Failed to create article {}: {}", articleName, e.getMessage(), e );
            output.put( "articleCreated", Map.of( "pageName", articleName, "success", false, "error", e.getMessage() ) );
            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, output );
        }

        // 4. Patch hub body to add article link
        final List< Map< String, Object > > pagesUpdated = new ArrayList<>();
        if ( hubName != null ) {
            pagesUpdated.add( patchHubBody( hubName, articleName, articleMeta, effectiveAuthor ) );
        }

        // 5. Update related metadata on all existing members (add new article)
        for ( final String member : existingMembers ) {
            final Map< String, Object > updateResult = updateRelatedMetadata(
                    member, articleName, effectiveAuthor );
            if ( updateResult != null ) {
                pagesUpdated.add( updateResult );
            }
        }

        output.put( "pagesUpdated", pagesUpdated );

        // 6. Update Main page
        if ( updateMain ) {
            output.put( "mainPageUpdate", updateMainPage(
                    articleName, articleMeta, mainSection, hubName, existingMembers, effectiveAuthor ) );
        }

        // 7. Verify
        final List< String > warnings = new ArrayList<>();
        if ( hubName != null ) {
            final String hubText = pageManager.getPureText( hubName, PageProvider.LATEST_VERSION );
            final ParsedPage hubParsed = FrontmatterParser.parse( hubText );
            if ( !MarkdownLinkScanner.findLocalLinks( hubParsed.body() ).contains( articleName ) ) {
                warnings.add( "Hub body does not link to new article " + articleName );
            }
        }

        final String savedText = pageManager.getPureText( articleName, PageProvider.LATEST_VERSION );
        if ( savedText != null ) {
            final ParsedPage articleParsed = FrontmatterParser.parse( savedText );
            if ( hubName != null && !MarkdownLinkScanner.findLocalLinks( articleParsed.body() ).contains( hubName ) ) {
                warnings.add( articleName + " body does not link back to hub " + hubName );
            }
        }

        output.put( "verification", Map.of( "warnings", warnings, "allGreen", warnings.isEmpty() ) );

        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, output );
    }

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > patchHubBody( final String hubName, final String articleName,
                                                   final Map< String, Object > articleMeta,
                                                   final String author ) {
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "pageName", hubName );

        try {

            final String rawText = pageManager.getPureText( hubName, PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( rawText );
            String body = parsed.body();
            final Map< String, Object > hubMeta = new LinkedHashMap<>( parsed.metadata() );

            // Build the bullet for the new article
            final Object summary = articleMeta.get( "summary" );
            final String bullet = "- [" + articleName + "](" + articleName + ")" +
                    ( summary != null ? " — " + summary : "" );

            // Try to append to "Cluster Articles" section, or the last bullet list before See Also
            boolean inserted = false;

            // Strategy 1: Append to "Cluster Articles" section
            try {
                body = ContentPatcher.appendToSection( body, "Cluster Articles", bullet + "\n" );
                inserted = true;
            } catch ( final PatchException ignored ) {
                // Section doesn't exist — try alternative
            }

            // Strategy 2: Insert before "See Also" section
            if ( !inserted ) {
                final int seeAlsoIdx = body.indexOf( "## See Also" );
                if ( seeAlsoIdx > 0 ) {
                    body = body.substring( 0, seeAlsoIdx ) + bullet + "\n\n" + body.substring( seeAlsoIdx );
                    inserted = true;
                }
            }

            // Strategy 3: Append to end
            if ( !inserted ) {
                body = body + "\n" + bullet + "\n";
            }

            // Also update hub's related metadata
            final Object existingRelated = hubMeta.get( "related" );
            if ( existingRelated instanceof List ) {
                final List< Object > related = new ArrayList<>( ( List< Object > ) existingRelated );
                if ( !related.contains( articleName ) ) {
                    related.add( articleName );
                    hubMeta.put( "related", related );
                }
            }

            pageSaveHelper.saveText( hubName, body,
                    SaveOptions.builder()
                            .author( author )
                            .changeNote( "Extend cluster: add " + articleName )
                            .markupSyntax( "markdown" )
                            .metadata( hubMeta )
                            .replaceMetadata( true )
                            .build() );

            result.put( "success", true );
            result.put( "detail", "Added article link to hub body and related metadata" );
        } catch ( final com.wikantik.api.exceptions.WikiException e ) {
            LOG.error( "Failed to patch hub {}: {}", hubName, e.getMessage(), e );
            result.put( "success", false );
            result.put( "error", e.getMessage() );
        }

        return result;
    }

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > updateRelatedMetadata( final String pageName, final String newArticle,
                                                            final String author ) {
        try {

            final String rawText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( rawText );
            final Map< String, Object > meta = new LinkedHashMap<>( parsed.metadata() );

            final Object existingRelated = meta.get( "related" );
            if ( existingRelated instanceof List ) {
                final List< Object > related = new ArrayList<>( ( List< Object > ) existingRelated );
                if ( related.contains( newArticle ) ) {
                    return null; // Already has the reference
                }
                related.add( newArticle );
                meta.put( "related", related );
            } else {
                meta.put( "related", List.of( newArticle ) );
            }

            pageSaveHelper.saveText( pageName, parsed.body(),
                    SaveOptions.builder()
                            .author( author )
                            .changeNote( "Add " + newArticle + " to related" )
                            .markupSyntax( "markdown" )
                            .metadata( meta )
                            .replaceMetadata( true )
                            .build() );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "pageName", pageName );
            result.put( "success", true );
            result.put( "detail", "Added " + newArticle + " to related metadata" );
            return result;
        } catch ( final Exception e ) {
            LOG.error( "Failed to update related on {}: {}", pageName, e.getMessage(), e );
            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "pageName", pageName );
            result.put( "success", false );
            result.put( "error", e.getMessage() );
            return result;
        }
    }

    private String updateMainPage( final String articleName, final Map< String, Object > articleMeta,
                                     final String mainSection, final String hubName,
                                     final List< String > existingMembers,
                                     final String author ) {
        try {

            final Page mainPage = pageManager.getPage( "Main" );
            if ( mainPage == null ) {
                return "Main page not found — skipped";
            }

            final String rawText = pageManager.getPureText( "Main", PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( rawText );
            String body = parsed.body();

            final Object summary = articleMeta.get( "summary" );
            final String bullet = "- [" + articleName + "](" + articleName + ")" +
                    ( summary != null ? " — " + summary : "" );

            if ( mainSection != null ) {
                // Insert into specified section
                body = ContentPatcher.appendToSection( body, mainSection, bullet + "\n" );
            } else {
                // Auto-detect: find the section that contains an existing cluster member
                String targetSection = null;
                final List< ContentPatcher.Section > sections = ContentPatcher.findSections( body );
                for ( final ContentPatcher.Section s : sections ) {
                    if ( s.level() == 3 ) {
                        final String[] lines = body.split( "\n", -1 );
                        for ( int i = s.startLine(); i < s.endLine() && i < lines.length; i++ ) {
                            for ( final String member : existingMembers ) {
                                if ( lines[ i ].contains( "(" + member + ")" ) ) {
                                    targetSection = s.heading();
                                    break;
                                }
                            }
                            if ( targetSection != null ) break;
                        }
                    }
                    if ( targetSection != null ) break;
                }

                if ( targetSection != null ) {
                    body = ContentPatcher.appendToSection( body, targetSection, bullet + "\n" );
                } else {
                    return "Could not determine Main page section — add manually";
                }
            }

            pageSaveHelper.saveText( "Main", body,
                    SaveOptions.builder()
                            .author( author )
                            .changeNote( "Add " + articleName + " to Main page" )
                            .markupSyntax( "markdown" )
                            .metadata( new LinkedHashMap<>( parsed.metadata() ) )
                            .replaceMetadata( true )
                            .build() );

            return "success";
        } catch ( final Exception e ) {
            LOG.error( "Failed to update Main page: {}", e.getMessage(), e );
            return "Failed: " + e.getMessage();
        }
    }
}
