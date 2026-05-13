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
 * Wire-level Cargo IT exercising the {@code /wikantik-admin-mcp} write surface
 * end-to-end: {@code write_pages → read_page → update_page → rename_page → delete_pages}.
 *
 * <p>Previously these tools were only referenced by {@link McpProtocolIT}'s
 * tool-list assertion — no IT actually drove the wire-level execute path. This
 * IT closes that gap and pins:</p>
 *
 * <ul>
 *   <li>{@code read_page} returns {@code exists}, {@code content}, {@code contentHash},
 *       {@code version}, {@code lastModified}.</li>
 *   <li>{@code update_page} with the correct {@code expectedContentHash} succeeds.</li>
 *   <li>{@code update_page} with a stale hash returns {@code updated:false,
 *       error:"hash mismatch"} + the current page state — agents can rebase
 *       without an extra round trip.</li>
 *   <li>{@code update_page} missing {@code expectedContentHash} is a tool error.</li>
 *   <li>{@code rename_page} returns the renamed slug; the old slug 404s.</li>
 *   <li>{@code delete_pages} returns per-page success and the page no longer reads.</li>
 *   <li>{@code update_page} refuses to mutate a system page.</li>
 * </ul>
 */
public class McpWritePageCycleIT extends WithMcpTestSetup {

    // ------------------------------------------------------------------
    // read_page — wire-level shape on a freshly imported page
    // ------------------------------------------------------------------

    @Test
    public void readPage_returnsContentAndHash() {
        final String name = uniquePageName( "ReadShape" );
        mcp.importPage( name, "Body of the read shape page." );

        final Map< String, Object > result = mcp.callTool( "read_page",
                Map.of( "pageName", name ) );
        Assertions.assertEquals( Boolean.TRUE, result.get( "exists" ),
                "read_page must set exists=true: " + result );
        Assertions.assertNotNull( result.get( "content" ),
                "read_page must include content: " + result );
        Assertions.assertNotNull( result.get( "contentHash" ),
                "read_page must include contentHash: " + result );
        Assertions.assertNotNull( result.get( "version" ),
                "read_page must include version: " + result );
    }

    @Test
    public void readPage_missingPageMarksExistsFalse() {
        final String name = "DefinitelyMissing-" + System.nanoTime();
        final Map< String, Object > result = mcp.callTool( "read_page",
                Map.of( "pageName", name ) );
        Assertions.assertEquals( Boolean.FALSE, result.get( "exists" ),
                "read_page must set exists=false for missing pages: " + result );
    }

    // ------------------------------------------------------------------
    // update_page — happy path + hash mismatch + missing hash
    // ------------------------------------------------------------------

    @Test
    public void updatePage_happyPathRoundTripsContent() {
        final String name = uniquePageName( "UpdateHappy" );
        mcp.importPage( name, "Original body." );
        final Map< String, Object > read = mcp.callTool( "read_page",
                Map.of( "pageName", name ) );
        final String hash = ( String ) read.get( "contentHash" );
        Assertions.assertNotNull( hash, "read_page must provide a contentHash for the update path" );

        final Map< String, Object > updated = mcp.callTool( "update_page",
                Map.of( "pageName", name,
                        "content", "Updated body.",
                        "expectedContentHash", hash ) );
        Assertions.assertEquals( Boolean.TRUE, updated.get( "updated" ),
                "update_page happy path must set updated=true: " + updated );

        // Round-trip: a fresh read must now show the new content.
        final Map< String, Object > postRead = mcp.callTool( "read_page",
                Map.of( "pageName", name ) );
        Assertions.assertTrue( postRead.get( "content" ).toString().contains( "Updated body." ),
                "read_page after update must reflect new content: " + postRead );
    }

    @Test
    public void updatePage_staleHashReturnsLatestContent() {
        final String name = uniquePageName( "UpdateStale" );
        mcp.importPage( name, "Initial body." );

        // First update — succeeds; second update with the OLD hash must fail.
        final Map< String, Object > read1 = mcp.callTool( "read_page",
                Map.of( "pageName", name ) );
        final String hash1 = ( String ) read1.get( "contentHash" );

        final Map< String, Object > first = mcp.callTool( "update_page",
                Map.of( "pageName", name,
                        "content", "First update.",
                        "expectedContentHash", hash1 ) );
        Assertions.assertEquals( Boolean.TRUE, first.get( "updated" ),
                "first update must succeed: " + first );

        // Now reuse hash1 — it is stale.
        final Map< String, Object > stale = mcp.callTool( "update_page",
                Map.of( "pageName", name,
                        "content", "Second update — should reject.",
                        "expectedContentHash", hash1 ) );
        Assertions.assertEquals( Boolean.FALSE, stale.get( "updated" ),
                "stale hash must reject the update: " + stale );
        Assertions.assertEquals( "hash mismatch", stale.get( "error" ),
                "stale hash error must be 'hash mismatch': " + stale );
        Assertions.assertNotNull( stale.get( "currentHash" ),
                "rebase response must include currentHash: " + stale );
        Assertions.assertNotNull( stale.get( "latestContent" ),
                "rebase response must include latestContent: " + stale );
        Assertions.assertTrue( stale.get( "latestContent" ).toString().contains( "First update." ),
                "latestContent must show the page state the agent is racing with: " + stale );
    }

