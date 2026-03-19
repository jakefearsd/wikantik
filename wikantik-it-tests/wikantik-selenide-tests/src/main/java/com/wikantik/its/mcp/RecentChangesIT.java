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

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Integration tests for the {@code recent_changes} MCP tool.
 */
public class RecentChangesIT extends WithMcpTestSetup {

    @Test
    public void recentChangesIncludesNewlyWrittenPage() {
        final String pageName = uniquePageName( "RecentNew" );
        mcp.writePage( pageName, "New page for recent changes" );

        final Map< String, Object > result = mcp.recentChanges();
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > changes = ( List< Map< String, Object > > ) result.get( "changes" );

        final boolean found = changes.stream().anyMatch( c -> pageName.equals( c.get( "pageName" ) ) );
        Assertions.assertTrue( found, "Newly written page should appear in recent changes" );
    }

    @Test
    public void recentChangesRespectsSinceFilter() {
        final Instant before = Instant.now().truncatedTo( java.time.temporal.ChronoUnit.SECONDS );
        final String pageName = uniquePageName( "RecentSince" );
        mcp.writePage( pageName, "Page after timestamp" );

        final Map< String, Object > result = mcp.recentChanges( before.toString() );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > changes = ( List< Map< String, Object > > ) result.get( "changes" );

        final boolean found = changes.stream().anyMatch( c -> pageName.equals( c.get( "pageName" ) ) );
        Assertions.assertTrue( found, "Page written after 'since' should appear" );

        // All returned changes should be after the 'since' date
        for ( final Map< String, Object > change : changes ) {
            final String lastMod = ( String ) change.get( "lastModified" );
            if ( lastMod != null ) {
                Assertions.assertTrue( Instant.parse( lastMod ).isAfter( before ) || Instant.parse( lastMod ).equals( before ),
                        "All changes should be after the 'since' timestamp" );
            }
        }
    }

    @Test
    public void recentChangesRespectsLimit() {
        final Map< String, Object > result = mcp.recentChanges( 2 );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > changes = ( List< Map< String, Object > > ) result.get( "changes" );

        Assertions.assertTrue( changes.size() <= 2, "Should return at most 2 changes" );
    }

    @Test
    public void recentChangesIncludesChangeNote() {
        final String pageName = uniquePageName( "RecentNote" );
        final String note = "IT change " + System.currentTimeMillis();
        mcp.writePage( pageName, "Content", note );

        final Map< String, Object > result = mcp.recentChanges();
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > changes = ( List< Map< String, Object > > ) result.get( "changes" );

        final boolean found = changes.stream()
                .anyMatch( c -> pageName.equals( c.get( "pageName" ) ) && note.equals( c.get( "changeNote" ) ) );
        Assertions.assertTrue( found, "Change note should be preserved in recent changes" );
    }

    @Test
    public void recentChangesOrderedByDateDescending() {
        final Map< String, Object > result = mcp.recentChanges();
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > changes = ( List< Map< String, Object > > ) result.get( "changes" );

        for ( int i = 1; i < changes.size(); i++ ) {
            final String prevDate = ( String ) changes.get( i - 1 ).get( "lastModified" );
            final String currDate = ( String ) changes.get( i ).get( "lastModified" );
            if ( prevDate != null && currDate != null ) {
                Assertions.assertTrue( Instant.parse( prevDate ).compareTo( Instant.parse( currDate ) ) >= 0,
                        "Changes should be ordered newest first" );
            }
        }
    }
}
