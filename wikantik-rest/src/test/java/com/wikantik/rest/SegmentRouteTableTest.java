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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentRouteTableTest {

    private static HttpServletRequest request( final String method, final String servletPath, final String pathInfo ) {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( method ).when( request ).getMethod();
        Mockito.doReturn( servletPath ).when( request ).getServletPath();
        Mockito.doReturn( pathInfo ).when( request ).getPathInfo();
        return request;
    }

    @Test
    void segmentsStripThePrefix() {
        assertArrayEquals( new String[] { "items", "7" },
            SegmentRouteTable.segments( request( "GET", "/api/work", "/items/7" ), "/api/work" ) );
        assertArrayEquals( new String[] { "t1", "comments" },
            SegmentRouteTable.segments( request( "GET", "/api/comments", "/t1/comments" ), "/api/comments" ) );
    }

    @Test
    void segmentsOfThePrefixAloneOrOfNoPathAreEmpty() {
        assertEquals( 0, SegmentRouteTable.segments( request( "GET", "/api/comments", null ), "/api/comments" ).length );
        assertEquals( 0, SegmentRouteTable.segments( request( "GET", null, null ), "/api/comments" ).length );
        assertEquals( 0, SegmentRouteTable.segments( request( "GET", "/api/comments", "/" ), "/api/comments" ).length );
    }

    @Test
    void emptySegmentsAreDropped() {
        assertArrayEquals( new String[] { "a", "b" },
            SegmentRouteTable.segments( request( "GET", "/api/comments", "//a//b/" ), "/api/comments" ) );
        assertArrayEquals( new String[] { "x" },
            SegmentRouteTable.segments( request( "GET", "/api/comments", "//x" ), "/api/comments" ) );
    }

    @Test
    void aPathOutsideThePrefixIsTakenWhole() {
        assertArrayEquals( new String[] { "7", "rotate" },
            SegmentRouteTable.segments( request( "GET", null, "/7/rotate" ), "/api/work" ) );
    }

    @Test
    void placeholdersCaptureInOrder() {
        final SegmentRouteTable< String > table = new SegmentRouteTable< String >( "/api/comments" )
            .add( "PATCH", "{}/comments/{}", "edit" );
        final SegmentRouteTable.Match< String > match =
            table.match( request( "PATCH", "/api/comments", "/t1/comments/c9" ) ).orElseThrow();
        assertEquals( "edit", match.handler() );
        assertEquals( List.of( "t1", "c9" ), match.captured() );
    }

    @Test
    void theEmptyPatternIsThePrefixItself() {
        final SegmentRouteTable< String > table = new SegmentRouteTable< String >( "/api/comments" ).add( "POST", "", "create" );
        assertEquals( "create", table.match( request( "POST", "/api/comments", null ) ).orElseThrow().handler() );
        assertEquals( "create", table.match( request( "POST", "/api/comments", "/" ) ).orElseThrow().handler(),
            "a trailing slash is the same route" );
        assertTrue( table.match( request( "POST", "/api/comments", "/x" ) ).isEmpty() );
    }

    @Test
    void aMethodMismatchMatchesNothing() {
        final SegmentRouteTable< String > table = new SegmentRouteTable< String >( "/api/comments" ).add( "DELETE", "{}", "drop" );
        assertTrue( table.match( request( "GET", "/api/comments", "/t1" ) ).isEmpty() );
        assertTrue( table.match( request( null, "/api/comments", "/t1" ) ).isEmpty() );
        assertEquals( "drop", table.match( request( "delete", "/api/comments", "/t1" ) ).orElseThrow().handler(),
            "the request method is read case-insensitively" );
    }

    @Test
    void aSegmentCountMismatchMatchesNothing() {
        final SegmentRouteTable< String > table = new SegmentRouteTable< String >( "/api/work" ).add( "GET", "items/{}", "one" );
        assertTrue( table.match( request( "GET", "/api/work", "/items" ) ).isEmpty() );
        assertTrue( table.match( request( "GET", "/api/work", "/items/7/extra" ) ).isEmpty() );
        assertTrue( table.match( request( "GET", "/api/work", "/other/7" ) ).isEmpty() );
    }

    @Test
    void matchingAnAlreadySplitPathNeedsNoRequest() {
        final SegmentRouteTable< String > table = new SegmentRouteTable< String >( "/api/work" ).add( "GET", "items/{}", "one" );
        assertEquals( List.of( "7" ), table.match( "GET", new String[] { "items", "7" } ).orElseThrow().captured() );
    }
}
