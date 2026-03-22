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
import com.wikantik.frontmatter.FrontmatterParser;
import com.wikantik.frontmatter.ParsedPage;
import com.wikantik.pages.PageManager;
import com.wikantik.parser.MarkdownLinkScanner;
import com.wikantik.references.ReferenceManager;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Read-only MCP tool that runs all per-cluster audit checks in a single call:
 * structural integrity, metadata completeness, SEO readiness, staleness,
 * and auto-fixable issue classification.
 *
 * <p>SEO readiness checks use the Strategy pattern — composed {@link PageCheck}
 * instances from {@link PageChecks} — so validation rules are shared with
 * {@link VerifyPagesTool} rather than duplicated.
 */
public class AuditClusterTool implements McpTool {

    public static final String TOOL_NAME = "audit_cluster";

    private static final Set< String > REQUIRED_METADATA_FIELDS =
            Set.of( "type", "tags", "summary", "related", "cluster", "status", "date", "author" );

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final PageManager pageManager;

    public AuditClusterTool( final PageManager pageManager, final ReferenceManager referenceManager ) {
        this.pageManager = pageManager;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > summaryItem = new LinkedHashMap<>();
        summaryItem.put( "type", "object" );
        summaryItem.put( "properties", Map.of(
                "page", Map.of( "type", "string" ),
                "summary", Map.of( "type", "string" ) ) );

        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "cluster", Map.of( "type", "string", "description", "Cluster name to audit" ) );
        properties.put( "stalenessWindow", Map.of( "type", "integer", "description",
                "Days before a non-archived page is flagged as stale (default: 90)" ) );
        properties.put( "stalledDraftWindow", Map.of( "type", "integer", "description",
                "Days before a draft is flagged as stalled (default: 30)" ) );
        properties.put( "allSummaries", Map.of( "type", "array", "description",
                "Summaries from other clusters for duplicate detection",
                "items", summaryItem ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Run all per-cluster audit checks in a single call: " +
                        "structural integrity (missing hub backlinks, broken links), " +
                        "metadata completeness (8 required fields), " +
                        "SEO readiness (summary length, tags, JSON-LD), " +
                        "staleness (stale pages, stalled drafts), " +
                        "and auto-fixable issue classification. " +
                        "Returns structured results with severity counts." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "cluster" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @SuppressWarnings( "unchecked" )
    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String clusterName = McpToolUtils.getString( arguments, "cluster" );
        final int stalenessWindow = McpToolUtils.getInt( arguments, "stalenessWindow", 90 );
        final int stalledDraftWindow = McpToolUtils.getInt( arguments, "stalledDraftWindow", 30 );
        final List< Map< String, Object > > allSummaries =
                ( List< Map< String, Object > > ) arguments.get( "allSummaries" );

        if ( clusterName == null || clusterName.isBlank() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "cluster is required" );
        }

        // Collect all pages in this cluster
        final Collection< Page > allPages;
        try {
            allPages = pageManager.getAllPages();
        } catch ( final Exception e ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "Failed to list pages: " + e.getMessage() );
        }

        final Map< String, Page > clusterPageMap = new LinkedHashMap<>();
        final Map< String, Map< String, Object > > clusterMetadata = new LinkedHashMap<>();
        final Map< String, String > clusterBodies = new LinkedHashMap<>();
        String hubPage = null;

        for ( final Page page : allPages ) {
            final String pageName = page.getName();
            final String rawText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( rawText );
            final Map< String, Object > metadata = parsed.metadata();

            final Object clusterVal = metadata.get( "cluster" );
            if ( clusterVal instanceof String && ( ( String ) clusterVal ).equals( clusterName ) ) {
                clusterPageMap.put( pageName, page );
                clusterMetadata.put( pageName, metadata );
                clusterBodies.put( pageName, parsed.body() );

                if ( "hub".equals( metadata.get( "type" ) ) ) {
                    hubPage = pageName;
                }
            }
        }

