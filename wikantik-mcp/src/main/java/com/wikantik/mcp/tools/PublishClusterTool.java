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
 * Compound MCP tool that creates an entire article cluster in a single call:
 * writes hub + articles, auto-sets cluster metadata (type, cluster, related, status),
 * optionally updates the Main page, and verifies the result.
 */
public class PublishClusterTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( PublishClusterTool.class );
    public static final String TOOL_NAME = "publish_cluster";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final PageSaveHelper pageSaveHelper;
    private final PageManager pageManager;

    private String defaultAuthor = "MCP";

    public PublishClusterTool( final PageSaveHelper pageSaveHelper, final PageManager pageManager ) {
        this.pageSaveHelper = pageSaveHelper;
        this.pageManager = pageManager;
    }

    @Override
    public void setDefaultAuthor( final String defaultAuthor ) {
        this.defaultAuthor = defaultAuthor;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > pageSchema = new LinkedHashMap<>();
        pageSchema.put( "type", "object" );
        pageSchema.put( "properties", Map.of(
                "name", Map.of( "type", "string", "description", "CamelCase page name" ),
                "body", Map.of( "type", "string", "description", "Markdown body content (without frontmatter)" ),
                "metadata", Map.of( "type", "object", "description",
                        "Frontmatter fields (tags, summary, date, author, etc.). " +
                        "type, cluster, related, and status are auto-set if omitted." )
        ) );
        pageSchema.put( "required", List.of( "name", "body" ) );

        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "clusterName", Map.of( "type", "string", "description",
                "Kebab-case cluster identifier (e.g., 'retirement-planning')" ) );
        properties.put( "hub", pageSchema );
        properties.put( "articles", Map.of( "type", "array", "description",
                "Article page definitions", "items", pageSchema ) );
        properties.put( "updateMain", Map.of( "type", "boolean", "description",
                "Patch the Main page to list this cluster (default: true)" ) );
        properties.put( "mainSection", Map.of( "type", "string", "description",
                "Section heading on Main page under which to insert (e.g., 'Technology'). " +
                "If omitted, a new section is created alphabetically under 'Article Clusters'." ) );
        properties.put( "author", Map.of( "type", "string", "description",
                "Author name for all pages (defaults to MCP client name)" ) );
        properties.put( "changeNote", Map.of( "type", "string", "description",
                "Optional change note" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Create an entire article cluster in a single call. " +
                        "Writes hub + articles, auto-sets cluster metadata " +
                        "(type, cluster, related, status default to appropriate values if omitted), " +
                        "optionally updates the Main page, and verifies the result. " +
                        "Returns creation results and any verification warnings." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "clusterName", "hub", "articles" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
                .build();
    }

    @SuppressWarnings( "unchecked" )
    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String clusterName = McpToolUtils.getString( arguments, "clusterName" );
        final Map< String, Object > hubSpec = ( Map< String, Object > ) arguments.get( "hub" );
        final List< Map< String, Object > > articleSpecs =
                ( List< Map< String, Object > > ) arguments.get( "articles" );
        final boolean updateMain = arguments.get( "updateMain" ) == null
                || Boolean.TRUE.equals( arguments.get( "updateMain" ) );
        final String mainSection = McpToolUtils.getString( arguments, "mainSection" );
        final String author = McpToolUtils.getString( arguments, "author" );
        final String changeNote = McpToolUtils.getString( arguments, "changeNote" );
        final String effectiveAuthor = author != null ? author : defaultAuthor;

        if ( clusterName == null || clusterName.isBlank() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "clusterName is required" );
        }
        if ( hubSpec == null ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "hub is required" );
        }
        if ( articleSpecs == null || articleSpecs.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "At least one article is required" );
        }

        final String hubName = ( String ) hubSpec.get( "name" );
        final String hubBody = ( String ) hubSpec.get( "body" );
        final Map< String, Object > hubMeta = hubSpec.get( "metadata" ) != null
                ? new LinkedHashMap<>( ( Map< String, Object > ) hubSpec.get( "metadata" ) )
                : new LinkedHashMap<>();

        // Collect all article names
        final List< String > articleNames = new ArrayList<>();
        for ( final Map< String, Object > spec : articleSpecs ) {
            articleNames.add( ( String ) spec.get( "name" ) );
        }

        // Auto-set hub metadata
        hubMeta.put( "type", "hub" );
        hubMeta.put( "cluster", clusterName );
        hubMeta.putIfAbsent( "status", "active" );
        hubMeta.putIfAbsent( "related", articleNames );

        final List< Map< String, Object > > results = new ArrayList<>();

        // 1. Create hub page
        results.add( savePage( hubName, hubBody, hubMeta, effectiveAuthor,
                changeNote != null ? changeNote : "Publish cluster " + clusterName + ": hub" ) );

        // 2. Create article pages with auto-set metadata
        for ( final Map< String, Object > spec : articleSpecs ) {
            final String articleName = ( String ) spec.get( "name" );
            final String articleBody = ( String ) spec.get( "body" );
            final Map< String, Object > articleMeta = spec.get( "metadata" ) != null
                    ? new LinkedHashMap<>( ( Map< String, Object > ) spec.get( "metadata" ) )
                    : new LinkedHashMap<>();

            articleMeta.putIfAbsent( "type", "article" );
            articleMeta.put( "cluster", clusterName );
            articleMeta.putIfAbsent( "status", "active" );

            // Auto-populate related: hub + all sibling articles
            if ( !articleMeta.containsKey( "related" ) ) {
                final List< String > related = new ArrayList<>();
                related.add( hubName );
                for ( final String sibling : articleNames ) {
                    if ( !sibling.equals( articleName ) ) {
                        related.add( sibling );
                    }
                }
                articleMeta.put( "related", related );
            }

            results.add( savePage( articleName, articleBody, articleMeta, effectiveAuthor,
                    changeNote != null ? changeNote : "Publish cluster " + clusterName + ": article" ) );
        }

        // 3. Optionally update Main page
        String mainUpdateResult = null;
        if ( updateMain ) {
            mainUpdateResult = updateMainPage( clusterName, hubName, hubMeta,
                    articleSpecs, mainSection, effectiveAuthor );
        }

        // 4. Verify: collect warnings
        final List< String > warnings = verify( hubName, articleNames );

        // Build result
        final Map< String, Object > output = new LinkedHashMap<>();
        output.put( "clusterName", clusterName );
        output.put( "results", results );
        if ( mainUpdateResult != null ) {
            output.put( "mainPageUpdate", mainUpdateResult );
        }
        output.put( "verification", Map.of(
                "warnings", warnings,
                "allGreen", warnings.isEmpty()
        ) );

        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, output );
    }

    private Map< String, Object > savePage( final String pageName, final String body,
                                              final Map< String, Object > metadata,
                                              final String author, final String changeNote ) {
        final Map< String, Object > entry = new LinkedHashMap<>();
        entry.put( "pageName", pageName );

        try {
            final Page saved = pageSaveHelper.saveText( pageName, body,
                    SaveOptions.builder()
                            .author( author )
                            .changeNote( changeNote )
                            .markupSyntax( "markdown" )
                            .metadata( metadata )
                            .replaceMetadata( true )
                            .build() );

            entry.put( "success", true );
            entry.put( "version", saved != null ? McpToolUtils.normalizeVersion( saved.getVersion() ) : 1 );
        } catch ( final Exception e ) {
            LOG.error( "Failed to create page {}: {}", pageName, e.getMessage(), e );
            entry.put( "success", false );
            entry.put( "error", e.getMessage() );
        }

        return entry;
    }

    @SuppressWarnings( "unchecked" )
    private String updateMainPage( final String clusterName, final String hubName,
                                     final Map< String, Object > hubMeta,
                                     final List< Map< String, Object > > articleSpecs,
                                     final String mainSection, final String author ) {
        try {
            final Page mainPage = pageManager.getPage( "Main" );
            if ( mainPage == null ) {
                return "Main page not found — skipped";
            }

            final String rawText = pageManager.getPureText( "Main", PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( rawText );
            String body = parsed.body();

            // Build the cluster bullet list
            final StringBuilder bullets = new StringBuilder();
            final Object hubSummary = hubMeta.get( "summary" );
            bullets.append( "- [" ).append( hubName ).append( "](" ).append( hubName ).append( ")" );
            if ( hubSummary != null ) {
                bullets.append( " — " ).append( hubSummary );
            }
            bullets.append( "\n" );

            for ( final Map< String, Object > spec : articleSpecs ) {
                final String articleName = ( String ) spec.get( "name" );
                final Map< String, Object > articleMeta = ( Map< String, Object > ) spec.get( "metadata" );
                final Object articleSummary = articleMeta != null ? articleMeta.get( "summary" ) : null;
                bullets.append( "- [" ).append( articleName ).append( "](" ).append( articleName ).append( ")" );
                if ( articleSummary != null ) {
                    bullets.append( " — " ).append( articleSummary );
                }
                bullets.append( "\n" );
            }

            // Insert into Main page
            if ( mainSection != null ) {
                // Append to existing section
                try {
                    body = ContentPatcher.appendToSection( body, mainSection, bullets.toString() );
                } catch ( final PatchException e ) {
                    return "Section '" + mainSection + "' not found on Main page: " + e.getMessage();
                }
            } else {
                // Create a new section under "Article Clusters"
                final String sectionTitle = clusterDisplayName( clusterName );
                final String newSection = "\n### " + sectionTitle + "\n\n" + bullets;

                // Try to insert alphabetically among existing ### sections under "Article Clusters"
                final List< ContentPatcher.Section > sections = ContentPatcher.findSections( body );
                int insertPos = -1;

                for ( final ContentPatcher.Section s : sections ) {
                    if ( s.level() == 3 && s.heading().compareTo( sectionTitle ) > 0 ) {
                        // This section comes after ours alphabetically — insert before it
                        final String[] lines = body.split( "\n", -1 );
                        final StringBuilder sb = new StringBuilder();
                        for ( int i = 0; i < s.startLine(); i++ ) {
                            sb.append( lines[ i ] ).append( "\n" );
                        }
                        sb.append( newSection );
                        for ( int i = s.startLine(); i < lines.length; i++ ) {
                            sb.append( lines[ i ] );
                            if ( i < lines.length - 1 ) {
                                sb.append( "\n" );
                            }
                        }
                        body = sb.toString();
                        insertPos = s.startLine();
                        break;
                    }
                }

                if ( insertPos == -1 ) {
                    // Append at end of "Article Clusters" section
                    try {
                        body = ContentPatcher.appendToSection( body, "Article Clusters", newSection );
                    } catch ( final PatchException e ) {
                        // No "Article Clusters" section — just append to end
                        body = body + "\n" + newSection;
                    }
                }
            }

            pageSaveHelper.saveText( "Main", body,
                    SaveOptions.builder()
                            .author( author )
                            .changeNote( "Add " + clusterName + " cluster to Main page" )
                            .markupSyntax( "markdown" )
                            .metadata( new LinkedHashMap<>( parsed.metadata() ) )
                            .replaceMetadata( true )
                            .build() );

            return "success";
        } catch ( final com.wikantik.api.exceptions.WikiException e ) {
            LOG.error( "Failed to update Main page: {}", e.getMessage(), e );
            return "Failed: " + e.getMessage();
        }
    }

    private List< String > verify( final String hubName, final List< String > articleNames ) {
        final List< String > warnings = new ArrayList<>();

        // Check hub exists
        if ( pageManager.getPage( hubName ) == null ) {
            warnings.add( "Hub page " + hubName + " was not created" );
            return warnings;
        }

        // Check each article exists and has backlink to hub in body
        for ( final String articleName : articleNames ) {
            if ( pageManager.getPage( articleName ) == null ) {
                warnings.add( "Article " + articleName + " was not created" );
                continue;
            }

            final String body = pageManager.getPureText( articleName, PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( body );
            final Set< String > localLinks = MarkdownLinkScanner.findLocalLinks( parsed.body() );

            if ( !localLinks.contains( hubName ) ) {
                warnings.add( articleName + " body does not link back to hub " + hubName +
                        " — add a See Also section with [" + hubName + "](" + hubName + ")" );
            }
        }

        // Check hub body links to all articles
        final String hubText = pageManager.getPureText( hubName, PageProvider.LATEST_VERSION );
        final ParsedPage hubParsed = FrontmatterParser.parse( hubText );
        final Set< String > hubLinks = MarkdownLinkScanner.findLocalLinks( hubParsed.body() );
        for ( final String articleName : articleNames ) {
            if ( !hubLinks.contains( articleName ) ) {
                warnings.add( "Hub body does not link to article " + articleName );
            }
        }

        return warnings;
    }

    private static String clusterDisplayName( final String clusterName ) {
        // Convert kebab-case to Title Case: "hobby-woodworking" → "Hobby Woodworking"
        final String[] parts = clusterName.split( "-" );
        final StringBuilder sb = new StringBuilder();
        for ( final String part : parts ) {
            if ( !part.isEmpty() ) {
                if ( sb.length() > 0 ) {
                    sb.append( " " );
                }
                sb.append( Character.toUpperCase( part.charAt( 0 ) ) );
                if ( part.length() > 1 ) {
                    sb.append( part.substring( 1 ) );
                }
            }
        }
        return sb.toString();
    }
}
