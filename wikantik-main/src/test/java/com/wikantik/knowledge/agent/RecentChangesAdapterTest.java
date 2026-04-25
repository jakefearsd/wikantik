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
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.RecentChange;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecentChangesAdapterTest {

    private PageManager pm;
    private RecentChangesAdapter adapter;

    @BeforeEach
    void setUp() {
        pm = mock( PageManager.class );
        adapter = new RecentChangesAdapter( pm );
    }

    @Test
    void returns_most_recent_versions_descending() {
        final Page v1 = mockVersion( 1, Instant.parse( "2026-04-01T00:00:00Z" ), "alice", "initial" );
        final Page v2 = mockVersion( 2, Instant.parse( "2026-04-15T00:00:00Z" ), "bob",   "edit" );
        final Page v3 = mockVersion( 3, Instant.parse( "2026-04-22T00:00:00Z" ), "alice", "polish" );
        when( pm.getVersionHistory( "HybridRetrieval" ) ).thenReturn( List.of( v1, v2, v3 ) );

        final List< RecentChange > out = adapter.recentChanges( "HybridRetrieval", 5 );

        assertEquals( 3, out.size() );
        assertEquals( 3,        out.get( 0 ).version() );
        assertEquals( "alice",  out.get( 0 ).author() );
        assertEquals( "polish", out.get( 0 ).summary() );
        assertEquals( 2,        out.get( 1 ).version() );
        assertEquals( 1,        out.get( 2 ).version() );
    }

    @Test
    void caps_at_requested_limit() {
        final List< Page > many = java.util.stream.IntStream.rangeClosed( 1, 20 )
                .mapToObj( i -> mockVersion( i, Instant.now().minusSeconds( i ), "u", "n" ) )
                .toList();
        when( pm.getVersionHistory( "Big" ) ).thenReturn( many );

        final List< RecentChange > out = adapter.recentChanges( "Big", 5 );

        assertEquals( 5, out.size() );
    }

    @Test
    void null_history_returns_empty_list() {
        when( pm.getVersionHistory( "Missing" ) ).thenReturn( null );
        assertTrue( adapter.recentChanges( "Missing", 5 ).isEmpty() );
    }

    @Test
    void provider_throws_returns_empty_list_and_logs() {
        when( pm.getVersionHistory( "Bad" ) ).thenThrow( new RuntimeException( "boom" ) );
        assertTrue( adapter.recentChanges( "Bad", 5 ).isEmpty() );
    }

    private static Page mockVersion( final int v, final Instant when, final String author, final String note ) {
        final Page p = mock( Page.class );
        when( p.getVersion() ).thenReturn( v );
        when( p.getLastModified() ).thenReturn( Date.from( when ) );
        when( p.getAuthor() ).thenReturn( author );
        when( p.getAttribute( Page.CHANGENOTE ) ).thenReturn( note );
        return p;
    }
}