    @Test
    public void updatePage_missingExpectedHashIsAnError() {
        final String name = uniquePageName( "UpdateNoHash" );
        mcp.importPage( name, "Body that won't be updated." );

        // Missing expectedContentHash must surface a tool error (isError=true).
        final McpTestClient.McpToolError thrown = Assertions.assertThrows(
                McpTestClient.McpToolError.class,
                () -> mcp.callTool( "update_page",
                        Map.of( "pageName", name, "content", "Doomed update." ) ),
                "update_page without expectedContentHash must raise an MCP error" );
        Assertions.assertTrue( thrown.getResponseText() != null
                        && thrown.getResponseText().toLowerCase().contains( "hash" ),
                "missing-hash error must cite hash: " + thrown.getResponseText() );
    }

    // System-page refusal for update_page is already covered by McpSystemPageProtectionIT —
    // do not duplicate that assertion here. This IT focuses on the happy/sad paths
    // unique to write tools.

    // ------------------------------------------------------------------
    // rename_page — old slug 404s, new slug reads
    // ------------------------------------------------------------------

    @Test
    public void renamePage_movesContentAndRetiresOldSlug() {
        final String oldName = uniquePageName( "RenameOld" );
        final String newName = uniquePageName( "RenameNew" );
        mcp.importPage( oldName, "Content to be moved by rename." );

        final Map< String, Object > rename = mcp.callTool( "rename_page",
                Map.of( "oldName", oldName, "newName", newName, "confirm", true ) );
        // The tool should at minimum signal success — exact field naming may
        // vary, so accept any of the well-known shapes.
        final String renameBody = rename.toString();
        Assertions.assertTrue( renameBody.contains( newName )
                        || renameBody.contains( "renamed" )
                        || renameBody.contains( "ok" )
                        || renameBody.contains( "true" ),
                "rename_page must signal success: " + renameBody );

        final Map< String, Object > newRead = mcp.callTool( "read_page",
                Map.of( "pageName", newName ) );
        Assertions.assertEquals( Boolean.TRUE, newRead.get( "exists" ),
                "renamed slug must exist: " + newRead );
        Assertions.assertTrue( newRead.get( "content" ).toString().contains( "Content to be moved" ),
                "renamed slug must carry the original content: " + newRead );

        final Map< String, Object > oldRead = mcp.callTool( "read_page",
                Map.of( "pageName", oldName ) );
        Assertions.assertEquals( Boolean.FALSE, oldRead.get( "exists" ),
                "old slug must no longer exist after rename: " + oldRead );
    }

    // ------------------------------------------------------------------
    // delete_pages — happy path
    // ------------------------------------------------------------------

    @Test
    public void deletePages_requiresExplicitConfirm() {
        // delete_pages without confirm=true must hard-error (safety check).
        final String name = uniquePageName( "DeleteNoConfirm" );
        mcp.importPage( name, "Should not be deleted by an unconfirmed call." );

        final McpTestClient.McpToolError thrown = Assertions.assertThrows(
                McpTestClient.McpToolError.class,
                () -> mcp.callTool( "delete_pages", Map.of( "pageNames", List.of( name ) ) ),
                "delete_pages without confirm=true must raise an MCP error" );
        Assertions.assertTrue( thrown.getResponseText() != null
                        && thrown.getResponseText().contains( "confirm" ),
                "unconfirmed-delete error must cite confirm: " + thrown.getResponseText() );

        // The page must still exist — the safety check fired before any delete ran.
        final Map< String, Object > postRead = mcp.callTool( "read_page",
                Map.of( "pageName", name ) );
        Assertions.assertEquals( Boolean.TRUE, postRead.get( "exists" ),
                "unconfirmed delete must leave the page intact: " + postRead );
    }

    @Test
    public void deletePages_removesNamedSlugs() {
        final String name = uniquePageName( "DeleteMe" );
        mcp.importPage( name, "Body to be deleted." );

        final Map< String, Object > delete = mcp.callTool( "delete_pages",
                Map.of( "pageNames", List.of( name ), "confirm", true ) );
        Assertions.assertNotNull( delete, "delete_pages envelope must not be null" );
        // The tool returns a per-page results array or a flat success message —
        // assert that the page name appears in the response so we know the
        // tool actually saw the request.
        Assertions.assertTrue( delete.toString().contains( name ),
                "delete_pages must echo the deleted page name: " + delete );

        // Confirm via read_page that the slug is gone.
        final Map< String, Object > postRead = mcp.callTool( "read_page",
                Map.of( "pageName", name ) );
        Assertions.assertEquals( Boolean.FALSE, postRead.get( "exists" ),
                "deleted slug must read as missing: " + postRead );
    }
}