        if ( clusterPageMap.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "No pages found for cluster: " + clusterName );
        }

        final List< Map< String, Object > > structural = new ArrayList<>();
        final List< Map< String, Object > > metadata = new ArrayList<>();
        final List< Map< String, Object > > seo = new ArrayList<>();
        final List< Map< String, Object > > staleness = new ArrayList<>();
        final List< Map< String, Object > > autoFixable = new ArrayList<>();
        final List< Map< String, Object > > summaries = new ArrayList<>();

        final Set< String > clusterPageNames = clusterPageMap.keySet();
        final Instant now = Instant.now();

        // Check each page
        for ( final Map.Entry< String, Page > entry : clusterPageMap.entrySet() ) {
            final String pageName = entry.getKey();
            final Page page = entry.getValue();
            final Map< String, Object > pageMeta = clusterMetadata.get( pageName );
            final String body = clusterBodies.get( pageName );

            // Collect summary for cross-cluster dedup
            final Object summaryVal = pageMeta.get( "summary" );
            if ( summaryVal instanceof String ) {
                summaries.add( Map.of( "page", pageName, "summary", ( String ) summaryVal ) );
            }

            // --- 1. Structural checks ---
            checkStructural( pageName, body, hubPage, clusterPageNames, structural, autoFixable );

            // --- 2. Metadata checks ---
            checkMetadata( pageName, pageMeta, clusterName, metadata, autoFixable, page );

            // --- 3. SEO checks (skip drafts) ---
            final String status = pageMeta.get( "status" ) instanceof String ? ( String ) pageMeta.get( "status" ) : null;
            if ( !"draft".equals( status ) ) {
                checkSeo( pageName, pageMeta, seo );
            }

            // --- 4. Staleness checks ---
            checkStaleness( pageName, page, status, stalenessWindow, stalledDraftWindow, now, staleness );
        }

        // Hub structural checks: hub should link to all sub-articles
        if ( hubPage != null ) {
            checkHubLinks( hubPage, clusterBodies.get( hubPage ), clusterPageNames, structural );
        }

        // Count severities
        int critical = structural.size();
        int warning = metadata.size() + seo.size();
        int suggestion = staleness.size();

        final Map< String, Object > summary = new LinkedHashMap<>();
        summary.put( "critical", critical );
        summary.put( "warning", warning );
        summary.put( "suggestion", suggestion );
        summary.put( "autoFixable", autoFixable.size() );

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "cluster", clusterName );
        result.put( "pageCount", clusterPageMap.size() );
        result.put( "structural", structural );
        result.put( "metadata", metadata );
        result.put( "seo", seo );
        result.put( "staleness", staleness );
        result.put( "autoFixable", autoFixable );
        result.put( "summaries", summaries );
        result.put( "summary", summary );

        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
    }

    private void checkStructural( final String pageName, final String body,
                                    final String hubPage, final Set< String > clusterPageNames,
                                    final List< Map< String, Object > > structural,
                                    final List< Map< String, Object > > autoFixable ) {
        if ( hubPage == null || pageName.equals( hubPage ) ) {
            return;
        }

        // Check if sub-article links back to hub
        final Set< String > localLinks = MarkdownLinkScanner.findLocalLinks( body );
        if ( !localLinks.contains( hubPage ) ) {
            structural.add( Map.of( "page", pageName, "issue", "missing_hub_backlink",
                    "detail", "Sub-article does not link back to hub " + hubPage ) );

            // Check if See Also section exists — if so, auto-fixable
            if ( body.contains( "## See Also" ) ) {
                autoFixable.add( Map.of( "page", pageName, "action", "add_hub_backlink",
                        "hubPage", hubPage,
                        "reason", "Has See Also section but missing hub backlink" ) );
            }
        }

        // Check for broken internal links
        for ( final String link : localLinks ) {
            if ( pageManager.getPage( link ) == null ) {
                structural.add( Map.of( "page", pageName, "issue", "broken_link",
                        "detail", "Links to non-existent page: " + link ) );

                // Check for CamelCase typo with exactly 1 correction candidate
                final String suggestion = findTypoCorrection( link, clusterPageNames );
                if ( suggestion != null ) {
                    autoFixable.add( Map.of( "page", pageName, "action", "fix_typo_link",
                            "brokenLink", link, "correctedLink", suggestion,
                            "reason", "CamelCase typo with exactly one correction candidate" ) );
                }
            }
        }
    }

    private void checkHubLinks( final String hubPage, final String hubBody,
                                  final Set< String > clusterPageNames,
                                  final List< Map< String, Object > > structural ) {
        final Set< String > hubLinks = MarkdownLinkScanner.findLocalLinks( hubBody );
        for ( final String pageName : clusterPageNames ) {
            if ( pageName.equals( hubPage ) ) {
                continue;
            }
            if ( !hubLinks.contains( pageName ) ) {
                structural.add( Map.of( "page", hubPage, "issue", "hub_missing_sub_link",
                        "detail", "Hub does not link to sub-article: " + pageName ) );
            }
        }
    }

    @SuppressWarnings( "unchecked" )
    private void checkMetadata( final String pageName, final Map< String, Object > pageMeta,
                                  final String clusterName,
                                  final List< Map< String, Object > > metadata,
                                  final List< Map< String, Object > > autoFixable,
                                  final Page page ) {
        for ( final String field : REQUIRED_METADATA_FIELDS ) {
            final Object val = pageMeta.get( field );
            final boolean missing = val == null
                    || ( val instanceof String && ( ( String ) val ).isBlank() )
                    || ( val instanceof List && ( ( List< ? > ) val ).isEmpty() );

            if ( missing ) {
                final Map< String, Object > issue = new LinkedHashMap<>();
                issue.put( "page", pageName );
                issue.put( "field", field );
                issue.put( "issue", "missing" );
                issue.put( "currentValue", val );
                metadata.add( issue );

                // Auto-fixable classification for certain fields
                if ( "status".equals( field ) ) {
                    autoFixable.add( Map.of( "page", pageName, "action", "set_metadata",
                            "field", "status", "proposedValue", "active",
                            "reason", "Missing status field — defaulting to active" ) );
                } else if ( "date".equals( field ) ) {
                    final String dateStr = formatDate( page.getLastModified() );
                    if ( dateStr != null ) {
                        autoFixable.add( Map.of( "page", pageName, "action", "set_metadata",
                                "field", "date", "proposedValue", dateStr,
                                "reason", "Missing date — using last modified date" ) );
                    }
                } else if ( "cluster".equals( field ) ) {
                    autoFixable.add( Map.of( "page", pageName, "action", "set_metadata",
                            "field", "cluster", "proposedValue", clusterName,
                            "reason", "Page in cluster but missing cluster field" ) );
                }
            }
        }

        // Summary length checks
        final Object summaryVal = pageMeta.get( "summary" );
        if ( summaryVal instanceof String ) {
            final int len = ( ( String ) summaryVal ).length();
            if ( len < 50 ) {
                metadata.add( Map.of( "page", pageName, "field", "summary",
                        "issue", "too_short", "currentValue", summaryVal ) );
            } else if ( len > 160 ) {
                metadata.add( Map.of( "page", pageName, "field", "summary",
                        "issue", "too_long", "currentValue", summaryVal ) );
            }
        }

        // Author == "MCP" check
        if ( "MCP".equals( pageMeta.get( "author" ) ) ) {
            metadata.add( Map.of( "page", pageName, "field", "author",
                    "issue", "default_author", "currentValue", "MCP" ) );
        }

        // Cluster value mismatch
        final Object clusterVal = pageMeta.get( "cluster" );
        if ( clusterVal instanceof String && !clusterName.equals( clusterVal ) ) {
            metadata.add( Map.of( "page", pageName, "field", "cluster",
                    "issue", "mismatch", "currentValue", clusterVal ) );
        }
    }

    /** SEO checks — shared implementations from {@link PageChecks}. No length bounds here
     *  because the metadata check already flags short/long summaries. */
    private static final List< PageCheck > SEO_CHECKS = List.of(
            new PageChecks.SummaryCheck( false ),
            new PageChecks.TagsCheck(),
            new PageChecks.HubRelatedCheck( false )
    );

    private void checkSeo( final String pageName, final Map< String, Object > pageMeta,
                             final List< Map< String, Object > > seo ) {
        final PageCheckContext ctx = new PageCheckContext( pageName, pageMeta, "", null, pageManager );
        for ( final PageCheck check : SEO_CHECKS ) {
            for ( final PageCheckResult result : check.check( ctx ) ) {
                seo.add( Map.of( "page", pageName, "warning", result.detail() ) );
            }
        }
    }

    private void checkStaleness( final String pageName, final Page page, final String status,
                                   final int stalenessWindow, final int stalledDraftWindow,
                                   final Instant now,
                                   final List< Map< String, Object > > staleness ) {
        if ( page.getLastModified() == null ) {
            return;
        }

        final long daysSince = ChronoUnit.DAYS.between( page.getLastModified().toInstant(), now );

        if ( "draft".equals( status ) && daysSince > stalledDraftWindow ) {
            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "page", pageName );
            entry.put( "lastModified", formatDate( page.getLastModified() ) );
            entry.put( "daysSinceModification", daysSince );
            entry.put( "status", "draft" );
            staleness.add( entry );
        } else if ( !"archived".equals( status ) && !"draft".equals( status ) && daysSince > stalenessWindow ) {
            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "page", pageName );
            entry.put( "lastModified", formatDate( page.getLastModified() ) );
            entry.put( "daysSinceModification", daysSince );
            entry.put( "status", status );
            staleness.add( entry );
        }
    }

    /**
     * Attempts to find a single correction candidate for a broken CamelCase link.
     * Returns the correction if exactly one candidate exists; null otherwise.
     */
    private String findTypoCorrection( final String brokenLink, final Set< String > knownPages ) {
        String candidate = null;
        int matches = 0;
        final String brokenLower = brokenLink.toLowerCase();

        for ( final String pageName : knownPages ) {
            if ( pageName.toLowerCase().equals( brokenLower ) ) {
                candidate = pageName;
                matches++;
            } else {
                // Check edit distance 1 (single char difference)
                if ( editDistance1( brokenLink, pageName ) ) {
                    candidate = pageName;
                    matches++;
                }
            }
            if ( matches > 1 ) {
                return null; // Ambiguous — not auto-fixable
            }
        }

        // Also check all wiki pages (not just cluster)
        if ( matches == 0 ) {
            try {
                for ( final Page page : pageManager.getAllPages() ) {
                    final String name = page.getName();
                    if ( name.toLowerCase().equals( brokenLower ) || editDistance1( brokenLink, name ) ) {
                        candidate = name;
                        matches++;
                    }
                    if ( matches > 1 ) {
                        return null;
                    }
                }
            } catch ( final Exception e ) {
                // Ignore — can't search further
            }
        }

        return matches == 1 ? candidate : null;
    }

    private static boolean editDistance1( final String a, final String b ) {
        if ( Math.abs( a.length() - b.length() ) > 1 ) {
            return false;
        }
        int diffs = 0;
        int i = 0, j = 0;
        while ( i < a.length() && j < b.length() ) {
            if ( a.charAt( i ) != b.charAt( j ) ) {
                diffs++;
                if ( diffs > 1 ) {
                    return false;
                }
                if ( a.length() > b.length() ) {
                    i++;
                } else if ( b.length() > a.length() ) {
                    j++;
                } else {
                    i++;
                    j++;
                }
            } else {
                i++;
                j++;
            }
        }
        diffs += ( a.length() - i ) + ( b.length() - j );
        return diffs == 1;
    }

    private static String formatDate( final java.util.Date date ) {
        if ( date == null ) {
            return null;
        }
        return LocalDate.ofInstant( date.toInstant(), ZoneId.systemDefault() )
                .format( DateTimeFormatter.ISO_LOCAL_DATE );
    }
}
