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

import com.wikantik.mcp.AbstractMcpTool;


import io.modelcontextprotocol.spec.McpSchema;
import com.wikantik.api.core.Page;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.mcp.tools.verify.PageContext;
import com.wikantik.mcp.tools.verify.VerifyCheck;
import com.wikantik.mcp.tools.verify.VerifyChecks;
import com.wikantik.mcp.tools.verify.VerifyTotals;

import java.util.*;

/**
 * Compound read-only MCP tool that verifies the state of multiple wiki pages in a single call.
 * Checks existence, broken links, backlinks, outbound links, metadata completeness,
 * and SEO readiness.
 *
 * <p>Each check is a {@link VerifyCheck} strategy (package {@code verify}); SEO and retrieval
 * readiness in turn compose {@link PageCheck} instances from {@link PageChecks}.
 */
public class VerifyPagesTool extends AbstractMcpTool {

    public static final String TOOL_NAME = "verify_pages";

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
        properties.put( "slugs", Map.of(
                "type", "array",
                "description", "Array of page names to verify",
                "items", Map.of( "type", "string" ),
                "examples", List.of( List.of( "HybridRetrieval", "AgentMemory" ) )
        ) );
        properties.put( "checks", Map.of(
                "type", "array",
                "description", "Optional subset of checks to run. Defaults to all. " +
                        "Valid values: existence, broken_links, backlinks, outbound_links, metadata_completeness, seo_readiness, retrieval_readiness",
                "items", Map.of( "type", "string" ),
                "examples", List.of( List.of( "existence", "metadata_completeness", "seo_readiness" ) )
        ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "pages", List.of(
                        Map.of(
                                "pageName", "HybridRetrieval",
                                "exists", true,
                                "version", 7,
                                "missingMetadata", List.of(),
                                "seoWarnings", List.of(),
                                "retrievalWarnings", List.of()
                        ),
                        Map.of(
                                "pageName", "AgentMemory",
                                "exists", true,
                                "version", 3,
                                "missingMetadata", List.of( "tags" ),
                                "seoWarnings", List.of( "no summary in frontmatter" )
                        )
                ),
                "summary", Map.of(
                        "totalPages", 2,
                        "allExist", true,
                        "metadataIssues", List.of( "AgentMemory missing: tags" ),
                        "seoIssues", List.of( "AgentMemory: no summary in frontmatter" ),
                        "retrievalIssues", List.of( "AgentMemory: No summary — chunk embeddings lack ..." )
                )
        ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Verify the state of multiple wiki pages in a single call. " +
                        "Checks existence, broken links, backlinks, outbound links, and metadata completeness. " +
                        "Returns per-page details and a summary. Use after creating or updating pages to confirm integrity." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "slugs" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @SuppressWarnings( "unchecked" )
    @Override
    protected McpSchema.CallToolResult doExecute( final Map< String, Object > arguments ) throws Exception {
        final List< String > pageNames = ( List< String > ) McpToolUtils.pageSlugs( arguments );
        final List< String > checks = ( List< String > ) arguments.get( "checks" );

        if ( pageNames == null || pageNames.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "No page names provided",
                    "Provide an array of page names in the slugs parameter." );
        }

        final Set< String > activeChecks = checks != null && !checks.isEmpty()
                ? new HashSet<>( checks )
                : Set.of( "existence", "broken_links", "backlinks", "outbound_links", "metadata_completeness" );

        final List< VerifyCheck > verifyChecks = VerifyChecks.active( pageManager, referenceManager, activeChecks );
        final VerifyTotals totals = new VerifyTotals();
        final List< Map< String, Object > > pageResults = new ArrayList<>();
        boolean allExist = true;

        for ( final String pageName : pageNames ) {
            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "pageName", pageName );

            final Page page = pageManager.getPage( pageName );
            final boolean exists = page != null;
            entry.put( "exists", exists );

            if ( !exists ) {
                allExist = false;
                pageResults.add( entry );
                continue;
            }
            entry.put( "version", McpToolUtils.normalizeVersion( page.getVersion() ) );

            // Frontmatter is parsed lazily, at most once, and only if a check asks for it.
            final PageContext context = new PageContext( pageName, page, () -> parseFrontmatter( pageName ) );
            for ( final VerifyCheck check : verifyChecks ) {
                check.apply( context, entry, totals );
            }
            pageResults.add( entry );
        }

        final Map< String, Object > summary = new LinkedHashMap<>();
        summary.put( "totalPages", pageNames.size() );
        summary.put( "allExist", allExist );
        for ( final VerifyCheck check : verifyChecks ) {
            check.summarise( totals, summary );
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "pages", pageResults );
        result.put( "summary", summary );

        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
    }

    private ParsedPage parseFrontmatter( final String pageName ) {
        return FrontmatterParser.parse( pageManager.getPureText( pageName, PageProvider.LATEST_VERSION ) );
    }

    // The package-private methods below run one check outside the tool loop; they exist for the
    // per-check unit tests (VerifyPagesToolTest) and delegate to the same VerifyCheck objects.

    int checkBrokenLinks( final String pageName, final Map< String, Object > entry,
                          final Set< String > activeChecks ) {
        final VerifyTotals totals = new VerifyTotals();
        runOne( VerifyChecks.active( pageManager, referenceManager, activeChecks ),
                pageName, null, "links", entry, totals );
        return totals.brokenLinks();
    }

    void checkBacklinks( final String pageName, final Map< String, Object > entry,
                         final Set< String > activeChecks, final List< String > pagesWithNoBacklinks ) {
        final VerifyTotals totals = new VerifyTotals();
        runOne( VerifyChecks.active( pageManager, referenceManager, activeChecks ),
                pageName, null, "backlinks", entry, totals );
        pagesWithNoBacklinks.addAll( totals.pagesWithNoBacklinks() );
    }

    void checkMetadata( final String pageName, final Map< String, Object > metadata,
                        final Map< String, Object > entry, final Set< String > activeChecks,
                        final List< String > metadataIssues ) {
        final VerifyTotals totals = new VerifyTotals();
        runOne( VerifyChecks.active( pageManager, referenceManager, activeChecks ), pageName,
                new PageContext( pageName, null, () -> new ParsedPage( metadata, "" ) ),
                "metadata_completeness", entry, totals );
        metadataIssues.addAll( totals.metadataIssues() );
    }

    void checkSeoReadiness( final String pageName, final Map< String, Object > metadata,
                            final Page page, final Map< String, Object > entry,
                            final Set< String > activeChecks, final List< String > seoIssues ) {
        final VerifyTotals totals = new VerifyTotals();
        runOne( VerifyChecks.active( pageManager, referenceManager, activeChecks ), pageName,
                new PageContext( pageName, page, () -> new ParsedPage( metadata, "" ) ),
                "seo_readiness", entry, totals );
        seoIssues.addAll( totals.seoIssues() );
    }

    private void runOne( final List< VerifyCheck > active, final String pageName, final PageContext supplied,
                         final String checkName, final Map< String, Object > entry, final VerifyTotals totals ) {
        final PageContext context = supplied != null ? supplied
                : new PageContext( pageName, null, () -> parseFrontmatter( pageName ) );
        for ( final VerifyCheck check : active ) {
            if ( check.name().equals( checkName ) ) {
                check.apply( context, entry, totals );
            }
        }
    }
}
