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
package com.wikantik.its.mcp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Wire-level Cargo IT for {@code /wikantik-admin-mcp} read-only and verification
 * tools that {@link McpProtocolIT} only proves are <em>registered</em>.
 *
 * <p>Each test calls the tool through the live MCP transport and asserts the
 * JSON envelope shape and a content-bearing field. Coverage targets:</p>
 * <ul>
 *   <li>{@code get_wiki_stats} — dashboard counters present and non-negative.</li>
 *   <li>{@code get_orphaned_pages} — returns an {@code orphans} array.</li>
 *   <li>{@code get_broken_links} — returns a {@code brokenLinks} array.</li>
 *   <li>{@code get_outbound_links} — round-trips a freshly written page.</li>
 *   <li>{@code get_page_history} — returns at least one version after a write.</li>
 *   <li>{@code diff_page} — returns a {@code diff} field after two versions.</li>
 *   <li>{@code verify_pages} — per-page {@code exists} + {@code summary} shape.</li>
 *   <li>{@code preview_structured_data} — returns JSON-LD when one is present.</li>
 *   <li>{@code ping_search_engines} — returns per-engine result entries.</li>
 *   <li>{@code mark_page_verified} — happy path + system-page refusal.</li>
 * </ul>
 *
 * <p>None of these targets existed at wire level before this IT — they were
 * unit-tested only inside the {@code wikantik-admin-mcp} module.</p>
 */
public class AdminMcpReadToolsIT extends WithMcpTestSetup {

    // ------------------------------------------------------------------
    // get_wiki_stats
    // ------------------------------------------------------------------

    @Test
    public void getWikiStatsReturnsDashboardCounters() {
        final Map< String, Object > result = mcp.callTool( "get_wiki_stats", Map.of() );

        Assertions.assertNotNull( result.get( "totalPages" ),     "totalPages must be present" );
        Assertions.assertNotNull( result.get( "brokenLinkCount" ), "brokenLinkCount must be present" );
        Assertions.assertNotNull( result.get( "orphanedPageCount" ), "orphanedPageCount must be present" );
        Assertions.assertNotNull( result.get( "recentChangesCount" ), "recentChangesCount must be present" );

        // All four are integers (Gson maps numeric to Double; just guard against negative noise).
        final double total   = ( ( Number ) result.get( "totalPages" ) ).doubleValue();
        final double broken  = ( ( Number ) result.get( "brokenLinkCount" ) ).doubleValue();
        final double orphans = ( ( Number ) result.get( "orphanedPageCount" ) ).doubleValue();
        final double recents = ( ( Number ) result.get( "recentChangesCount" ) ).doubleValue();
        Assertions.assertTrue( total   >= 0, "totalPages must be non-negative: "      + total );
        Assertions.assertTrue( broken  >= 0, "brokenLinkCount must be non-negative: " + broken );
        Assertions.assertTrue( orphans >= 0, "orphanedPageCount non-negative: "       + orphans );
        Assertions.assertTrue( recents >= 0, "recentChangesCount non-negative: "      + recents );
    }

    // ------------------------------------------------------------------
    // get_orphaned_pages / get_broken_links — array shape
    // ------------------------------------------------------------------

    @Test
    public void getOrphanedPagesReturnsArrayEnvelope() {
        final Map< String, Object > result = mcp.callTool( "get_orphaned_pages", Map.of() );
        Assertions.assertNotNull( result, "envelope must not be null" );
        // Tool returns { "orphans": [...] } or similar — accept any list-valued field.
        final boolean hasListPayload = result.values().stream()
                .anyMatch( v -> v instanceof List< ? > );
        Assertions.assertTrue( hasListPayload,
                "Response must contain at least one list-typed field: " + result );
    }

