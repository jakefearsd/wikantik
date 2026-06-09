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
 * Wire-level guard for the frontmatter validation layer as seen through the MCP
 * admin write tools ({@code write_pages} and {@code update_page}).
 *
 * <p>Two behaviours are exercised end-to-end:</p>
 * <ol>
 *   <li><strong>ERROR refusal</strong> — metadata with {@code audience: robots}
 *       (not in the canonical set {@code {humans, agents, both}}, and
 *       {@code audience.open=false}) must cause {@code write_pages} to return a
 *       per-page result with {@code created=false} and a {@code violations} entry
 *       citing the {@code audience} field. The tool envelope remains non-errored
 *       (a per-op refusal is not a top-level {@code isError=true}).</li>
 *   <li><strong>WARNING pass-through</strong> — metadata with {@code status: published}
 *       ({@code status.open=true}; "published" is in the suggestion map → "active")
 *       must cause the write to succeed ({@code created=true}) and the result must
 *       carry a {@code frontmatterWarnings} list citing {@code status}.</li>
 * </ol>
 *
 * <p>Uses the same {@link WithMcpTestSetup} base class (MCP client lifecycle +
 * unique-name helper) as {@link KgCurationIT} and {@link McpFrontmatterNormalizationIT}.
 * Cleanup via {@code delete_pages} mirrors those ITs.</p>
 */
public class McpFrontmatterValidationIT extends WithMcpTestSetup {

    /**
     * {@code write_pages} with {@code audience: robots} (ERROR severity, open=false)
     * must refuse the write at the per-page level: {@code created=false}, and a
     * {@code violations} entry citing {@code audience}.
     *
     * <p>The overall tool call succeeds (the envelope is not {@code isError=true}),
     * so we use {@code callTool} (not {@code callToolExpectingError}). The per-op
     * refusal surfaces inside the {@code results} list — mirroring the pattern used
     * by {@code review_proposals} per-id errors in {@link KgCurationIT}.</p>
     */
    @Test
    public void writePagesWithInvalidAudienceRefusesWriteAndCitesViolation() {
        final String pageName = uniquePageName( "FmValInvalidAudience" );

        final Map< String, Object > response = mcp.callTool( "write_pages", Map.of(
                "pages", List.of( Map.of(
                        "pageName", pageName,
                        "content", "Body of the invalid audience test page.",
                        "metadata", Map.of(
                                "type", "article",
                                "audience", "robots" ) ) ) ) );

        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > results =
                ( List< Map< String, Object > > ) response.get( "results" );
        Assertions.assertNotNull( results,
                "write_pages must return a 'results' list: " + response );
        Assertions.assertFalse( results.isEmpty(),
                "results must not be empty: " + response );

        final Map< String, Object > entry = results.get( 0 );
        Assertions.assertEquals( Boolean.FALSE, entry.get( "created" ),
                "Per-page result must have created=false for audience=robots: " + entry );

        // The refusal must surface a 'violations' list or an 'error' string that
        // mentions 'audience' so the agent can identify and fix the offending field.
        final boolean hasViolationsList = entry.containsKey( "violations" );
        final boolean errorMentionsAudience = String.valueOf( entry.get( "error" ) )
                .toLowerCase().contains( "audience" );
        Assertions.assertTrue( hasViolationsList || errorMentionsAudience,
                "Refusal must cite 'audience' in violations or error message: " + entry );

        if ( hasViolationsList ) {
            @SuppressWarnings( "unchecked" )
            final List< Object > violations = ( List< Object > ) entry.get( "violations" );
            final String violationsStr = violations.toString().toLowerCase();
            Assertions.assertTrue( violationsStr.contains( "audience" ),
                    "violations must include 'audience' field: " + violations );
        }

        // Page must NOT have been created — verify by attempting to read it.
        // If write_pages silently wrote the page despite created=false, the delete
        // guard below would succeed and mask the regression; we verify separately.
        final Map< String, Object > readResult = mcp.callTool( "read_page",
                Map.of( "pageName", pageName ) );
        Assertions.assertNotEquals( Boolean.TRUE, readResult.get( "exists" ),
                "Page must NOT exist after a refused write: " + readResult );
    }

    /**
     * {@code write_pages} with {@code status: published} (WARNING severity,
     * open=true; "published" → suggestion "active") must succeed and surface a
     * {@code frontmatterWarnings} entry citing {@code status}.
     *
     * <p>The page is cleaned up via {@code delete_pages} after the assertion,
     * mirroring the cleanup pattern in {@link McpFrontmatterNormalizationIT}.</p>
     */
    @Test
    public void writePagesWithNonCanonicalStatusSucceedsAndSurfacesWarning() {
        final String pageName = uniquePageName( "FmValNoncanonicalStatus" );
        try {
            final Map< String, Object > response = mcp.callTool( "write_pages", Map.of(
                    "pages", List.of( Map.of(
                            "pageName", pageName,
                            "content", "Body of the noncanonical status test page.",
                            "metadata", Map.of(
                                    "type", "article",
                                    "status", "published" ) ) ) ) );

            @SuppressWarnings( "unchecked" )
            final List< Map< String, Object > > results =
                    ( List< Map< String, Object > > ) response.get( "results" );
            Assertions.assertNotNull( results,
                    "write_pages must return a 'results' list: " + response );
            Assertions.assertFalse( results.isEmpty(),
                    "results must not be empty: " + response );

            final Map< String, Object > entry = results.get( 0 );
            Assertions.assertEquals( Boolean.TRUE, entry.get( "created" ),
                    "Page with status=published must be created (open=true): " + entry );

            // frontmatterWarnings must be present and cite "status"
            Assertions.assertTrue( entry.containsKey( "frontmatterWarnings" ),
                    "Result must carry 'frontmatterWarnings' for status=published: " + entry );

            @SuppressWarnings( "unchecked" )
            final List< Object > warnings =
                    ( List< Object > ) entry.get( "frontmatterWarnings" );
            Assertions.assertFalse( warnings.isEmpty(),
                    "frontmatterWarnings must not be empty for status=published: " + entry );
            Assertions.assertTrue( warnings.toString().toLowerCase().contains( "status" ),
                    "frontmatterWarnings must mention 'status': " + warnings );
        } finally {
            // Best-effort cleanup: don't fail the test if the page was not created
            // (the assertion above would have already failed for that case).
            try {
                mcp.callTool( "delete_pages", Map.of(
                        "pageNames", List.of( pageName ),
                        "confirm", true ) );
            } catch ( final Exception e ) {
                // Cleanup failure must not mask test results.
                System.err.println( "McpFrontmatterValidationIT cleanup: could not delete "
                        + pageName + ": " + e.getMessage() );
            }
        }
    }
}
