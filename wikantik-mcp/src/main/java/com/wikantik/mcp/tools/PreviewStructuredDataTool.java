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
import com.wikantik.api.core.Page;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Read-only MCP tool that previews what structured data a page's frontmatter
 * produces in the HTML output layer. Shows meta tags, Open Graph, JSON-LD,
 * breadcrumbs, Atom feed, and News Sitemap eligibility — the feedback loop
 * so agents can see the SEO impact of their metadata before moving on.
 */
public class PreviewStructuredDataTool implements McpTool {

    public static final String TOOL_NAME = "preview_structured_data";

    // Keep in sync with NEWS_CUTOFF_DAYS
    private static final int NEWS_CUTOFF_DAYS = 2;

    private final PageManager pageManager;
    private final String applicationName;
    private final String baseUrl;

    public PreviewStructuredDataTool( final PageManager pageManager, final String applicationName,
                                      final String baseUrl ) {
        this.pageManager = pageManager;
        this.applicationName = applicationName;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string",
                "description", "Page name to preview structured data for" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Preview what structured data a page's frontmatter metadata produces " +
                        "in the HTML output: meta tags, Open Graph, JSON-LD, breadcrumbs, Atom feed " +
                        "inclusion, and Google News Sitemap eligibility. Use to verify SEO impact " +
                        "before moving on." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "pageName" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String pageName = McpToolUtils.getString( arguments, "pageName" );
        if ( pageName == null || pageName.isBlank() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Missing required parameter: pageName" );
        }

        final Page page = pageManager.getPage( pageName );
        if ( page == null ) {
            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "pageName", pageName );
            result.put( "exists", false );
            result.put( "error", "Page does not exist" );
            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
        }

        final String rawText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
        final ParsedPage parsed = FrontmatterParser.parse( rawText != null ? rawText : "" );
        final Map< String, Object > metadata = parsed.metadata();

        final String appName = applicationName;
        final String normalizedBaseUrl = normalizeBaseUrl( baseUrl );