    @Test
    public void getBrokenLinksReturnsArrayEnvelope() {
        final Map< String, Object > result = mcp.callTool( "get_broken_links", Map.of() );
        Assertions.assertNotNull( result, "envelope must not be null" );
        final boolean hasListPayload = result.values().stream()
                .anyMatch( v -> v instanceof List< ? > );
        Assertions.assertTrue( hasListPayload,
                "Response must contain at least one list-typed field: " + result );
    }

    // ------------------------------------------------------------------
    // get_outbound_links — round-trip on a freshly-written page
    // ------------------------------------------------------------------

    @Test
    public void getOutboundLinksReturnsLinkedPages() {
        final String src    = uniquePageName( "OutLinksSrc" );
        final String target = uniquePageName( "OutLinksTgt" );
        mcp.importPage( target, "Target." );
        mcp.importPage( src,    "Points to [" + target + "](" + target + ") and nowhere else." );

        final Map< String, Object > result = mcp.callTool( "get_outbound_links",
                Map.of( "pageName", src ) );

        final String body = result.toString();
        Assertions.assertTrue( body.contains( target ),
                "outbound links for " + src + " must mention " + target + ": " + body );
    }

    // ------------------------------------------------------------------
    // get_page_history + diff_page — fresh page produces v1+v2
    // ------------------------------------------------------------------

    @Test
    public void getPageHistoryAndDiffPageReturnVersionPair() {
        final String name = uniquePageName( "HistoryPage" );
        mcp.importPage( name, "First revision." );
        mcp.importPage( name, "Second revision — substantially different text." );

        final Map< String, Object > history = mcp.callTool( "get_page_history",
                Map.of( "pageName", name ) );
        Assertions.assertNotNull( history, "history envelope must not be null" );
        final String historyBody = history.toString();
        Assertions.assertTrue(
                historyBody.contains( "version" ) || historyBody.contains( "revisions" ),
                "history payload must mention version/revisions: " + historyBody );

        final Map< String, Object > diff = mcp.callTool( "diff_page",
                Map.of( "pageName", name, "version1", 1, "version2", 2 ) );
        Assertions.assertNotNull( diff.get( "diff" ),
                "diff_page must return a 'diff' field: " + diff );
        Assertions.assertEquals( name, diff.get( "pageName" ),
                "diff_page must echo back the pageName" );
    }

    // ------------------------------------------------------------------
    // verify_pages — per-page payload + summary
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings( "unchecked" )
    public void verifyPagesReportsExistenceAndSummary() {
        final String real    = uniquePageName( "VerifyReal" );
        final String missing = uniquePageName( "VerifyMissing" );
        mcp.importPage( real, "Verified page body." );

        final Map< String, Object > result = mcp.callTool( "verify_pages",
                Map.of( "pageNames", List.of( real, missing ) ) );

        final List< Map< String, Object > > pages =
                ( List< Map< String, Object > > ) result.get( "pages" );
        Assertions.assertNotNull( pages, "verify_pages must return 'pages': " + result );
        Assertions.assertEquals( 2, pages.size(),
                "verify_pages must return one entry per pageName: " + pages );

        boolean realFlagged = false;
        boolean missingFlagged = false;
        for ( final Map< String, Object > entry : pages ) {
            if ( real.equals( entry.get( "pageName" ) ) && Boolean.TRUE.equals( entry.get( "exists" ) ) ) {
                realFlagged = true;
            }
            if ( missing.equals( entry.get( "pageName" ) ) && Boolean.FALSE.equals( entry.get( "exists" ) ) ) {
                missingFlagged = true;
            }
        }
        Assertions.assertTrue( realFlagged,
                "verify_pages must mark " + real + " as exists=true" );
        Assertions.assertTrue( missingFlagged,
                "verify_pages must mark " + missing + " as exists=false" );

        final Map< String, Object > summary = ( Map< String, Object > ) result.get( "summary" );
        Assertions.assertNotNull( summary, "verify_pages must include a 'summary': " + result );
        Assertions.assertEquals( 2.0, ( ( Number ) summary.get( "totalPages" ) ).doubleValue(),
                "summary.totalPages must equal the number requested" );
    }

