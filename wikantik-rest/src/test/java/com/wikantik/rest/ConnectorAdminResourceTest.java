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
import com.wikantik.WikiEngine;
import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.api.connectors.SyncStateStore;
import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditService;
import com.wikantik.connectors.SyncReport;
import com.wikantik.connectors.config.ConnectorConfigCodec;
import com.wikantik.connectors.runtime.ConnectorRegistry;
import com.wikantik.connectors.runtime.ConnectorRuntime;
import com.wikantik.connectors.runtime.ConnectorStatus;
import com.wikantik.connectors.runtime.ConnectorsDisabledException;
import com.wikantik.connectors.state.JdbcSyncRunStore;
import com.wikantik.connectors.state.SyncRunRow;
import com.wikantik.derived.ConnectorConfigService;
import com.wikantik.derived.ConnectorTestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

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
    private WikiEngine            wikiEngine;
    private AuditService          auditService;
    /** Canned return value for the {@code probeUnsaved} testing seam below — set per-test. */
    private ConnectorTestService.TestResult unsavedTestResult;
    /** Every argument list {@code probeUnsaved} was called with, in call order — lets tests assert
     *  the handler actually threads {@code type}/{@code config}/{@code credentials} through rather
     *  than the seam masking a dropped or misordered argument. */
    private final List< Object[] > probeUnsavedCalls = new java.util.ArrayList<>();

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
        // Bypasses ConnectorAssembler's real (network-making) connector construction — the
        // service layer itself (ConnectorTestService) is unit tested directly against a stub
        // connector; here we only need to prove the REST plumbing.
        @Override
        protected ConnectorTestService.TestResult probeUnsaved( final String id, final String type,
                final com.google.gson.JsonObject config, final Map< String, String > transientCredentials,
                final CredentialStore realStore ) {
            probeUnsavedCalls.add( new Object[] { id, type, config, transientCredentials, realStore } );
            return unsavedTestResult;
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

        // Mutation routes (create/update/delete/import) audit via getEngine() instanceof WikiEngine
        // exactly like AdminApiKeysResource — inject a mock WikiEngine via the protected setEngine()
        // accessor (RestServletBase, same package) so those tests can assert on the recorded entry.
        wikiEngine = mock( WikiEngine.class );
        auditService = mock( AuditService.class );
        when( wikiEngine.getAuditService() ).thenReturn( auditService );
        servlet.setEngine( wikiEngine );

        final Principal principal = mock( Principal.class );
        when( principal.getName() ).thenReturn( "admin" );
        when( req.getUserPrincipal() ).thenReturn( principal );
    }

    /** Builds a {@link ConnectorRegistry} containing exactly the given ids (each mapped to a stub
     *  {@link SourceConnector}), mirroring {@code mergeRuntimeStatus}'s registry-presence check —
     *  an id NOT passed here simulates a disabled/failed-to-build DB row. */
    private static ConnectorRegistry registryWith( final String... ids ) {
        final Map< String, SourceConnector > byId = new LinkedHashMap<>();
        for ( final String id : ids ) {
            byId.put( id, mock( SourceConnector.class ) );
        }
        return new ConnectorRegistry( byId, Map.of() );
    }

    /** Stubs {@code req.getReader()} to serve {@code json} as the POST/PUT body. */
    private void stubReaderBody( final String json ) throws Exception {
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( json ) ) );
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
        when( runtime.registry() ).thenReturn( registryWith( "fs1" ) );
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
        when( runtime.registry() ).thenReturn( registryWith( "gd1" ) );
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
        // Disabled DB rows are never added to the registry — registry().get() comes back empty,
        // exactly like it does for a truly unknown id, and runtime.status() is never called.
        when( runtime.registry() ).thenReturn( registryWith() );
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
        verify( runtime, never() ).status( "conf1" );
    }

    /** Race guard: the registry-presence check passes but a concurrent {@code ConnectorConfigService}
     *  rebuild swaps the connector out before {@code status()} runs — {@code status()} throws exactly
     *  like it does for a truly unknown id. This must still degrade to the no-status fallback (now via
     *  the logged catch block, not silently) rather than propagating. */
    @Test
    void listHandlesRegistryRaceBetweenPresenceCheckAndStatus() throws Exception {
        when( req.getPathInfo() ).thenReturn( null );
        when( runtime.syncingEnabled() ).thenReturn( true );
        final ConnectorConfigService.ConnectorView view = new ConnectorConfigService.ConnectorView(
            "race1", "webcrawler", "db", true, 6, new JsonObject(), null, null, null, List.of() );
        when( configService.list() ).thenReturn( List.of( view ) );
        when( runtime.registry() ).thenReturn( registryWith( "race1" ) );
        when( runtime.status( "race1" ) ).thenThrow( new IllegalArgumentException( "unknown connector: race1" ) );
        when( syncState.items( "race1" ) ).thenReturn( List.of() );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        final JsonObject entry = json.getAsJsonArray( "connectors" ).get( 0 ).getAsJsonObject();
        assertTrue( entry.get( "lastRun" ).isJsonNull() );
        assertTrue( entry.get( "lastStatus" ).isJsonNull() );
        assertEquals( 0, entry.get( "pageCount" ).getAsInt() );
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
        when( runtime.registry() ).thenReturn( registryWith( "gd1" ) );
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
    void syncDisabledIs409() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/fs1/sync" );
        when( runtime.syncNow( "fs1" ) ).thenThrow( new ConnectorsDisabledException( "connectors are disabled" ) );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_CONFLICT );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertEquals( "connectors are disabled", json.get( "message" ).getAsString() );
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

    // -----------------------------------------------------------------------
    // POST /admin/connectors  (create)
    // -----------------------------------------------------------------------

    @Test
    void createReturns201AndAudits() throws Exception {
        when( req.getPathInfo() ).thenReturn( null );
        stubReaderBody( "{\"id\":\"gh1\",\"type\":\"github\",\"config\":{\"repo\":\"jake/notes\"}}" );
        when( configService.create( eq( "gh1" ), eq( "github" ), any( JsonObject.class ),
                eq( true ), eq( 0 ), isNull(), isNull(), isNull() ) )
            .thenReturn( new ConnectorConfigCodec.Validation( Map.of() ) );
        final ConnectorConfigService.ConnectorView view = new ConnectorConfigService.ConnectorView(
            "gh1", "github", "db", true, 0, new JsonObject(), null, null, null, List.of() );
        when( configService.get( "gh1" ) ).thenReturn( Optional.of( view ) );
        when( runtime.registry() ).thenReturn( registryWith( "gh1" ) );
        when( runtime.status( "gh1" ) ).thenReturn( new ConnectorStatus( "gh1", "github", null, null, 0 ) );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_CREATED );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertEquals( "gh1", json.get( "id" ).getAsString() );
        assertEquals( "github", json.get( "type" ).getAsString() );

        final ArgumentCaptor< AuditEntry > captor = ArgumentCaptor.forClass( AuditEntry.class );
        verify( auditService ).record( captor.capture() );
        final AuditEntry entry = captor.getValue();
        assertEquals( "connector.create", entry.eventType() );
        assertEquals( AuditCategory.ADMIN, entry.category() );
        assertEquals( AuditOutcome.SUCCESS, entry.outcome() );
        assertEquals( "admin", entry.actorPrincipal() );
        assertEquals( "connector", entry.targetType() );
        assertEquals( "gh1", entry.targetId() );
    }

    @Test
    void createValidationErrorsAre422() throws Exception {
        when( req.getPathInfo() ).thenReturn( null );
        stubReaderBody( "{\"id\":\"gh1\",\"type\":\"github\",\"config\":{}}" );
        when( configService.create( any(), any(), any(), anyBoolean(), anyInt(), any(), any(), any() ) )
            .thenReturn( new ConnectorConfigCodec.Validation( Map.of( "repo", "repo is required" ) ) );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( 422 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertEquals( "repo is required", json.getAsJsonObject( "errors" ).get( "repo" ).getAsString() );
        verifyNoInteractions( auditService );
    }

    @Test
    void create_configServiceAbsent_returns503() throws Exception {
        configService = null;
        when( req.getPathInfo() ).thenReturn( null );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    @Test
    void create_malformedJson_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( null );
        stubReaderBody( "not json" );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        verifyNoInteractions( auditService );
    }

    // -----------------------------------------------------------------------
    // PUT /admin/connectors/{id}  (update)
    // -----------------------------------------------------------------------

    @Test
    void updatePropertiesOriginIs409() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/legacy1" );
        stubReaderBody( "{\"config\":{\"seeds\":[\"https://example.com\"]}}" );
        when( configService.update( eq( "legacy1" ), any( JsonObject.class ),
                eq( true ), eq( 0 ), isNull(), isNull(), isNull() ) )
            .thenThrow( new ConnectorConfigService.PropertiesOriginException( "legacy1" ) );

        servlet.doPut( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_CONFLICT );
        verifyNoInteractions( auditService );
    }

    @Test
    void updateUnknownIs404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/nope" );
        stubReaderBody( "{\"config\":{}}" );
        when( configService.update( eq( "nope" ), any( JsonObject.class ),
                eq( true ), eq( 0 ), isNull(), isNull(), isNull() ) )
            .thenThrow( new IllegalArgumentException( "unknown connector: nope" ) );

        servlet.doPut( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    @Test
    void updateValidationErrorsAre422() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gh1" );
        stubReaderBody( "{\"config\":{}}" );
        when( configService.update( any(), any(), anyBoolean(), anyInt(), any(), any(), any() ) )
            .thenReturn( new ConnectorConfigCodec.Validation( Map.of( "repo", "repo is required" ) ) );

        servlet.doPut( req, resp );

        verify( resp ).setStatus( 422 );
        verifyNoInteractions( auditService );
    }

    @Test
    void updateSucceedsAndAudits() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gh1" );
        stubReaderBody( "{\"config\":{\"repo\":\"jake/notes\"},\"enabled\":false,\"syncIntervalHours\":6}" );
        when( configService.update( eq( "gh1" ), any( JsonObject.class ),
                eq( false ), eq( 6 ), isNull(), isNull(), isNull() ) )
            .thenReturn( new ConnectorConfigCodec.Validation( Map.of() ) );
        final ConnectorConfigService.ConnectorView view = new ConnectorConfigService.ConnectorView(
            "gh1", "github", "db", false, 6, new JsonObject(), null, null, null, List.of() );
        when( configService.get( "gh1" ) ).thenReturn( Optional.of( view ) );
        when( runtime.registry() ).thenReturn( registryWith() );
        when( syncState.items( "gh1" ) ).thenReturn( List.of() );

        servlet.doPut( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_OK );
        final ArgumentCaptor< AuditEntry > captor = ArgumentCaptor.forClass( AuditEntry.class );
        verify( auditService ).record( captor.capture() );
        assertEquals( "connector.update", captor.getValue().eventType() );
        assertEquals( "gh1", captor.getValue().targetId() );
    }

    @Test
    void put_runtimeAbsent_returns503() throws Exception {
        runtime = null;
        when( req.getPathInfo() ).thenReturn( "/gh1" );

        servlet.doPut( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    // -----------------------------------------------------------------------
    // DELETE /admin/connectors/{id}
    // -----------------------------------------------------------------------

    @Test
    void deleteReturnsCounts() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gh1" );
        when( req.getParameter( "deletePages" ) ).thenReturn( "true" );
        when( configService.delete( "gh1", true ) )
            .thenReturn( new ConnectorConfigService.DeleteResult( 2, 0, 1 ) );

        servlet.doDelete( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_OK );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertEquals( 2, json.get( "pagesKept" ).getAsInt() );
        assertEquals( 0, json.get( "pagesDeleted" ).getAsInt() );
        assertEquals( 1, json.get( "credentialsDeleted" ).getAsInt() );

        final ArgumentCaptor< AuditEntry > captor = ArgumentCaptor.forClass( AuditEntry.class );
        verify( auditService ).record( captor.capture() );
        assertEquals( "connector.delete", captor.getValue().eventType() );
        assertEquals( "deletePages=true", captor.getValue().targetLabel() );
    }

    @Test
    void deleteDefaultsDeletePagesToFalse() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gh1" );
        when( req.getParameter( "deletePages" ) ).thenReturn( null );
        when( configService.delete( "gh1", false ) )
            .thenReturn( new ConnectorConfigService.DeleteResult( 3, 0, 0 ) );

        servlet.doDelete( req, resp );

        verify( configService ).delete( "gh1", false );
    }

    @Test
    void deleteUnknownIs404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/nope" );
        when( req.getParameter( "deletePages" ) ).thenReturn( "false" );
        when( configService.delete( "nope", false ) )
            .thenThrow( new IllegalArgumentException( "unknown connector: nope" ) );

        servlet.doDelete( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
        verifyNoInteractions( auditService );
    }

    @Test
    void deletePropertiesOriginIs409() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/legacy1" );
        when( req.getParameter( "deletePages" ) ).thenReturn( "false" );
        when( configService.delete( "legacy1", false ) )
            .thenThrow( new ConnectorConfigService.PropertiesOriginException( "legacy1" ) );

        servlet.doDelete( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_CONFLICT );
    }

    @Test
    void delete_configServiceAbsent_returns503() throws Exception {
        configService = null;
        when( req.getPathInfo() ).thenReturn( "/gh1" );

        servlet.doDelete( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    // -----------------------------------------------------------------------
    // POST /admin/connectors/{id}/import
    // -----------------------------------------------------------------------

    @Test
    void importRoundTrip() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gh-legacy/import" );
        final ConnectorConfigService.ConnectorView propsView = new ConnectorConfigService.ConnectorView(
            "gh-legacy", "github", "properties", true, 0, new JsonObject(), null, null, null, List.of() );
        final ConnectorConfigService.ConnectorView dbView = new ConnectorConfigService.ConnectorView(
            "gh-legacy", "github", "db", true, 0, new JsonObject(), null, null, null, List.of() );
        when( configService.get( "gh-legacy" ) )
            .thenReturn( Optional.of( propsView ) )    // origin check before import
            .thenReturn( Optional.of( dbView ) );      // respondDetail after a successful import

        final Properties props = new Properties();
        props.setProperty( "wikantik.connectors.github.gh-legacy.repo", "jake/notes" );
        when( wikiEngine.getWikiProperties() ).thenReturn( props );

        final ArgumentCaptor< JsonObject > configCaptor = ArgumentCaptor.forClass( JsonObject.class );
        when( configService.importFromProperties( eq( "gh-legacy" ), configCaptor.capture() ) )
            .thenReturn( new ConnectorConfigCodec.Validation( Map.of() ) );
        when( runtime.registry() ).thenReturn( registryWith() );
        when( syncState.items( "gh-legacy" ) ).thenReturn( List.of() );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_OK );
        verify( configService ).importFromProperties( eq( "gh-legacy" ), any( JsonObject.class ) );
        assertEquals( "jake/notes", configCaptor.getValue().get( "repo" ).getAsString() );

        final ArgumentCaptor< AuditEntry > auditCaptor = ArgumentCaptor.forClass( AuditEntry.class );
        verify( auditService ).record( auditCaptor.capture() );
        assertEquals( "connector.import", auditCaptor.getValue().eventType() );
        assertEquals( "gh-legacy", auditCaptor.getValue().targetId() );
    }

    @Test
    void importNotAPropertiesConnectorIs404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/nope/import" );
        when( configService.get( "nope" ) ).thenReturn( Optional.empty() );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
        verifyNoInteractions( auditService );
    }

    @Test
    void importAlreadyImportedIs409() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gh1/import" );
        final ConnectorConfigService.ConnectorView dbView = new ConnectorConfigService.ConnectorView(
            "gh1", "github", "db", true, 0, new JsonObject(), null, null, null, List.of() );
        when( configService.get( "gh1" ) ).thenReturn( Optional.of( dbView ) );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_CONFLICT );
        verify( configService, never() ).importFromProperties( any(), any() );
        verifyNoInteractions( auditService );
    }

    @Test
    void import_configServiceAbsent_returns503() throws Exception {
        configService = null;
        when( req.getPathInfo() ).thenReturn( "/gh1/import" );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    // -----------------------------------------------------------------------
    // POST /admin/connectors/test  (dry-run, unsaved payload)
    // -----------------------------------------------------------------------

    @Test
    void testUnsavedEndpointReturnsResult() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/test" );
        stubReaderBody( "{\"type\":\"webcrawler\",\"config\":{\"seeds\":[\"https://example.com\"]}}" );
        unsavedTestResult = new ConnectorTestService.TestResult(
            true, 2, List.of( "https://example.com/a", "https://example.com/b" ), true,
            "reachable — found 2 item(s) in a capped probe" );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertTrue( json.get( "ok" ).getAsBoolean() );
        assertEquals( 2, json.get( "found" ).getAsInt() );
        assertEquals( "reachable — found 2 item(s) in a capped probe", json.get( "message" ).getAsString() );
        verifyNoInteractions( auditService );
    }

    @Test
    void testUnsavedThreadsTypeConfigAndCredentialsToProbe() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/test" );
        stubReaderBody( "{\"type\":\"github\",\"config\":{\"repo\":\"jake/notes\"},"
            + "\"credentials\":{\"token\":\"transient-tok\",\"ignored\":{\"nested\":true}}}" );
        unsavedTestResult = new ConnectorTestService.TestResult( true, 0, List.of(), true, "ok" );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( 200 );
        assertEquals( 1, probeUnsavedCalls.size() );
        final Object[] call = probeUnsavedCalls.get( 0 );
        assertEquals( "github", call[ 1 ] );
        assertEquals( "jake/notes", ( ( JsonObject ) call[ 2 ] ).get( "repo" ).getAsString() );
        @SuppressWarnings( "unchecked" )
        final Map< String, String > credentials = ( Map< String, String > ) call[ 3 ];
        assertEquals( Map.of( "token", "transient-tok" ), credentials );   // non-primitive "ignored" entry dropped
        assertEquals( credStore, call[ 4 ] );
    }

    @Test
    void testUnsavedMalformedBodyIs400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/test" );
        stubReaderBody( "not json" );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    @Test
    void testUnsavedInvalidConfigIs422() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/test" );
        stubReaderBody( "{\"type\":\"webcrawler\",\"config\":{}}" );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( 422 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertTrue( json.getAsJsonObject( "errors" ).has( "seeds" ) );
    }

    // -----------------------------------------------------------------------
    // POST /admin/connectors/{id}/test  (dry-run, saved connector)
    // -----------------------------------------------------------------------

    @Test
    void testSavedUnknownIdIs404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/nope/test" );
        when( runtime.registry() ).thenReturn( registryWith() );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    @Test
    void testSavedEndpointReturnsResult() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gh1/test" );
        final SourceConnector connector = mock( SourceConnector.class );
        when( connector.poll( null ) ).thenReturn( new com.wikantik.api.connectors.SyncBatch(
            List.of(), List.of(), null, true ) );
        final ConnectorRegistry withGh1 = new ConnectorRegistry( Map.of( "gh1", connector ), Map.of() );
        when( runtime.registry() ).thenReturn( withGh1 );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertTrue( json.get( "ok" ).getAsBoolean() );
        assertEquals( 0, json.get( "found" ).getAsInt() );
        verifyNoInteractions( auditService );
    }

    // -----------------------------------------------------------------------
    // POST /admin/connectors  (create) — reserved id
    // -----------------------------------------------------------------------

    @Test
    void createRejectsReservedTestId() throws Exception {
        when( req.getPathInfo() ).thenReturn( null );
        stubReaderBody( "{\"id\":\"test\",\"type\":\"github\",\"config\":{\"repo\":\"jake/notes\"}}" );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( 422 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertEquals( "reserved id", json.getAsJsonObject( "errors" ).get( "connector_id" ).getAsString() );
        verify( configService, never() ).create( any(), any(), any(), anyBoolean(), anyInt(), any(), any(), any() );
        verifyNoInteractions( auditService );
    }
}
