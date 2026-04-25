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
import com.wikantik.api.structure.RelationEdge;
import com.wikantik.api.structure.RelationType;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.api.structure.TraversalSpec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PageRelationsResourceTest {

    private StructuralIndexService svc;
    private PageRelationsResource resource;

    @BeforeEach
    void setUp() {
        svc = mock( StructuralIndexService.class );
        final Engine engine = mock( Engine.class );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( svc );
        resource = new PageRelationsResource();
        resource.setEngineForTesting( engine );
    }

    @Test
    void out_default_calls_outgoingRelations_at_depth_one() throws Exception {
        when( svc.outgoingRelations( eq( "01A" ), any() ) ).thenReturn( List.of(
                new RelationEdge( "01A", "A", "01B", "B", "B Title",
                        RelationType.PART_OF, 1 ) ) );
        final JsonObject body = call( "/01A", null );
        assertEquals( 1, body.getAsJsonObject( "data" ).get( "count" ).getAsInt() );
        assertEquals( "out", body.getAsJsonObject( "data" ).get( "direction" ).getAsString() );
        verify( svc ).outgoingRelations( eq( "01A" ), eq( Optional.empty() ) );
    }

    @Test
    void in_direction_calls_incomingRelations() throws Exception {
        when( svc.incomingRelations( eq( "01A" ), any() ) ).thenReturn( List.of() );
        call( "/01A", "direction=in" );
        verify( svc ).incomingRelations( eq( "01A" ), any() );
        verify( svc, never() ).outgoingRelations( any(), any() );
    }

    @Test
    void deeper_than_one_dispatches_to_traverse() throws Exception {
        when( svc.traverse( eq( "01A" ), any( TraversalSpec.class ) ) ).thenReturn( List.of() );
        call( "/01A", "depth=3" );
        verify( svc ).traverse( eq( "01A" ), any( TraversalSpec.class ) );
    }

    @Test
    void type_filter_passes_through() throws Exception {
        when( svc.outgoingRelations( eq( "01A" ), any() ) ).thenReturn( List.of() );
        call( "/01A", "type=part-of" );
        verify( svc ).outgoingRelations( "01A", Optional.of( RelationType.PART_OF ) );
    }

    @Test
    void unknown_type_query_param_resolves_to_no_filter() throws Exception {
        when( svc.outgoingRelations( eq( "01A" ), any() ) ).thenReturn( List.of() );
        call( "/01A", "type=does-not-exist" );
        verify( svc ).outgoingRelations( "01A", Optional.empty() );
    }

    @Test
    void malformed_path_returns_400() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getPathInfo() ).thenReturn( "/01A/extra" );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        resource.doGet( req, resp );
        verify( resp ).setStatus( 400 );
    }

    private JsonObject call( final String path, final String query ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getPathInfo() ).thenReturn( path );
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
