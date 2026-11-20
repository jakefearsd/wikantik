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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentRouteTableTest {

    private static HttpServletRequest request( final String servletPath, final String pathInfo ) {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( servletPath ).when( request ).getServletPath();
        Mockito.doReturn( pathInfo ).when( request ).getPathInfo();
        return request;
    }

    private static SegmentRouteTable< String > table( final String prefix, final String method,
                                                      final String pattern, final String handler ) {
        final SegmentRouteTable.Builder< String > builder =
            prefix == null ? SegmentRouteTable.builder() : SegmentRouteTable.builder( prefix );
        return builder.add( method, pattern, handler ).build();
    }

    @Test
    void aPrefixedTableStripsThePrefixFromServletPathPlusPathInfo() {
        final SegmentRouteTable< String > t = table( "/api/work", "GET", "", "x" );
        assertArrayEquals( new String[] { "items", "7" }, t.segments( request( "/api/work", "/items/7" ) ) );
    }

    @Test
    void aPathInfoTableIgnoresTheServletPath() {
        final SegmentRouteTable< String > t = table( null, "GET", "", "x" );
        assertArrayEquals( new String[] { "t1", "comments" },
            t.segments( request( "/api/anything-else", "/t1/comments" ) ) );
    }

    @Test
    void noPathIsNoSegments() {
        final SegmentRouteTable< String > t = table( null, "GET", "", "x" );
        assertEquals( 0, t.segments( request( null, null ) ).length );
        assertEquals( 0, t.segments( request( "/a", "/" ) ).length );
        assertEquals( 0, table( "/api/work", "GET", "", "x" ).segments( request( "/api/work", null ) ).length );
    }

    @Test
    void emptySegmentsAreDropped() {
        final SegmentRouteTable< String > t = table( null, "GET", "", "x" );
        assertArrayEquals( new String[] { "a", "b" }, t.segments( request( null, "//a//b/" ) ) );
        assertArrayEquals( new String[] { "x" }, t.segments( request( null, "//x" ) ) );
    }

    @Test
    void aPrefixedPathOutsideThePrefixIsTakenWhole() {
        final SegmentRouteTable< String > t = table( "/api/work", "GET", "", "x" );
        assertArrayEquals( new String[] { "7", "rotate" }, t.segments( request( null, "/7/rotate" ) ) );
    }

    @Test
    void placeholdersCaptureInOrder() {
        final SegmentRouteTable< String > t = table( null, "PATCH", "{}/comments/{}", "edit" );
        final SegmentRouteTable.Match< String > match =
            t.match( "PATCH", new String[] { "t1", "comments", "c9" } ).orElseThrow();
        assertEquals( "edit", match.handler() );
        assertEquals( List.of( "t1", "c9" ), match.captured() );
    }

    @Test
    void theEmptyPatternIsTheRoot() {
        final SegmentRouteTable< String > t = table( null, "POST", "", "create" );
        assertEquals( "create", t.match( "POST", t.segments( request( null, null ) ) ).orElseThrow().handler() );
        assertEquals( "create", t.match( "POST", t.segments( request( null, "/" ) ) ).orElseThrow().handler(),
            "a trailing slash is the same route" );
        assertTrue( t.match( "POST", new String[] { "x" } ).isEmpty() );
    }

    @Test
    void aMethodOrSegmentCountMismatchMatchesNothing() {
        final SegmentRouteTable< String > t = table( null, "DELETE", "items/{}", "drop" );
        assertTrue( t.match( "GET", new String[] { "items", "1" } ).isEmpty() );
        assertTrue( t.match( "DELETE", new String[] { "items" } ).isEmpty() );
        assertTrue( t.match( "DELETE", new String[] { "items", "1", "x" } ).isEmpty() );
        assertTrue( t.match( "DELETE", new String[] { "other", "1" } ).isEmpty() );
    }

    @Test
    void dispatchRunsTheMatchedActionOnTheContextAndReportsNoMatch() throws Exception {
        final List< String > ran = new ArrayList<>();
        final SegmentRouteTable< SegmentRouteTable.Action< List< String > > > t =
            SegmentRouteTable.< SegmentRouteTable.Action< List< String > > >builder()
                .add( "GET", "{}/x", ( ctx, req, resp, c ) -> ctx.add( c.get( 0 ) ) )
                .build();
        final HttpServletResponse response = Mockito.mock( HttpServletResponse.class );
        assertTrue( SegmentRouteTable.dispatch( t, ran, "GET", request( null, "/7/x" ), response ) );
        assertEquals( List.of( "7" ), ran );
        assertFalse( SegmentRouteTable.dispatch( t, ran, "GET", request( null, "/7" ), response ) );
        assertFalse( SegmentRouteTable.dispatch( t, ran, "POST", request( null, "/7/x" ), response ) );
        assertEquals( 1, ran.size() );
    }
}
