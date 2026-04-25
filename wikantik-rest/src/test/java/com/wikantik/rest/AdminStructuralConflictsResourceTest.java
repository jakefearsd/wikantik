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
import com.wikantik.api.structure.StructuralConflict;
import com.wikantik.api.structure.StructuralIndexService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminStructuralConflictsResourceTest {

    private StructuralIndexService svc;
    private AdminStructuralConflictsResource resource;

    @BeforeEach
    void setUp() {
        svc = mock( StructuralIndexService.class );
        final Engine engine = mock( Engine.class );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( svc );
        resource = new AdminStructuralConflictsResource();
        resource.setEngineForTesting( engine );
    }

    @Test
    void empty_conflicts_returns_zero_count() throws Exception {
        when( svc.conflicts() ).thenReturn( List.of() );
        final JsonObject body = call( null );
        assertEquals( 0, body.getAsJsonObject( "data" ).get( "count" ).getAsInt() );
    }

    @Test
    void returns_aggregated_payload_with_per_kind_counts() throws Exception {
        when( svc.conflicts() ).thenReturn( List.of(
                new StructuralConflict( "A", null, StructuralConflict.Kind.MISSING_CANONICAL_ID, "no id" ),
                new StructuralConflict( "B", "01B", StructuralConflict.Kind.RELATION_ISSUE, "broken target" ),
                new StructuralConflict( "C", "01C", StructuralConflict.Kind.RELATION_ISSUE, "self-loop" )
        ) );
        final JsonObject body = call( null );
        final JsonObject data = body.getAsJsonObject( "data" );
        assertEquals( 3, data.get( "count" ).getAsInt() );
        assertEquals( 1, data.get( "missing_canonical_id_count" ).getAsInt() );
        assertEquals( 2, data.get( "relation_issue_count" ).getAsInt() );
    }

    @Test
    void kind_filter_narrows_results() throws Exception {
        when( svc.conflicts() ).thenReturn( List.of(
                new StructuralConflict( "A", null, StructuralConflict.Kind.MISSING_CANONICAL_ID, "x" ),
                new StructuralConflict( "B", "01B", StructuralConflict.Kind.RELATION_ISSUE, "y" )
        ) );
        final JsonObject body = call( "kind=RELATION_ISSUE" );
        assertEquals( 1, body.getAsJsonObject( "data" ).get( "count" ).getAsInt() );
        assertEquals( "B",
                body.getAsJsonObject( "data" )
                    .getAsJsonArray( "conflicts" )
                    .get( 0 ).getAsJsonObject().get( "slug" ).getAsString() );
    }

    @Test
    void service_absent_returns_503() throws Exception {
        final Engine engine = mock( Engine.class );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( null );
        final var r = new AdminStructuralConflictsResource();
        r.setEngineForTesting( engine );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        r.doGet( req, resp );
        verify( resp ).setStatus( 503 );
    }

    private JsonObject call( final String query ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        if ( query != null ) {
            for ( final String pair : query.split( "&" ) ) {
                final String[] kv = pair.split( "=" );
                when( req.getParameter( kv[ 0 ] ) ).thenReturn( kv.length > 1 ? kv[ 1 ] : null );
            }
        }
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        resource.doGet( req, resp );
        return JsonParser.parseString( sw.toString() ).getAsJsonObject();
    }
}
