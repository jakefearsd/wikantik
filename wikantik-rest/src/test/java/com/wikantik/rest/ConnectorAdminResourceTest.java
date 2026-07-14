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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.connectors.SyncReport;
import com.wikantik.connectors.runtime.ConnectorRuntime;
import com.wikantik.connectors.runtime.ConnectorStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConnectorAdminResource}.
 *
 * <p>The {@link ConnectorRuntime} is injected via a subclass stub — no engine boot needed.
 */
class ConnectorAdminResourceTest {

    private ConnectorRuntime      runtime;
    private ConnectorAdminResource servlet;
    private HttpServletRequest    req;
    private HttpServletResponse   resp;
    private StringWriter          body;

    /** Test servlet subclass that injects the mocked runtime (or none, to simulate "disabled"). */
    private final class Stub extends ConnectorAdminResource {
        @Override
        protected ConnectorRuntime resolveRuntime() {
            return runtime;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        runtime = mock( ConnectorRuntime.class );
        servlet = new Stub();
        req  = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );
    }

    // -----------------------------------------------------------------------
    // GET /admin/connectors  (list)
    // -----------------------------------------------------------------------

    @Test
    void list_returnsConnectorsAsJsonArray() throws Exception {
        when( req.getPathInfo() ).thenReturn( null );
        when( runtime.list() ).thenReturn( List.of(
            new ConnectorStatus( "fs1", "filesystem", "2026-07-10T12:00:00Z", "ok", 42 ) ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonArray json = JsonParser.parseString( body.toString() ).getAsJsonArray();
        assertEquals( 1, json.size() );
        assertEquals( "fs1", json.get( 0 ).getAsJsonObject().get( "connectorId" ).getAsString() );
    }

    @Test
    void list_runtimeAbsent_returns503() throws Exception {
        runtime = null;
        when( req.getPathInfo() ).thenReturn( "/" );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    // -----------------------------------------------------------------------
    // POST /admin/connectors/{id}/sync
    // -----------------------------------------------------------------------

    @Test
    void sync_knownConnector_returnsSyncReportJson() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/fs1/sync" );
        when( runtime.syncNow( "fs1" ) ).thenReturn( new SyncReport( 3, 1, 2, 0, 0 ) );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertEquals( 3, json.get( "created" ).getAsInt() );
        assertEquals( 1, json.get( "updated" ).getAsInt() );
        assertEquals( 2, json.get( "unchanged" ).getAsInt() );
        assertEquals( 0, json.get( "deleted" ).getAsInt() );
        assertEquals( 0, json.get( "failed" ).getAsInt() );
    }

    @Test
    void sync_alreadyRunning_returns409() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/fs1/sync" );
        when( runtime.syncNow( "fs1" ) ).thenThrow(
            new com.wikantik.connectors.runtime.SyncInProgressException( "fs1" ) );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_CONFLICT );
    }

    @Test
    void sync_unknownConnector_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/nope/sync" );
        when( runtime.syncNow( "nope" ) ).thenThrow( new IllegalArgumentException( "unknown connector: nope" ) );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    @Test
    void post_runtimeAbsent_returns503() throws Exception {
        runtime = null;
        when( req.getPathInfo() ).thenReturn( "/fs1/sync" );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    @Test
    void post_unknownAction_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/fs1/frobnicate" );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    // -----------------------------------------------------------------------
    // GET /admin/connectors/{id}/status
    // -----------------------------------------------------------------------

    @Test
    void status_knownConnector_returnsStatusJson() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/fs1/status" );
        when( runtime.status( "fs1" ) ).thenReturn(
            new ConnectorStatus( "fs1", "filesystem", "2026-07-10T12:00:00Z", "ok", 42 ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertEquals( "fs1", json.get( "connectorId" ).getAsString() );
        assertEquals( "filesystem", json.get( "connectorType" ).getAsString() );
        assertEquals( 42, json.get( "syncedItemCount" ).getAsInt() );
    }

    @Test
    void status_unknownConnector_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/unknown/status" );
        when( runtime.status( "unknown" ) ).thenThrow( new IllegalArgumentException( "unknown connector: unknown" ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    @Test
    void get_unknownAction_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/fs1/bogus" );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }
}
