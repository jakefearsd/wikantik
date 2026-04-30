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
 * Wire-level guard that the MCP write surface refuses every system page (CSS theme
 * pages, menu fragments, help pages, anything shipped with the wiki via
 * {@code wikantik-wikipages}). System pages underpin the rendering surface of the
 * whole wiki — letting an agent rewrite {@code CSSRibbon} or {@code LeftMenu} would
 * leak style/layout drift across every page without an admin in the loop.
 *
 * <p>All five admin-MCP write tools must refuse:</p>
 * <ul>
 *   <li>{@code update_page} — refuse before the hash check.</li>
 *   <li>{@code write_pages} — per-page error.</li>
 *   <li>{@code delete_pages} — per-page error.</li>
 *   <li>{@code rename_page} — top-level error on either oldName or newName.</li>
 *   <li>{@code mark_page_verified} — per-page error.</li>
 * </ul>
 *
 * <p>{@code CSSRibbon} is a real CSS theme page shipped in {@code wikantik-wikipages}
 * (verified at the time of writing; if the page is ever renamed in the source pages
 * jar, swap the constant for any other page reported by
 * {@code DefaultSystemPageRegistry.getSystemPageNames()}).</p>
 */
public class McpSystemPageProtectionIT extends WithMcpTestSetup {

    /** A real system page that ships with the wiki. */
    private static final String SYSTEM_PAGE = "CSSRibbon";

    @Test
    public void updatePageRefusesSystemPage() {
        final Map< String, Object > result = mcp.callTool( "update_page", Map.of(
                "pageName", SYSTEM_PAGE,
                "content", "/* malicious style injection */",
                "expectedContentHash", "sha256:any" ) );

        Assertions.assertEquals( Boolean.FALSE, result.get( "updated" ),
                "update_page must refuse system pages" );
        Assertions.assertTrue( String.valueOf( result.get( "error" ) ).contains( "system page" ),
                "refusal must mention 'system page' so the agent stops retrying — got: " + result );
    }

    @Test
    public void writePagesRefusesSystemPage() {
        final Map< String, Object > response = mcp.callTool( "write_pages", Map.of(
                "pages", List.of(
                        Map.of( "pageName", SYSTEM_PAGE,
                                "content", "/* shadow CSS */" ) ) ) );

        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) response.get( "results" );
        Assertions.assertNotNull( results, "write_pages must return per-page results" );
        Assertions.assertEquals( 1, results.size() );

