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
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code /admin/content/pages-without-frontmatter}: what is listed, how it pages, and when it rescans. */
class FrontmatterBackfillAdminHandlersTest {

    private PageManager pm;
    private SystemPageRegistry systemPages;
    private Engine engine;
    private final Properties props = new Properties();
    private final List< Page > pages = new ArrayList<>();
    private FrontmatterBackfillAdminHandlers handlers;

    private Page page( final String name, final String text, final Date modified ) throws Exception {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        when( p.getLastModified() ).thenReturn( modified );
        when( pm.getPureText( p ) ).thenReturn( text );
        pages.add( p );
        return p;
    }

    @BeforeEach
    void setUp() throws Exception {
        pm = mock( PageManager.class );
        systemPages = mock( SystemPageRegistry.class );
        engine = mock( Engine.class );
        when( engine.getWikiProperties() ).thenReturn( props );
        when( pm.getAllPages() ).thenAnswer( inv -> new ArrayList<>( pages ) );
        handlers = new FrontmatterBackfillAdminHandlers( () -> pm, () -> systemPages, () -> engine );
    }

    private record Reply( int status, JsonObject json ) { }

    private Reply list( final String limit, final String offset ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getParameter( "limit" ) ).thenReturn( limit );
        when( req.getParameter( "offset" ) ).thenReturn( offset );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw, true ) );
        final int[] status = { 200 };
        org.mockito.Mockito.doAnswer( inv -> {
            status[ 0 ] = inv.getArgument( 0 );
            return null;
        } ).when( resp ).setStatus( anyInt() );
        handlers.handleGetPagesWithoutFrontmatter( req, resp );
        return new Reply( status[ 0 ], JsonParser.parseString( sw.toString() ).getAsJsonObject() );
    }

    private static List< String > names( final Reply r ) {
        return r.json().getAsJsonArray( "pages" ).asList().stream()
            .map( e -> e.getAsJsonObject().get( "name" ).getAsString() ).toList();
    }

    @Test
    void listsOnlyPagesWithoutFrontmatterSortedByNameAndNotTheSystemPages() throws Exception {
        page( "Zebra", "no frontmatter here", null );
        page( "Alpha", "plain body", new Date( 1_700_000_000_000L ) );
        page( "Mid", "---\ntitle: T\n---\nbody", null );
        page( "SystemThing", "plain", null );
        page( "Empty", null, null );
        when( systemPages.isSystemPage( "SystemThing" ) ).thenReturn( true );

        final Reply r = list( null, null );

        assertEquals( 200, r.status() );
        assertEquals( List.of( "Alpha", "Empty", "Zebra" ), names( r ) );
        assertEquals( 3, r.json().get( "total" ).getAsInt() );
        final JsonObject alpha = r.json().getAsJsonArray( "pages" ).get( 0 ).getAsJsonObject();
        assertEquals( "2023-11-14T22:13:20Z", alpha.get( "lastModified" ).getAsString() );
        assertTrue( !r.json().getAsJsonArray( "pages" ).get( 2 ).getAsJsonObject().has( "lastModified" ),
            "an unknown modification time is left out, not invented" );
    }

    @Test
    void withoutASystemPageRegistryEveryPageIsConsidered() throws Exception {
        page( "SystemThing", "plain", null );
        handlers = new FrontmatterBackfillAdminHandlers( () -> pm, () -> null, () -> engine );

        assertEquals( List.of( "SystemThing" ), names( list( null, null ) ) );
    }

    @ParameterizedTest
    @CsvSource( { "2,0,'P1,P2',5", "2,2,'P3,P4',5", "2,4,P5,5", "2,5,'',5", "2,50,'',5", "10,0,'P1,P2,P3,P4,P5',5",
            "0,0,'',5", ",,'P1,P2,P3,P4,P5',5", "abc,xyz,'P1,P2,P3,P4,P5',5" } )
    void pagingSlicesTheSortedListAndTotalCountsAll( final String limit, final String offset, final String expected,
                                                     final int total ) throws Exception {
        for ( int i = 5; i >= 1; i-- ) {
            page( "P" + i, "body", null );
        }

        final Reply r = list( limit, offset );

        assertEquals( 200, r.status() );
        assertEquals( expected.isEmpty() ? List.of() : List.of( expected.split( "," ) ), names( r ) );
        assertEquals( total, r.json().get( "total" ).getAsInt() );
    }

    /** An admin typing a nonsense window gets an answer for it, not a server error. */
    @ParameterizedTest
    @CsvSource( { "-1,0", "5,-1", "-5,-5", "2147483647,1", "2147483647,2147483647", "1,2147483647" } )
    void anOutOfRangeWindowIsNotAServerError( final String limit, final String offset ) throws Exception {
        for ( int i = 1; i <= 3; i++ ) {
            page( "P" + i, "body", null );
        }

        final Reply r = list( limit, offset );

        assertEquals( 200, r.status(), "limit=" + limit + " offset=" + offset + " -> " + r.json() );
        assertEquals( 3, r.json().get( "total" ).getAsInt() );
    }

    @Test
    void anUnavailablePageManagerIs503() throws Exception {
        handlers = new FrontmatterBackfillAdminHandlers( () -> null, () -> systemPages, () -> engine );

        assertEquals( 503, list( null, null ).status() );
    }

    @Test
    void aScanFailureIs500() throws Exception {
        when( pm.getAllPages() ).thenThrow( new IllegalStateException( "provider down" ) );

        final Reply r = list( null, null );

        assertEquals( 500, r.status() );
        assertEquals( "Failed: provider down", r.json().get( "message" ).getAsString() );
    }

    @Test
    void theScanIsReusedWithinTheTtlAndRepeatedWhenCachingIsOff() throws Exception {
        page( "A", "body", null );
        props.setProperty( ScanCache.TTL_KEY, "300" );

        list( null, null );
        page( "B", "body", null );
        final Reply cached = list( null, null );

        verify( pm, times( 1 ) ).getAllPages();
        assertEquals( List.of( "A" ), names( cached ), "a page added inside the TTL is not seen yet" );

        props.setProperty( ScanCache.TTL_KEY, "0" );
        final Reply fresh = list( null, null );

        assertEquals( List.of( "A", "B" ), names( fresh ) );
        verify( pm, times( 2 ) ).getAllPages();
        list( null, null );
        verify( pm, times( 3 ) ).getAllPages();
    }

    @Test
    void aCachedScanStillPagesPerRequest() throws Exception {
        for ( int i = 1; i <= 4; i++ ) {
            page( "P" + i, "body", null );
        }
        props.setProperty( ScanCache.TTL_KEY, "300" );

        assertEquals( List.of( "P1", "P2" ), names( list( "2", "0" ) ) );
        assertEquals( List.of( "P3", "P4" ), names( list( "2", "2" ) ) );
        verify( pm, times( 1 ) ).getAllPages();
    }

    @Test
    void theBackfillStatusStartsIdle() throws Exception {
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw, true ) );

        handlers.handleGetBackfillStatus( resp );

        assertEquals( Map.of( "running", false, "total", 0.0, "processed", 0.0, "errors", 0.0 ),
            new com.google.gson.Gson().fromJson( sw.toString(), Map.class ) );
    }
}
