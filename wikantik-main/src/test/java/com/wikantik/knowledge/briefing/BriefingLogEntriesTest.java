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
package com.wikantik.knowledge.briefing;

import com.wikantik.api.briefing.BriefingItem;
import com.wikantik.api.briefing.BriefingLogEntry;
import com.wikantik.api.briefing.BriefingRequest;
import com.wikantik.api.briefing.ContextBriefing;
import com.wikantik.api.briefing.ScopeMode;
import com.wikantik.api.bundle.BundleCoverage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BriefingLogEntriesTest {

    private static BriefingItem item( final String slug, final String origin, final boolean included ) {
        return new BriefingItem( slug, "id-" + slug, slug, "summary", origin, included,
                included ? "content" : null );
    }

    private static ContextBriefing briefing( final BriefingItem... items ) {
        return new ContextBriefing( "p", List.of(), BundleCoverage.empty(), List.of( items ), List.of(), 4000, 123 );
    }

    private static BriefingRequest request( final String prompt, final ScopeMode mode ) {
        return new BriefingRequest( List.of( "A", "B" ), List.of( "c1" ), prompt, null, mode );
    }

    @Test
    void pointerCountCountsOnlyTrimmedPinAndClusterItems() {
        final BriefingLogEntry e = BriefingLogEntries.of( request( null, ScopeMode.PREFER ), briefing(
                item( "a", "pin", true ), item( "b", "pin", false ),
                item( "c", "cluster", true ), item( "d", "cluster", false ),
                item( "e", "cited", false ), item( "f", "elsewhere", false ) ), "api_briefing" );

        assertEquals( 1, e.pinCount(), "only included pins" );
        assertEquals( 2, e.pointerCount(), "a trimmed pin and a trimmed cluster item; cited/elsewhere excluded" );
    }

    @Test
    void citedCountCountsEveryCitedItemWhetherOrNotIncluded() {
        final BriefingLogEntry e = BriefingLogEntries.of( request( null, ScopeMode.PREFER ), briefing(
                item( "a", "cited", true ), item( "b", "cited", false ), item( "c", "pin", true ) ), "api_briefing" );

        assertEquals( 2, e.citedCount() );
        assertEquals( 0, e.elsewhereCount() );
    }

    @Test
    void elsewhereCountCountsElsewhereItems() {
        final BriefingLogEntry e = BriefingLogEntries.of( request( null, ScopeMode.PREFER ), briefing(
                item( "a", "elsewhere", false ), item( "b", "elsewhere", false ), item( "c", "cited", false ) ),
                "api_briefing" );

        assertEquals( 2, e.elsewhereCount() );
        assertEquals( 1, e.citedCount() );
        assertEquals( 0, e.pointerCount() );
    }

    @Test
    void scopeModeIsLoggedInWireForm() {
        assertEquals( "strict",
                BriefingLogEntries.of( request( null, ScopeMode.STRICT ), briefing(), "api_briefing" ).scopeMode() );
        assertEquals( "prefer",
                BriefingLogEntries.of( request( null, ScopeMode.PREFER ), briefing(), "api_briefing" ).scopeMode() );
    }

    @Test
    void carriesRequestAndBriefingFields() {
        final BriefingLogEntry e = BriefingLogEntries.of( request( "deploy steps", ScopeMode.PREFER ),
                briefing( item( "a", "pin", true ) ), "mcp_get_briefing" );

        assertEquals( "A,B", e.pins() );
        assertEquals( "c1", e.clusters() );
        assertTrue( e.promptPresent() );
        assertEquals( 4000, e.budgetRequested() );
        assertEquals( 123, e.budgetUsed() );
        assertEquals( 0, e.sectionCount() );
        assertEquals( "mcp_get_briefing", e.surface() );
    }

    @Test
    void blankPromptIsNotPresent() {
        assertFalse( BriefingLogEntries.of( request( "  ", ScopeMode.PREFER ), briefing(), "s" ).promptPresent() );
        assertFalse( BriefingLogEntries.of( request( null, ScopeMode.PREFER ), briefing(), "s" ).promptPresent() );
    }

    @Test
    void nullOriginIsNotCounted() {
        final BriefingLogEntry e = BriefingLogEntries.of( request( null, ScopeMode.PREFER ),
                briefing( item( "a", null, false ) ), "s" );

        assertEquals( 0, e.pinCount() );
        assertEquals( 0, e.pointerCount() );
        assertEquals( 0, e.citedCount() );
        assertEquals( 0, e.elsewhereCount() );
    }
}