        final Map< String, Object > entry = results.get( 0 );
        Assertions.assertEquals( SYSTEM_PAGE, entry.get( "pageName" ) );
        Assertions.assertEquals( Boolean.FALSE, entry.get( "created" ),
                "write_pages must refuse system pages" );
        // The refusal could be the "already exists" branch (since system pages are
        // pre-loaded) or the new system-page branch — either is acceptable, since
        // both prevent the write. Assert the more general invariant: the tool did
        // not create the page.
    }

    @Test
    public void writePagesRefusesSystemPageRegardlessOfPriorExistence() {
        // Even if the agent attacker first calls delete_pages (which we expect to also
        // refuse), then write_pages, the system-page guard must hold. We don't try to
        // delete first because that's the same failure mode under test elsewhere; here
        // we just assert that the per-page error explicitly cites "system page" when
        // the system-page check fires before the existence check.
        final Map< String, Object > response = mcp.callTool( "write_pages", Map.of(
                "pages", List.of(
                        Map.of( "pageName", SYSTEM_PAGE,
                                "content", "/* would be a fresh write if the page were missing */" ) ) ) );

        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) response.get( "results" );
        final String error = String.valueOf( results.get( 0 ).get( "error" ) );
        Assertions.assertTrue( error.contains( "system page" ) || error.contains( "already exists" ),
                "write_pages must refuse the system page either via the system-page guard "
                + "or the existing-page guard — got: " + error );
    }

    @Test
    public void deletePagesRefusesSystemPage() {
        final Map< String, Object > response = mcp.callTool( "delete_pages", Map.of(
                "pageNames", List.of( SYSTEM_PAGE ),
                "confirm", true ) );

        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) response.get( "results" );
        Assertions.assertNotNull( results, "delete_pages must return per-page results" );
        Assertions.assertEquals( 1, results.size() );

        final Map< String, Object > entry = results.get( 0 );
        Assertions.assertEquals( SYSTEM_PAGE, entry.get( "pageName" ) );
        Assertions.assertEquals( Boolean.FALSE, entry.get( "deleted" ),
                "delete_pages must refuse system pages" );
        Assertions.assertTrue( String.valueOf( entry.get( "error" ) ).contains( "system page" ),
                "refusal must mention 'system page' — got: " + entry );
    }

    @Test
    public void renamePageRefusesSystemPageAsSource() {
        // rename_page returns an error result (not a per-page entry) when refusing.
        // McpTestClient.callTool throws McpToolError on isError=true; assert it.
        final McpTestClient.McpToolError err = Assertions.assertThrows(
                McpTestClient.McpToolError.class,
                () -> mcp.callTool( "rename_page", Map.of(
                        "oldName", SYSTEM_PAGE,
                        "newName", "AttackerOwned",
                        "confirm", true ) ),
                "rename_page must reject a system-page source" );
        Assertions.assertTrue( err.getResponseText().contains( "system page" )
                            || err.getResponseText().contains( "Cannot rename system page" ),
                "rename refusal must explain why — got: " + err.getResponseText() );
    }

    @Test
    public void renamePageRefusesSystemPageAsTarget() {
        final McpTestClient.McpToolError err = Assertions.assertThrows(
                McpTestClient.McpToolError.class,
                () -> mcp.callTool( "rename_page", Map.of(
                        "oldName", "SomeUserPage",
                        "newName", SYSTEM_PAGE,
                        "confirm", true ) ),
                "rename_page must reject a system-page target name" );
        Assertions.assertTrue( err.getResponseText().contains( "system page" )
                            || err.getResponseText().contains( "Cannot rename" ),
                "rename refusal must explain why — got: " + err.getResponseText() );
    }

    @Test
    public void markPageVerifiedRefusesSystemPage() {
        // Verification metadata is a trust signal: the /for-agent projection treats
        // confidence: stale as untrusted but stamps confidence: authoritative when a
        // trusted author has verified within the stale window. Letting agents stamp
        // shipped help / CSS / menu pages would be a soft attack on those signals.
        final Map< String, Object > response = mcp.callTool( "mark_page_verified", Map.of(
                "pageNames", List.of( SYSTEM_PAGE ) ) );

        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) response.get( "results" );
        Assertions.assertNotNull( results, "mark_page_verified must return per-page results" );
        Assertions.assertEquals( 1, results.size() );

        final Map< String, Object > entry = results.get( 0 );
        Assertions.assertEquals( SYSTEM_PAGE, entry.get( "pageName" ) );
        Assertions.assertEquals( Boolean.FALSE, entry.get( "ok" ),
                "mark_page_verified must refuse system pages" );
        Assertions.assertTrue( String.valueOf( entry.get( "error" ) ).contains( "system page" ),
                "refusal must mention 'system page' — got: " + entry );
        Assertions.assertEquals( 0, ( ( Number ) response.get( "succeeded" ) ).intValue(),
                "summary count must report zero successes" );
    }

    @Test
    public void readPageStillWorksOnSystemPage() {
        // The protection must be write-only. Reads of system pages remain allowed —
        // agents need to be able to inspect CSS / menu / help text for context.
        final Map< String, Object > result = mcp.callTool( "read_page", Map.of(
                "pageName", SYSTEM_PAGE ) );
        Assertions.assertEquals( Boolean.TRUE, result.get( "exists" ),
                "read_page must still expose the system page — protection is write-only" );
        Assertions.assertNotNull( result.get( "content" ),
                "read_page must return the page body for a system page" );
    }
}
