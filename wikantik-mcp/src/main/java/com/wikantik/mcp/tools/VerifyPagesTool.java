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
import com.wikantik.api.managers.ReferenceManager;

import java.util.*;

/**
 * Compound read-only MCP tool that verifies the state of multiple wiki pages in a single call.
 * Checks existence, broken links, backlinks, outbound links, metadata completeness,
 * and SEO readiness.
 *
 * <p>SEO readiness checks use the Strategy pattern — composed {@link PageCheck}
 * instances from {@link PageChecks} — so validation rules are shared with
 * {@link AuditClusterTool} rather than duplicated.
 */
public class VerifyPagesTool implements McpTool {

    public static final String TOOL_NAME = "verify_pages";

    private static final Set< String > STANDARD_METADATA_FIELDS =
            Set.of( "type", "tags", "summary" );

    /** Composed SEO checks — shared implementations from {@link PageChecks}. */
    private static final List< PageCheck > SEO_CHECKS = List.of(
            new PageChecks.SummaryCheck( true ),
            new PageChecks.TagsCheck(),
            new PageChecks.HubRelatedCheck( true ),
            new PageChecks.DateCheck(),
            new PageChecks.ClusterTypeCheck()
    );

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final PageManager pageManager;
    private final ReferenceManager referenceManager;