        final String summary = getStringField( metadata, "summary" );
        final String pageType = getStringField( metadata, "type" );
        final String cluster = getStringField( metadata, "cluster" );
        final String date = getStringField( metadata, "date" );
        final List< String > tags = getListField( metadata, "tags" );
        final List< String > related = getListField( metadata, "related" );
        final boolean isHub = "hub".equals( pageType );

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "pageName", pageName );
        result.put( "exists", true );

        // Meta description
        result.put( "metaDescription", buildMetaDescription( pageName, appName, summary ) );

        // Meta keywords
        if ( !tags.isEmpty() ) {
            result.put( "metaKeywords", String.join( ", ", tags ) );
        }

        // Open Graph
        result.put( "openGraph", buildOpenGraph( pageName, appName, summary, tags ) );

        // JSON-LD
        result.put( "jsonLd", buildJsonLd( pageName, appName, normalizedBaseUrl, summary, tags, date,
                cluster, related, isHub ) );

        // Breadcrumb (clustered non-hub pages only)
        if ( cluster != null && !isHub ) {
            result.put( "breadcrumbList", List.of( "Home", cluster, pageName ) );
        }

        // Atom feed
        result.put( "atomFeed", buildAtomFeed( summary, tags ) );

        // News sitemap eligibility
        result.put( "newsSitemap", buildNewsSitemap( page, tags, metadata ) );

        // Warnings (same checks as seo_readiness)
        result.put( "warnings", buildWarnings( summary, tags, date, cluster, pageType, related ) );

        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
    }

    private Map< String, Object > buildMetaDescription( final String pageName, final String appName,
                                                          final String summary ) {
        final Map< String, Object > desc = new LinkedHashMap<>();
        if ( summary != null ) {
            desc.put( "text", summary );
            desc.put( "source", "frontmatter_summary" );
            desc.put( "length", summary.length() );
        } else {
            final String fallback = pageName + " - " + appName + " wiki page.";
            desc.put( "text", fallback );
            desc.put( "source", "generic_fallback" );
            desc.put( "length", fallback.length() );
        }
        return desc;
    }

    private Map< String, Object > buildOpenGraph( final String pageName, final String appName,
                                                    final String summary, final List< String > tags ) {
        final Map< String, Object > og = new LinkedHashMap<>();
        og.put( "title", pageName + " - " + appName );
        og.put( "type", "article" );
        og.put( "description", summary != null ? summary : pageName + " - A page on " + appName + "." );
        if ( !tags.isEmpty() ) {
            og.put( "articleTags", tags );
        }
        return og;
    }

    private Map< String, Object > buildJsonLd( final String pageName, final String appName,
                                                 final String baseUrl, final String summary,
                                                 final List< String > tags, final String date,
                                                 final String cluster, final List< String > related,
                                                 final boolean isHub ) {
        final Map< String, Object > ld = new LinkedHashMap<>();
        ld.put( "@type", isHub ? "CollectionPage" : "Article" );
        ld.put( "headline", pageName );

        if ( summary != null ) {
            ld.put( "description", summary );
        }
        if ( !tags.isEmpty() ) {
            ld.put( "keywords", String.join( ", ", tags ) );
        }
        if ( date != null ) {
            ld.put( "datePublished", date );
        }
        if ( cluster != null ) {
            ld.put( "articleSection", cluster );
        }

        if ( isHub && !related.isEmpty() ) {
            final List< Map< String, String > > parts = new ArrayList<>();
            for ( final String rel : related ) {
                parts.add( Map.of( "@type", "Article", "name", rel,
                        "url", baseUrl + "/wiki/" + rel ) );
            }
            ld.put( "hasPart", parts );
        }

        if ( !isHub && cluster != null ) {
            ld.put( "isPartOf", Map.of( "@type", "CollectionPage", "name", cluster ) );
        }

        if ( !isHub && !related.isEmpty() ) {
            final List< String > links = new ArrayList<>();
            for ( final String rel : related ) {
                links.add( baseUrl + "/wiki/" + rel );
            }
            ld.put( "relatedLink", links );
        }

        return ld;
    }

    private Map< String, Object > buildAtomFeed( final String summary, final List< String > tags ) {
        final Map< String, Object > atom = new LinkedHashMap<>();
        atom.put( "included", true );
        atom.put( "hasSummary", summary != null );
        if ( !tags.isEmpty() ) {
            atom.put( "categoryTags", tags );
        }
        return atom;
    }

    private Map< String, Object > buildNewsSitemap( final Page page, final List< String > tags,
                                                      final Map< String, Object > metadata ) {
        final Map< String, Object > news = new LinkedHashMap<>();

        if ( tags.isEmpty() ) {
            news.put( "eligible", false );
            news.put( "reason", "No tags — pages without frontmatter tags never appear in News Sitemap" );
            return news;
        }

        if ( metadata.isEmpty() ) {
            news.put( "eligible", false );
            news.put( "reason", "No frontmatter metadata" );
            return news;
        }

        final long cutoffMillis = System.currentTimeMillis()
                - ( ( long ) NEWS_CUTOFF_DAYS * 24 * 60 * 60 * 1000 );
        if ( page.getLastModified() != null && page.getLastModified().getTime() >= cutoffMillis ) {
            news.put( "eligible", true );
            news.put( "reason", "Has tags and modified within " + NEWS_CUTOFF_DAYS + " days" );
        } else {
            news.put( "eligible", false );
            news.put( "reason", "Page not modified within the last " + NEWS_CUTOFF_DAYS + " days" );
        }

        return news;
    }

    private List< String > buildWarnings( final String summary, final List< String > tags,
                                            final String date, final String cluster,
                                            final String pageType, final List< String > related ) {
        final List< String > warnings = new ArrayList<>();

        if ( summary == null ) {
            warnings.add( "No summary — no meta description for search engines" );
        } else {
            final int len = summary.length();
            if ( len < 50 ) {
                warnings.add( "Summary too short (" + len + " chars) — aim for 50-160" );
            } else if ( len > 160 ) {
                warnings.add( "Summary too long (" + len + " chars) — Google will truncate at ~155" );
            } else {
                warnings.add( "Summary is " + len + " chars (good: 50-160 range)" );
            }
        }

        if ( tags.isEmpty() ) {
            warnings.add( "No tags — not eligible for News Sitemap" );
        }

        if ( date == null ) {
            warnings.add( "No date — JSON-LD will lack datePublished" );
        }

        if ( "hub".equals( pageType ) && related.isEmpty() ) {
            warnings.add( "Hub page has no related pages — CollectionPage JSON-LD will have empty hasPart" );
        }

        if ( cluster != null && pageType == null ) {
            warnings.add( "Has cluster but no type — set type to 'article' or 'hub'" );
        }

        return warnings;
    }

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern( "yyyy-MM-dd" );

    private static String getStringField( final Map< String, Object > metadata, final String key ) {
        final Object val = metadata.get( key );
        if ( val == null ) {
            return null;
        }
        // SnakeYAML parses ISO dates as java.util.Date — convert back to string
        if ( val instanceof java.util.Date dateVal ) {
            return ISO_DATE.format( dateVal.toInstant().atZone( ZoneId.systemDefault() ).toLocalDate() );
        }
        return val.toString();
    }

    @SuppressWarnings( "unchecked" )
    private static List< String > getListField( final Map< String, Object > metadata, final String key ) {
        final Object val = metadata.get( key );
        if ( val instanceof List< ? > list && !list.isEmpty() ) {
            final List< String > result = new ArrayList<>();
            for ( final Object item : list ) {
                result.add( item.toString() );
            }
            return result;
        }
        return List.of();
    }

    private static String normalizeBaseUrl( final String baseUrl ) {
        if ( baseUrl == null ) {
            return "http://localhost";
        }
        return baseUrl.endsWith( "/" ) ? baseUrl.substring( 0, baseUrl.length() - 1 ) : baseUrl;
    }
}
