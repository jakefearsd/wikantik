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
import com.wikantik.api.structure.IndexHealth;
import com.wikantik.api.structure.StructuralIndexService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StructuralIndexHealthResourceTest {

    @Test
    void reports_up_status_when_service_healthy() throws Exception {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.health() ).thenReturn( new IndexHealth(
                IndexHealth.Status.UP, 1024, 0, Instant.EPOCH, Instant.EPOCH, 500L, 3L ) );
        when( svc.snapshot() ).thenReturn( new StructuralIndexService.StructuralProjectionSnapshot() {
            @Override public int pageCount()       { return 1024; }
            @Override public int clusterCount()    { return 24; }
            @Override public int tagCount()        { return 185; }
            @Override public Instant generatedAt() { return Instant.EPOCH; }
        } );
        final Engine engine = mock( Engine.class );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( svc );

        final StructuralIndexHealthResource r = new StructuralIndexHealthResource();
        r.setEngineForTesting( engine );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        r.doGet( req, resp );

        final JsonObject body = JsonParser.parseString( sw.toString() ).getAsJsonObject();
        assertEquals( "UP", body.get( "status" ).getAsString() );
        assertEquals( 1024, body.get( "pages" ).getAsInt() );
        assertEquals( 0,    body.get( "unclaimed_canonical_ids" ).getAsInt() );
    }

    @Test
    void reports_503_when_service_absent() throws Exception {
        final Engine engine = mock( Engine.class );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( null );
        final StructuralIndexHealthResource r = new StructuralIndexHealthResource();
        r.setEngineForTesting( engine );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        r.doGet( req, resp );

        verify( resp ).setStatus( 503 );
    }
}
