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
import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.api.connectors.SyncStateStore;
import com.wikantik.connectors.SyncReport;
import com.wikantik.connectors.runtime.ConnectorRuntime;
import com.wikantik.connectors.runtime.ConnectorStatus;
import com.wikantik.connectors.state.JdbcSyncRunStore;
import com.wikantik.connectors.state.SyncRunRow;
import com.wikantik.derived.ConnectorConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConnectorAdminResource}.
 *
 * <p>The {@link ConnectorRuntime} and its sibling stores are injected via a subclass stub — no
 * engine boot needed.
 */
class ConnectorAdminResourceTest {

    private ConnectorRuntime       runtime;
    private ConnectorConfigService configService;
    private JdbcSyncRunStore       runStore;
    private SyncStateStore         syncState;
    private CredentialStore        credStore;
    private ConnectorAdminResource servlet;
    private HttpServletRequest    req;
    private HttpServletResponse   resp;
    private StringWriter          body;

    /** Test servlet subclass that injects the mocked runtime/stores (or none, to simulate
     *  "disabled"/"not yet wired"). */
    private final class Stub extends ConnectorAdminResource {
        @Override
        protected ConnectorRuntime resolveRuntime() {
            return runtime;
        }
        @Override
        protected ConnectorConfigService resolveConfigService() {
            return configService;
        }
        @Override
        protected JdbcSyncRunStore resolveRunStore() {
            return runStore;
        }
        @Override
        protected SyncStateStore resolveSyncState() {
            return syncState;
        }
        @Override
        protected CredentialStore resolveCredentialStore() {
            return credStore;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        runtime = mock( ConnectorRuntime.class );
        configService = mock( ConnectorConfigService.class );
        runStore = mock( JdbcSyncRunStore.class );
        syncState = mock( SyncStateStore.class );
        credStore = mock( CredentialStore.class );
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
        when( runtime.syncingEnabled() ).thenReturn( true );
        final ConnectorConfigService.ConnectorView view = new ConnectorConfigService.ConnectorView(
            "fs1", "filesystem", "properties", true, 24, new JsonObject(), null, null, null, List.of() );
        when( configService.list() ).thenReturn( List.of( view ) );
        when( runtime.status( "fs1" ) ).thenReturn(
            new ConnectorStatus( "fs1", "filesystem", "2026-07-10T12:00:00Z", "ok", 42 ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        final JsonArray connectors = json.getAsJsonArray( "connectors" );
        assertEquals( 1, connectors.size() );
        assertEquals( "fs1", connectors.get( 0 ).getAsJsonObject().get( "id" ).getAsString() );
    }

    @Test
    void list_runtimeAbsent_returns503() throws Exception {
        runtime = null;
        when( req.getPathInfo() ).thenReturn( "/" );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    @Test
    void listMergesConfigViewsAndStatus() throws Exception {
        when( req.getPathInfo() ).thenReturn( null );
        when( runtime.syncingEnabled() ).thenReturn( true );
        when( credStore.enabled() ).thenReturn( true );
        final JsonObject cfg = new JsonObject();
        cfg.addProperty( "folder_ids", "abc" );
        final ConnectorConfigService.ConnectorView view = new ConnectorConfigService.ConnectorView(
            "gd1", "gdrive", "db", true, 6, cfg, "cluster1", "tag1,tag2", "GDrive/", List.of( "client_secret" ) );
        when( configService.list() ).thenReturn( List.of( view ) );
        when( runtime.status( "gd1" ) ).thenReturn(
            new ConnectorStatus( "gd1", "gdrive", "2026-07-10T12:00:00Z", "ok", 5 ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertTrue( json.get( "syncingEnabled" ).getAsBoolean() );
        assertTrue( json.get( "credentialStoreEnabled" ).getAsBoolean() );
        final JsonArray connectors = json.getAsJsonArray( "connectors" );
        assertEquals( 1, connectors.size() );
        final JsonObject entry = connectors.get( 0 ).getAsJsonObject();
        assertEquals( "gd1", entry.get( "id" ).getAsString() );
        assertEquals( "gdrive", entry.get( "type" ).getAsString() );
        assertEquals( "db", entry.get( "origin" ).getAsString() );
        assertTrue( entry.get( "enabled" ).getAsBoolean() );
        assertEquals( 6, entry.get( "syncIntervalHours" ).getAsInt() );
        assertEquals( "2026-07-10T12:00:00Z", entry.get( "lastRun" ).getAsString() );
        assertEquals( "ok", entry.get( "lastStatus" ).getAsString() );
        assertEquals( 5, entry.get( "pageCount" ).getAsInt() );
        assertEquals( 1, entry.getAsJsonArray( "secretsSet" ).size() );
        assertEquals( "client_secret", entry.getAsJsonArray( "secretsSet" ).get( 0 ).getAsString() );
    }

    @Test
    void listIncludesDisabledDbRow() throws Exception {
        when( req.getPathInfo() ).thenReturn( null );
        when( runtime.syncingEnabled() ).thenReturn( true );
        final ConnectorConfigService.ConnectorView view = new ConnectorConfigService.ConnectorView(
            "conf1", "confluence", "db", false, 12, new JsonObject(), null, null, null, List.of() );
        when( configService.list() ).thenReturn( List.of( view ) );
        // Disabled DB rows are never added to the registry — ConnectorRuntime.status()
        // throws IllegalArgumentException exactly like it does for a truly unknown id.
        when( runtime.status( "conf1" ) ).thenThrow( new IllegalArgumentException( "unknown connector: conf1" ) );
        when( syncState.items( "conf1" ) ).thenReturn( List.of(
            new SyncStateStore.SyncedItem( "https://example/1", "Page1", Instant.parse( "2026-07-01T00:00:00Z" ) ) ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        final JsonObject entry = json.getAsJsonArray( "connectors" ).get( 0 ).getAsJsonObject();
        assertFalse( entry.get( "enabled" ).getAsBoolean() );
        assertTrue( entry.get( "lastRun" ).isJsonNull() );
        assertTrue( entry.get( "lastStatus" ).isJsonNull() );
        assertEquals( 1, entry.get( "pageCount" ).getAsInt() );
    }

    @Test
    void listWorksWithoutConfigService() throws Exception {
        configService = null;
        when( req.getPathInfo() ).thenReturn( null );
        when( runtime.syncingEnabled() ).thenReturn( true );
        when( runtime.list() ).thenReturn( List.of(
            new ConnectorStatus( "fs1", "filesystem", "2026-07-10T12:00:00Z", "ok", 42 ) ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        final JsonArray connectors = json.getAsJsonArray( "connectors" );
        assertEquals( 1, connectors.size() );
        final JsonObject entry = connectors.get( 0 ).getAsJsonObject();
        assertEquals( "fs1", entry.get( "id" ).getAsString() );
        assertEquals( "filesystem", entry.get( "type" ).getAsString() );
        assertEquals( "properties", entry.get( "origin" ).getAsString() );
        assertEquals( "2026-07-10T12:00:00Z", entry.get( "lastRun" ).getAsString() );
        assertEquals( 42, entry.get( "pageCount" ).getAsInt() );
    }

    // -----------------------------------------------------------------------
    // GET /admin/connectors/{id}  (detail)
    // -----------------------------------------------------------------------

    @Test
    void detailReturnsConfigJson() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gd1" );
        final JsonObject cfg = new JsonObject();
        cfg.addProperty( "folder_ids", "abc" );
        final ConnectorConfigService.ConnectorView view = new ConnectorConfigService.ConnectorView(
            "gd1", "gdrive", "db", true, 6, cfg, "cluster1", "tag1,tag2", "GDrive/", List.of( "client_secret" ) );
        when( configService.get( "gd1" ) ).thenReturn( Optional.of( view ) );
        when( runtime.status( "gd1" ) ).thenReturn(
            new ConnectorStatus( "gd1", "gdrive", "2026-07-10T12:00:00Z", "ok", 5 ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertEquals( "gd1", json.get( "id" ).getAsString() );
        assertEquals( "cluster1", json.get( "cluster" ).getAsString() );
        assertEquals( "tag1,tag2", json.get( "defaultTags" ).getAsString() );
        assertEquals( "GDrive/", json.get( "pagePrefix" ).getAsString() );
        assertEquals( "abc", json.getAsJsonObject( "config" ).get( "folder_ids" ).getAsString() );
    }

    @Test
    void detailUnknownIs404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/nope" );
        when( configService.get( "nope" ) ).thenReturn( Optional.empty() );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    // -----------------------------------------------------------------------
    // GET /admin/connectors/{id}/runs
    // -----------------------------------------------------------------------

    @Test
    void runsReturnsHistoryNewestFirst() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/fs1/runs" );
        when( req.getParameter( "limit" ) ).thenReturn( null );
        final Instant started2 = Instant.parse( "2026-07-12T00:00:00Z" );
        final Instant finished2 = Instant.parse( "2026-07-12T00:05:00Z" );
        final Instant started1 = Instant.parse( "2026-07-11T00:00:00Z" );
        final Instant finished1 = Instant.parse( "2026-07-11T00:05:00Z" );
        when( runStore.list( "fs1", 20 ) ).thenReturn( List.of(
            new SyncRunRow( 2L, "fs1", "manual", started2, finished2, "ok", 1, 2, 3, 0, 0, null ),
            new SyncRunRow( 1L, "fs1", "scheduled", started1, finished1, "ok", 4, 5, 6, 0, 0, null ) ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        final JsonArray runs = json.getAsJsonArray( "runs" );
        assertEquals( 2, runs.size() );
        final JsonObject first = runs.get( 0 ).getAsJsonObject();
        assertEquals( 2, first.get( "runId" ).getAsInt() );
        assertEquals( "manual", first.get( "trigger" ).getAsString() );
        assertEquals( started2.toString(), first.get( "started" ).getAsString() );
        assertEquals( finished2.toString(), first.get( "finished" ).getAsString() );
        assertEquals( "ok", first.get( "status" ).getAsString() );
        assertEquals( 1, first.get( "created" ).getAsInt() );
        assertEquals( 2, first.get( "updated" ).getAsInt() );
        assertEquals( 3, first.get( "unchanged" ).getAsInt() );
    }

    // -----------------------------------------------------------------------
    // GET /admin/connectors/{id}/pages
    // -----------------------------------------------------------------------

    @Test
    void pagesListsSyncedItems() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/fs1/pages" );
        final Instant synced = Instant.parse( "2026-07-10T00:00:00Z" );
        when( syncState.items( "fs1" ) ).thenReturn( List.of(
            new SyncStateStore.SyncedItem( "file:///a.md", "PageA", synced ) ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        final JsonArray pages = json.getAsJsonArray( "pages" );
        assertEquals( 1, pages.size() );
        final JsonObject p = pages.get( 0 ).getAsJsonObject();
        assertEquals( "PageA", p.get( "pageName" ).getAsString() );
        assertEquals( "file:///a.md", p.get( "sourceUri" ).getAsString() );
        assertEquals( synced.toString(), p.get( "lastSynced" ).getAsString() );
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