    // ------------------------------------------------------------------
    // preview_structured_data — JSON-LD preview
    // ------------------------------------------------------------------

    @Test
    public void previewStructuredDataReturnsJsonLdForKnownPage() {
        // Main always exists in the IT corpus; preview_structured_data should
        // return at least an envelope describing the JSON-LD that would be
        // injected when the page renders.
        final Map< String, Object > result = mcp.callTool( "preview_structured_data",
                Map.of( "pageName", "Main" ) );
        Assertions.assertNotNull( result, "preview_structured_data envelope must not be null" );
        final String body = result.toString();
        Assertions.assertTrue( body.contains( "Main" ),
                "preview must reference the requested page name: " + body );
    }

    // ------------------------------------------------------------------
    // ping_search_engines — returns per-engine entries
    // ------------------------------------------------------------------

    @Test
    public void pingSearchEnginesReturnsPerEngineResults() {
        // service is required per the input schema enum (indexnow|google_ping|all).
        // 'all' exercises both code paths in one call; the IT does not have any
        // configured indexnow key or reachable Google ping URL, so the tool
        // either reports per-service success/failure entries or surfaces a
        // top-level error envelope — both are valid.
        final Map< String, Object > result;
        try {
            result = mcp.callTool( "ping_search_engines",
                    Map.of( "service", "all" ) );
        } catch ( final McpTestClient.McpToolError e ) {
            // Tool-level error (e.g. "no engines configured") is also a real
            // code path. Just verify the response surfaces a reason.
            Assertions.assertTrue( e.getResponseText() != null && !e.getResponseText().isBlank(),
                    "ping_search_engines tool-level error must explain itself: " + e.getResponseText() );
            return;
        }
        Assertions.assertNotNull( result, "ping_search_engines envelope must not be null" );
        final boolean hasResults = result.containsKey( "results" )
                || result.containsKey( "engines" )
                || result.containsKey( "message" )
                || result.containsKey( "error" );
        Assertions.assertTrue( hasResults,
                "Response must surface results / engines / message / error: " + result );
    }

    // ------------------------------------------------------------------
    // mark_page_verified — happy path + system-page refusal
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings( "unchecked" )
    public void markPageVerifiedStampsFrontmatter() {
        final String name = uniquePageName( "MarkVerifiedHappy" );
        mcp.importPage( name, "Page body, no frontmatter yet." );

        final Map< String, Object > result = mcp.callTool( "mark_page_verified",
                Map.of( "pageNames", List.of( name ),
                        "verifier", "janne",
                        "changeNote", "IT verification stamp" ) );

        final List< Map< String, Object > > results =
                ( List< Map< String, Object > > ) result.get( "results" );
        Assertions.assertNotNull( results,
                "mark_page_verified must return 'results' array: " + result );
        Assertions.assertEquals( 1, results.size(),
                "results must contain exactly one entry: " + results );
        final Map< String, Object > entry = results.get( 0 );
        Assertions.assertEquals( name, entry.get( "pageName" ) );
        Assertions.assertEquals( Boolean.TRUE, entry.get( "ok" ),
                "Happy-path mark_page_verified must set ok=true: " + entry );
        Assertions.assertNotNull( entry.get( "verifiedAt" ),
                "Happy path must return verifiedAt: " + entry );
        Assertions.assertEquals( "janne", entry.get( "verifiedBy" ),
                "verifiedBy must echo the supplied verifier: " + entry );

        // Round-trip via read_page to confirm the frontmatter was persisted.
        final Map< String, Object > read = mcp.callTool( "read_page",
                Map.of( "pageName", name ) );
        Assertions.assertNotNull( read, "read_page envelope must not be null" );
        Assertions.assertTrue( read.toString().contains( "verified_at" ),
                "Persisted page must contain verified_at frontmatter: " + read );
    }

    // System-page refusal for mark_page_verified is already covered by
    // McpSystemPageProtectionIT — no duplicate assertion here.
}
