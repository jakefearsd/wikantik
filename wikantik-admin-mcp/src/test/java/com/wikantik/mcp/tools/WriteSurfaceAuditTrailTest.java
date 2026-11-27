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

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.insights.runtime.ContentOpportunityService;
import com.wikantik.pagegraph.spine.ClusterRenameService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code MCP_WRITE} security-log line is the audit trail of the admin MCP write surface: every state change and
 * every refusal of one must leave exactly one line saying which tool did what to which target, as whom.
 */
class WriteSurfaceAuditTrailTest {

    private static List< String > audit( final Runnable action ) {
        return SecurityLogCapture.during( action );
    }

    private static String line( final String tool, final String action, final String target, final String author ) {
        return "MCP_WRITE tool=" + tool + " action=" + action + " target=" + target + " author=" + author;
    }

    // ---- update_page

    private static String hash( final String text ) {
        return McpToolUtils.computeContentHash( text );
    }

    @Test
    void updatePageAuditsASuccessfulUpdateWithItsAuthor() {
        final PageManager pm = mock( PageManager.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 2 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( "old" );
        final UpdatePageTool tool = new UpdatePageTool( mock( PageSaveHelper.class ), pm, null );
        tool.setDefaultAuthor( "bot" );

        final List< String > lines = audit( () -> tool.execute( Map.of( "slug", "P", "content", "new",
            "expectedContentHash", hash( "old" ) ) ) );

        assertEquals( List.of( line( "update_page", "updated", "P", "bot" ) ), lines );
    }

    @Test
    void updatePageAuditsAnInvalidNameRefusal() {
        final UpdatePageTool tool = new UpdatePageTool( mock( PageSaveHelper.class ), mock( PageManager.class ), null );
        tool.setDefaultAuthor( "bot" );

        final List< String > lines = audit( () -> tool.execute( Map.of( "slug", "  ", "content", "x",
            "expectedContentHash", "h" ) ) );

        assertEquals( List.of( line( "update_page", "rejected-invalid-name", "null", "bot" ) ), lines );
    }

    @Test
    void updatePageAuditsASystemPageRefusal() {
        final SystemPageRegistry sys = mock( SystemPageRegistry.class );
        when( sys.isSystemPage( "CSSRibbon" ) ).thenReturn( true );
        final UpdatePageTool tool = new UpdatePageTool( mock( PageSaveHelper.class ), mock( PageManager.class ), sys );
        tool.setDefaultAuthor( "bot" );

        final List< String > lines = audit( () -> tool.execute( Map.of( "slug", "CSSRibbon", "content", "x",
            "expectedContentHash", "h" ) ) );

        assertEquals( List.of( line( "update_page", "refused-system-page", "CSSRibbon", "bot" ) ), lines );
    }

    @Test
    void updatePageWritesNoAuditLineWhenTheHashIsStale() {
        final PageManager pm = mock( PageManager.class );
        when( pm.getPage( "P" ) ).thenReturn( mock( Page.class ) );
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( "old" );
        final UpdatePageTool tool = new UpdatePageTool( mock( PageSaveHelper.class ), pm, null );

        final List< String > lines = audit( () -> tool.execute( Map.of( "slug", "P", "content", "new",
            "expectedContentHash", "stale" ) ) );

        assertEquals( List.of(), lines );
    }

    // ---- write_pages

    @Test
    void writePagesAuditsEachCreatedPageAndEachRefusal() {
        final PageManager pm = mock( PageManager.class );
        final SystemPageRegistry sys = mock( SystemPageRegistry.class );
        when( sys.isSystemPage( "CSSRibbon" ) ).thenReturn( true );
        final WritePagesTool tool = new WritePagesTool( mock( PageSaveHelper.class ), pm, sys );
        tool.setDefaultAuthor( "bot" );

        final List< String > lines = audit( () -> tool.execute( Map.of( "pages", List.of(
            Map.of( "slug", "Fresh", "content", "body" ),
            Map.of( "slug", " ", "content", "body" ),
            Map.of( "slug", "CSSRibbon", "content", "body" ) ) ) ) );

        assertEquals( List.of(
            line( "write_pages", "created", "Fresh", "bot" ),
            line( "write_pages", "rejected-invalid-name", " ", "bot" ),
            line( "write_pages", "refused-system-page", "CSSRibbon", "bot" ) ), lines );
    }

    // ---- delete_pages

    @Test
    void deletePagesAuditsTheDeletionWithAndWithoutAChangeNote() {
        final PageManager pm = mock( PageManager.class );
        final ReferenceManager refs = mock( ReferenceManager.class );
        for ( final String n : List.of( "A", "B" ) ) {
            when( pm.getPage( n ) ).thenReturn( mock( Page.class ) );
            when( refs.findReferrers( n ) ).thenReturn( Set.of() );
        }
        final DeletePagesTool tool = new DeletePagesTool( pm, refs, null );

        final List< String > withNote = audit( () -> tool.execute( Map.of( "slugs", List.of( "A" ),
            "confirm", true, "changeNote", "dup" ) ) );
        final List< String > withoutNote = audit( () -> tool.execute( Map.of( "slugs", List.of( "B" ),
            "confirm", true ) ) );

        assertEquals( List.of( line( "delete_pages", "deleted", "A note=dup", "unknown" ) ), withNote );
        assertEquals( List.of( line( "delete_pages", "deleted", "B", "unknown" ) ), withoutNote );
    }

    // ---- snooze_opportunity

    @Test
    void snoozeOpportunityAuditsTheSnoozeWithItsAuthor() {
        final ContentOpportunityService service = mock( ContentOpportunityService.class );
        when( service.snooze( anyString(), anyString(), anyInt(), anyString(), anyString() ) )
            .thenReturn( new ContentOpportunityService.SnoozeResult( LocalDate.of( 2026, 1, 1 ), false ) );
        final SnoozeOpportunityTool tool = new SnoozeOpportunityTool( service );
        tool.setDefaultAuthor( "bot" );

        final List< String > lines = audit( () -> tool.execute( Map.of( "type", "agent_gap", "target", "q",
            "days", 3, "reason", "r" ) ) );

        assertEquals( List.of( line( "snooze_opportunity", "snoozed", "agent_gap/q", "bot" ) ), lines );
    }

    // ---- mark_page_verified

    @Test
    void markPageVerifiedAuditsASystemPageRefusalWithTheVerifier() {
        final SystemPageRegistry sys = mock( SystemPageRegistry.class );
        when( sys.isSystemPage( "Main" ) ).thenReturn( true );
        final MarkPageVerifiedTool tool = new MarkPageVerifiedTool( mock( PageSaveHelper.class ),
            mock( PageManager.class ), sys );

        final List< String > lines = audit( () -> tool.execute( Map.of( "slugs", List.of( "Main" ),
            "verifier", "alice" ) ) );

        assertEquals( List.of( line( "mark_page_verified", "refused-system-page", "Main", "alice" ) ), lines );
    }

    // ---- rename_cluster

    @Test
    void renameClusterAuditsTheRenameAndTheConflictRefusal() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final StructuralIndexService structural = mock( StructuralIndexService.class );
        when( structural.getCluster( anyString() ) ).thenReturn( Optional.empty() );
        when( structural.listPagesByFilter( any( StructuralFilter.class ) ) ).thenReturn( List.of() );
        final RenameClusterTool tool = new RenameClusterTool( new ClusterRenameService( pm, structural,
            mock( PageSaveHelper.class ) ) );
        tool.setDefaultAuthor( "bot" );

        final List< String > renamed = audit( () -> tool.execute( Map.of( "from", "a", "to", "b", "confirm", true ) ) );

        assertEquals( List.of( line( "rename_cluster", "renamed", "a->b (0 pages)", "bot" ) ), renamed );
    }
}
