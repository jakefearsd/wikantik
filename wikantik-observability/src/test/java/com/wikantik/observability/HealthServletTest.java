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
package com.wikantik.observability;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wikantik.observability.health.HealthCheck;
import com.wikantik.observability.health.HealthResult;
import com.wikantik.observability.health.HealthStatus;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class HealthServletTest {

    private static final Gson GSON = new Gson();

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private ServletContext servletContext;

    private StringWriter responseBody;
    private HealthServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        responseBody = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( responseBody ) );

        servlet = new HealthServlet() {
            @Override
            public ServletContext getServletContext() {
                return servletContext;
            }
        };
    }

    @Test
    void returns200WhenAllChecksUp() throws Exception {
        final HealthCheck check1 = mockCheck( "engine", HealthResult.up() );
        final HealthCheck check2 = mockCheck( "database", HealthResult.up( 5 ) );
        when( servletContext.getAttribute( HealthServlet.HEALTH_CHECKS_ATTR ) ).thenReturn( List.of( check1, check2 ) );

        servlet.doGet( request, response );

        verify( response ).setStatus( 200 );
        final JsonObject body = GSON.fromJson( responseBody.toString(), JsonObject.class );
        assertEquals( "UP", body.get( "status" ).getAsString() );
        assertTrue( body.getAsJsonObject( "checks" ).has( "engine" ) );
        assertTrue( body.getAsJsonObject( "checks" ).has( "database" ) );
    }

    @Test
    void returns503WhenAnyCheckDown() throws Exception {
        final HealthCheck upCheck = mockCheck( "engine", HealthResult.up() );
        final HealthCheck downCheck = mockCheck( "database", HealthResult.down( "Connection refused" ) );
        when( servletContext.getAttribute( HealthServlet.HEALTH_CHECKS_ATTR ) ).thenReturn( List.of( upCheck, downCheck ) );

        servlet.doGet( request, response );

        verify( response ).setStatus( 503 );
        final JsonObject body = GSON.fromJson( responseBody.toString(), JsonObject.class );
        assertEquals( "DOWN", body.get( "status" ).getAsString() );

        final JsonObject dbCheck = body.getAsJsonObject( "checks" ).getAsJsonObject( "database" );
        assertEquals( "DOWN", dbCheck.get( "status" ).getAsString() );
        assertEquals( "Connection refused", dbCheck.get( "error" ).getAsString() );
    }

    @Test
    void returns503WhenNoChecksRegistered() throws Exception {
        when( servletContext.getAttribute( HealthServlet.HEALTH_CHECKS_ATTR ) ).thenReturn( null );

        servlet.doGet( request, response );

        verify( response ).setStatus( 503 );
        final JsonObject body = GSON.fromJson( responseBody.toString(), JsonObject.class );
        assertEquals( "DOWN", body.get( "status" ).getAsString() );
    }

    @Test
    void includesResponseTimeWhenPresent() throws Exception {
        final HealthCheck check = mockCheck( "database", HealthResult.up( 42 ) );
        when( servletContext.getAttribute( HealthServlet.HEALTH_CHECKS_ATTR ) ).thenReturn( List.of( check ) );

        servlet.doGet( request, response );

        final JsonObject body = GSON.fromJson( responseBody.toString(), JsonObject.class );
        final JsonObject dbCheck = body.getAsJsonObject( "checks" ).getAsJsonObject( "database" );
        assertEquals( 42, dbCheck.get( "responseTimeMs" ).getAsLong() );
    }

    @Test
    void omitsResponseTimeWhenNotMeasured() throws Exception {
        final HealthCheck check = mockCheck( "engine", HealthResult.up() );
        when( servletContext.getAttribute( HealthServlet.HEALTH_CHECKS_ATTR ) ).thenReturn( List.of( check ) );

        servlet.doGet( request, response );

        final JsonObject body = GSON.fromJson( responseBody.toString(), JsonObject.class );
        final JsonObject engineCheck = body.getAsJsonObject( "checks" ).getAsJsonObject( "engine" );
        assertFalse( engineCheck.has( "responseTimeMs" ) );
    }

    @Test
    void returns200WithDegradedStatusWhenCheckIsDegraded() throws Exception {
        final HealthCheck upCheck = mockCheck( "engine", HealthResult.up() );
        final HealthCheck degradedCheck = mockCheck( "searchIndex",
                new HealthResult( HealthStatus.DEGRADED, 100, java.util.Map.of( "warning", "index stale" ) ) );
        when( servletContext.getAttribute( HealthServlet.HEALTH_CHECKS_ATTR ) )
                .thenReturn( List.of( upCheck, degradedCheck ) );

        servlet.doGet( request, response );

        verify( response ).setStatus( 200 );
        final JsonObject body = GSON.fromJson( responseBody.toString(), JsonObject.class );
        assertEquals( "DEGRADED", body.get( "status" ).getAsString() );
    }

    @Test
    void downOverridesDegraded() throws Exception {
        final HealthCheck degradedCheck = mockCheck( "searchIndex",
                new HealthResult( HealthStatus.DEGRADED, -1, java.util.Map.of() ) );
        final HealthCheck downCheck = mockCheck( "database", HealthResult.down( "Connection refused" ) );
        when( servletContext.getAttribute( HealthServlet.HEALTH_CHECKS_ATTR ) )
                .thenReturn( List.of( degradedCheck, downCheck ) );

        servlet.doGet( request, response );

        verify( response ).setStatus( 503 );
        final JsonObject body = GSON.fromJson( responseBody.toString(), JsonObject.class );
        assertEquals( "DOWN", body.get( "status" ).getAsString() );
    }

    private HealthCheck mockCheck( final String name, final HealthResult result ) {
        return new HealthCheck() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public HealthResult check() {
                return result;
            }
        };
    }

}
