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
package com.wikantik.seo;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.WikiProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentDateServiceTest {

    private static final String PAGE = "Doc";
    private static final Instant BASE = Instant.parse( "2026-01-01T00:00:00Z" );

    private PageManager pm;
    private ContentDateRepository repo;
    private ContentDateService service;
    private final Map< Integer, String > texts = new HashMap<>();
    private final List< Page > history = new ArrayList<>();

    private static String words( final int n ) {
        final StringBuilder sb = new StringBuilder();
        for( int i = 0; i < n; i++ ) {
            sb.append( 'w' ).append( i ).append( ' ' );
        }
        return sb.toString().trim();
    }

    private static Instant at( final int version ) {
        return BASE.plusSeconds( 86400L * version );
    }

    private Page version( final int v, final String text ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( PAGE );
        when( p.getVersion() ).thenReturn( v );
        when( p.getLastModified() ).thenReturn( Date.from( at( v ) ) );
        texts.put( v, text );
        history.add( p );
        return p;
    }

    /** Makes version {@code v} the live one. */
    private void makeCurrent( final int v ) {
        final Page p = history.get( v - 1 );
        when( pm.getPage( PAGE ) ).thenReturn( p );
        when( pm.getPureText( PAGE, WikiProvider.LATEST_VERSION ) ).thenReturn( texts.get( v ) );
    }

    @BeforeEach
    void setUp() {
        pm = mock( PageManager.class );
        repo = mock( ContentDateRepository.class );
        service = new ContentDateService( pm, repo, new ContentChangeRule( 5, 100 ) );
        when( pm.getPureText( anyString(), any( Integer.class ) ) )
            .thenAnswer( inv -> {
                final int v = inv.getArgument( 1 );
                return v == WikiProvider.LATEST_VERSION ? null : texts.get( v );
            } );
        when( pm.getVersionHistory( PAGE ) ).thenAnswer( inv -> new ArrayList<>( history ) );
    }

    @Test
    void newPageGetsFirstVersionTime() {
        version( 1, words( 200 ) );
        makeCurrent( 1 );
        when( repo.find( PAGE ) ).thenReturn( Optional.empty() );

        service.onPageSaved( PAGE );

        verify( repo ).upsert( new ContentDate( PAGE, at( 1 ), 1 ) );
    }

    @Test
    void frontmatterOnlyEditLeavesRowUntouched() {
        version( 1, words( 200 ) );
        version( 2, "---\ntags: [a]\n---\n" + words( 200 ) );
        makeCurrent( 2 );
        when( repo.find( PAGE ) ).thenReturn( Optional.of( new ContentDate( PAGE, at( 1 ), 1 ) ) );

        service.onPageSaved( PAGE );

        verify( repo, never() ).upsert( any() );
    }

    @Test
    void tenSmallEditsAccumulateAndMoveDateOnTheCrossingEdit() {
        final String[] w = words( 400 ).split( " " );
        version( 1, String.join( " ", w ) );
        ContentDate row = new ContentDate( PAGE, at( 1 ), 1 );
        // edit k replaces word k-1 (cumulative): 2k changed words against v1; threshold is 20
        for( int k = 1; k <= 10; k++ ) {
            w[ k - 1 ] = "edit" + k;
            version( k + 1, String.join( " ", w ) );
            makeCurrent( k + 1 );
            when( repo.find( PAGE ) ).thenReturn( Optional.of( row ) );
            service.onPageSaved( PAGE );
            if( k < 10 ) {
                verify( repo, never() ).upsert( any() );
            }
        }
        verify( repo ).upsert( new ContentDate( PAGE, at( 11 ), 11 ) );
    }

    @Test
    void missingBaselineTextCountsAsSubstantial() {
        version( 1, words( 200 ) );
        version( 2, words( 200 ) );
        texts.remove( 1 );
        makeCurrent( 2 );
        when( repo.find( PAGE ) ).thenReturn( Optional.of( new ContentDate( PAGE, at( 1 ), 1 ) ) );

        service.onPageSaved( PAGE );

        verify( repo ).upsert( new ContentDate( PAGE, at( 2 ), 2 ) );
    }

    @Test
    void recomputeWalksHistoryOldestToNewest() {
        version( 1, words( 200 ) );
        version( 2, "---\ntags: [x]\n---\n" + words( 200 ) );
        version( 3, words( 100 ) + " " + words( 300 ).replace( 'w', 'z' ) );
        version( 4, words( 100 ) + " " + words( 300 ).replace( 'w', 'z' ) + " typo" );
        // newest-first, as some providers return it: recompute must not depend on order
        java.util.Collections.reverse( history );

        final ContentDate result = service.recompute( PAGE );

        assertEquals( new ContentDate( PAGE, at( 3 ), 3 ), result );
        verify( repo ).upsert( result );
    }

    @Test
    void recomputeWithoutHistoryUsesLastModifiedAndCurrentVersion() {
        final Page p = mock( Page.class );
        when( p.getVersion() ).thenReturn( 4 );
        when( p.getLastModified() ).thenReturn( Date.from( at( 7 ) ) );
        when( pm.getPage( PAGE ) ).thenReturn( p );
        when( pm.getVersionHistory( PAGE ) ).thenReturn( List.of() );

        assertEquals( new ContentDate( PAGE, at( 7 ), 4 ), service.recompute( PAGE ) );
    }

    @Test
    void repositoryFailureOnSaveIsLoggedNotThrown() {
        version( 1, words( 200 ) );
        makeCurrent( 1 );
        when( repo.find( PAGE ) ).thenThrow( new IllegalStateException( "db down" ) );

        assertDoesNotThrow( () -> service.onPageSaved( PAGE ) );
    }

    @Test
    void deleteAndRenameDelegateAndSwallowFailures() {
        service.onPageDeleted( PAGE );
        verify( repo ).delete( PAGE );
        service.onPageRenamed( "Old", "New" );
        verify( repo ).rename( "Old", "New" );

        doThrow( new IllegalStateException( "db down" ) ).when( repo ).delete( anyString() );
        assertDoesNotThrow( () -> service.onPageDeleted( PAGE ) );
        doThrow( new IllegalStateException( "db down" ) ).when( repo ).rename( anyString(), anyString() );
        assertDoesNotThrow( () -> service.onPageRenamed( "A", "B" ) );
    }

    @Test
    void contentDatePrefersStoredThenLastModifiedThenNull() {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( PAGE );
        when( p.getLastModified() ).thenReturn( Date.from( at( 9 ) ) );
        when( repo.find( PAGE ) ).thenReturn( Optional.of( new ContentDate( PAGE, at( 2 ), 1 ) ) );
        assertEquals( at( 2 ), service.contentDate( p ) );

        when( repo.find( PAGE ) ).thenReturn( Optional.empty() );
        assertEquals( at( 9 ), service.contentDate( p ) );

        when( repo.find( PAGE ) ).thenThrow( new IllegalStateException( "db down" ) );
        assertEquals( at( 9 ), service.contentDate( p ) );

        when( p.getLastModified() ).thenReturn( null );
        assertNull( service.contentDate( p ) );
    }

    @Test
    void allContentDatesDelegatesAndDegradesToEmpty() {
        when( repo.findAll() ).thenReturn( Map.of( PAGE, at( 1 ) ) );
        assertEquals( Map.of( PAGE, at( 1 ) ), service.allContentDates() );
        when( repo.findAll() ).thenThrow( new IllegalStateException( "db down" ) );
        assertEquals( Map.of(), service.allContentDates() );
    }
}