    public VerifyPagesTool( final PageManager pageManager, final ReferenceManager referenceManager ) {
        this.pageManager = pageManager;
        this.referenceManager = referenceManager;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageNames", Map.of( "type", "array", "description",
                "Array of page names to verify", "items", Map.of( "type", "string" ) ) );
        properties.put( "checks", Map.of( "type", "array", "description",
                "Optional subset of checks to run. Defaults to all. " +
                        "Valid values: existence, broken_links, backlinks, outbound_links, metadata_completeness, seo_readiness",
                "items", Map.of( "type", "string" ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Verify the state of multiple wiki pages in a single call. " +
                        "Checks existence, broken links, backlinks, outbound links, and metadata completeness. " +
                        "Returns per-page details and a summary. Use after creating or updating pages to confirm integrity." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "pageNames" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @SuppressWarnings( "unchecked" )
    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final List< String > pageNames = ( List< String > ) arguments.get( "pageNames" );
        final List< String > checks = ( List< String > ) arguments.get( "checks" );

        if ( pageNames == null || pageNames.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "No page names provided",
                    "Provide an array of page names in the pageNames parameter." );
        }

        final Set< String > activeChecks = checks != null && !checks.isEmpty()
                ? new HashSet<>( checks )
                : Set.of( "existence", "broken_links", "backlinks", "outbound_links", "metadata_completeness" );

        final List< Map< String, Object > > pageResults = new ArrayList<>();
        int totalBrokenLinks = 0;
        final List< String > pagesWithNoBacklinks = new ArrayList<>();
        final List< String > metadataIssues = new ArrayList<>();
        final List< String > seoIssues = new ArrayList<>();
        boolean allExist = true;

        for ( final String pageName : pageNames ) {
            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "pageName", pageName );

            final Page page = pageManager.getPage( pageName );
            final boolean exists = page != null;
            entry.put( "exists", exists );

            if ( exists ) {
                entry.put( "version", McpToolUtils.normalizeVersion( page.getVersion() ) );
            } else {
                allExist = false;
                pageResults.add( entry );
                continue;
            }

            totalBrokenLinks += checkBrokenLinks( pageName, entry, activeChecks );

            checkBacklinks( pageName, entry, activeChecks, pagesWithNoBacklinks );

            // Parse frontmatter once for metadata_completeness and/or seo_readiness
            final boolean needsMetadata = activeChecks.contains( "metadata_completeness" )
                    || activeChecks.contains( "seo_readiness" );
            Map< String, Object > metadata = Map.of();
            if ( needsMetadata ) {
                final String rawText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
                final ParsedPage parsed = FrontmatterParser.parse( rawText );
                metadata = parsed.metadata();
            }

            checkMetadata( pageName, metadata, entry, activeChecks, metadataIssues );

            checkSeoReadiness( pageName, metadata, page, entry, activeChecks, seoIssues );

            pageResults.add( entry );
        }

        final Map< String, Object > summary = new LinkedHashMap<>();
        summary.put( "totalPages", pageNames.size() );
        summary.put( "allExist", allExist );
        if ( activeChecks.contains( "broken_links" ) ) {
            summary.put( "totalBrokenLinks", totalBrokenLinks );
        }
        if ( activeChecks.contains( "backlinks" ) ) {
            summary.put( "pagesWithNoBacklinks", pagesWithNoBacklinks );
        }
        if ( activeChecks.contains( "metadata_completeness" ) ) {
            summary.put( "metadataIssues", metadataIssues );
        }
        if ( activeChecks.contains( "seo_readiness" ) ) {
            summary.put( "seoIssues", seoIssues );
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "pages", pageResults );
        result.put( "summary", summary );

        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
    }

    /**
     * Check outbound and broken links for a page, populating the entry map.
     *
     * @return the number of broken links found (for aggregation into the summary)
     */
    int checkBrokenLinks( final String pageName, final Map< String, Object > entry,
                          final Set< String > activeChecks ) {
        if ( !activeChecks.contains( "outbound_links" ) && !activeChecks.contains( "broken_links" ) ) {
            return 0;
        }

        final Collection< String > refersTo = referenceManager.findRefersTo( pageName );
        final List< String > outbound = new ArrayList<>( refersTo );
        Collections.sort( outbound );

        if ( activeChecks.contains( "outbound_links" ) ) {
            entry.put( "outboundLinks", outbound );
        }

        if ( activeChecks.contains( "broken_links" ) ) {
            final List< String > broken = new ArrayList<>();
            for ( final String target : outbound ) {
                if ( pageManager.getPage( target ) == null ) {
                    broken.add( target );
                }
            }
            Collections.sort( broken );
            entry.put( "brokenLinks", broken );
            return broken.size();
        }

        return 0;
    }

    /**
     * Check backlinks for a page, populating the entry map and aggregate list.
     */
    void checkBacklinks( final String pageName, final Map< String, Object > entry,
                         final Set< String > activeChecks, final List< String > pagesWithNoBacklinks ) {
        if ( !activeChecks.contains( "backlinks" ) ) {
            return;
        }

        final Set< String > referrers = referenceManager.findReferrers( pageName );
        final List< String > backlinks = new ArrayList<>( referrers );
        Collections.sort( backlinks );
        entry.put( "backlinks", backlinks );

        if ( backlinks.isEmpty() ) {
            pagesWithNoBacklinks.add( pageName );
        }
    }

    /**
     * Check metadata completeness for a page, populating the entry map and aggregate list.
     */
    void checkMetadata( final String pageName, final Map< String, Object > metadata,
                        final Map< String, Object > entry, final Set< String > activeChecks,
                        final List< String > metadataIssues ) {
        if ( !activeChecks.contains( "metadata_completeness" ) ) {
            return;
        }

        if ( !metadata.isEmpty() ) {
            entry.put( "metadata", new LinkedHashMap<>( metadata ) );
        }

        final List< String > missing = new ArrayList<>();
        for ( final String field : STANDARD_METADATA_FIELDS ) {
            if ( !metadata.containsKey( field ) ) {
                missing.add( field );
            }
        }
        Collections.sort( missing );
        entry.put( "missingMetadata", missing );

        if ( !missing.isEmpty() ) {
            metadataIssues.add( pageName + " missing: " + String.join( ", ", missing ) );
        }
    }

    /**
     * Check SEO readiness for a page using composed {@link PageCheck} strategies,
     * populating the entry map and aggregate list.
     */
    void checkSeoReadiness( final String pageName, final Map< String, Object > metadata,
                            final Page page, final Map< String, Object > entry,
                            final Set< String > activeChecks, final List< String > seoIssues ) {
        if ( !activeChecks.contains( "seo_readiness" ) ) {
            return;
        }

        final PageCheckContext checkCtx = new PageCheckContext(
                pageName, metadata, "", page, pageManager );
        final List< String > seoWarnings = new ArrayList<>();
        for ( final PageCheck check : SEO_CHECKS ) {
            for ( final PageCheckResult result : check.check( checkCtx ) ) {
                seoWarnings.add( result.detail() );
            }
        }

        entry.put( "seoWarnings", seoWarnings );

        if ( !seoWarnings.isEmpty() ) {
            seoIssues.add( pageName + ": " + String.join( "; ", seoWarnings ) );
        }
    }
}
