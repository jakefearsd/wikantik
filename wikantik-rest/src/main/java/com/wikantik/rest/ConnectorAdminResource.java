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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.api.connectors.SyncStateStore;
import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditService;
import com.wikantik.connectors.SyncReport;
import com.wikantik.connectors.config.ConnectorConfigCodec;
import com.wikantik.connectors.runtime.ConnectorRuntime;
import com.wikantik.connectors.runtime.ConnectorStatus;
import com.wikantik.connectors.runtime.ConnectorsDisabledException;
import com.wikantik.connectors.state.JdbcSyncRunStore;
import com.wikantik.connectors.state.SyncRunRow;
import com.wikantik.derived.ConnectorConfigService;
import com.wikantik.derived.ConnectorTestService;
import com.wikantik.derived.ConnectorWiringHelper;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Admin endpoint for the source-connector sync stack ({@code wikantik-connectors}).
 *
 * <ul>
 *   <li>{@code GET  /admin/connectors} — lists every connector, merging
 *       {@link ConnectorConfigService#list()} (id/type/origin/enabled/interval/secretsSet) with
 *       {@link ConnectorRuntime} status (lastRun/lastStatus/pageCount). Falls back to
 *       {@link ConnectorRuntime#list()} alone (origin {@code "properties"}) when no
 *       {@link ConnectorConfigService} is wired.</li>
 *   <li>{@code GET  /admin/connectors/{id}} — the same merged fields plus the connector's
 *       {@code config}/{@code cluster}/{@code defaultTags}/{@code pagePrefix}. {@code 404} for an
 *       unknown id.</li>
 *   <li>{@code GET  /admin/connectors/{id}/runs?limit=20} — recent {@link SyncRunRow} history for
 *       connector {@code id}, newest first.</li>
 *   <li>{@code GET  /admin/connectors/{id}/pages} — the connector's synced items
 *       ({@link SyncStateStore.SyncedItem}).</li>
 *   <li>{@code GET  /admin/connectors/{id}/status} — returns the {@link ConnectorStatus} for connector {@code id}.</li>
 *   <li>{@code POST /admin/connectors/{id}/sync} — triggers an immediate sync for connector {@code id},
 *       returning the resulting {@link SyncReport}. {@code 409} when syncing is disabled
 *       ({@link ConnectorsDisabledException}) or already in progress.</li>
 *   <li>{@code POST /admin/connectors} — creates a DB-origin connector via
 *       {@link ConnectorConfigService#create}. {@code 201} + detail payload on success, {@code 422}
 *       {@code { "errors": {...} } } on field-keyed validation failure.</li>
 *   <li>{@code PUT  /admin/connectors/{id}} — updates a DB-origin connector via
 *       {@link ConnectorConfigService#update}. {@code 200} + detail payload; {@code 404} unknown id;
 *       {@code 409} for a properties-origin id that has never been imported.</li>
 *   <li>{@code DELETE /admin/connectors/{id}?deletePages=true|false} (default {@code false}) —
 *       deletes a DB-origin connector via {@link ConnectorConfigService#delete}, returning
 *       {@code { pagesKept, pagesDeleted, credentialsDeleted } }. {@code 404}/{@code 409} as above.</li>
 *   <li>{@code POST /admin/connectors/{id}/import} — copies a properties-defined connector into the
 *       DB via {@link ConnectorConfigService#importFromProperties}, reconstructing its config JSON
 *       from the startup properties. {@code 200} + detail payload; {@code 404} when {@code id} is not
 *       a live properties connector; {@code 409} when it is already imported (a DB row exists).</li>
 *   <li>{@code POST /admin/connectors/test} — dry-run probe of an <em>unsaved</em> {@code {type,
 *       config, credentials?}} payload via {@link ConnectorTestService#testUnsaved} (the wizard's
 *       Test step). {@code 400} malformed body; {@code 422} field-keyed validation errors (same
 *       shape as create). Because this path segment shadows a connector literally named {@code
 *       "test"}, that id is reserved (see {@link #RESERVED_CONNECTOR_IDS}) so the ambiguity can
 *       never actually arise.</li>
 *   <li>{@code POST /admin/connectors/{id}/test} — dry-run probe of a <em>saved</em> connector's
 *       live config via {@link ConnectorTestService#testSaved}. {@code 404} unknown id.</li>
 * </ul>
 *
 * <p>Neither test route audits (read-only, no state change) and neither logs or echoes credential
 * values (see {@link ConnectorTestService}'s class javadoc for the secret-hygiene contract).
 *
 * <p>All endpoints are protected by {@code AdminAuthFilter} (the {@code /admin/*} filter mapping).
 * {@link ConnectorRuntime} is wired unconditionally at engine startup, so a {@code 503 Service
 * Unavailable} here means the engine is not yet a fully-initialized {@code WikiEngine} (very early
 * startup), not that connectors are disabled. An unknown connector id responds {@code 404}. The
 * mutation routes above audit their outcome (category {@code ADMIN}, event types
 * {@code connector.create}/{@code .update}/{@code .delete}/{@code .import}) mirroring
 * {@link AdminApiKeysResource}'s idiom — success only, wrapped in try/catch so a broken
 * {@link AuditService} never fails the request itself.
 */
public class ConnectorAdminResource extends RestServletBase {

    private static final long   serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( ConnectorAdminResource.class );

    /** 503 message for every mutation route when {@link ConnectorConfigService} isn't wired. */
    private static final String CONFIG_SERVICE_UNAVAILABLE = "Connector configuration service unavailable";

    /** Connector ids a create request may never use — {@code "test"} would otherwise be shadowed
     *  by the {@code POST /admin/connectors/test} dry-run route (a single-segment path always
     *  routes there, never to a saved connector named "test"; see {@link #doPost}). Rejecting the
     *  id here at creation time means that routing precedence never actually has to matter. */
    private static final Set< String > RESERVED_CONNECTOR_IDS = Set.of( "test" );

    /** Throwaway assembly id for {@code POST /admin/connectors/test} (unsaved payload) — never
     *  persisted; see {@link ConnectorTestService#testUnsaved}'s javadoc for why the literal value
     *  doesn't matter. */
    private static final String TEST_PROBE_CONNECTOR_ID = "connector-test-probe";

    /**
     * The list/detail/runs/pages payloads promise explicit {@code null} fields (e.g. {@code
     * lastRun}/{@code lastStatus} for a disabled connector, {@code finished}/{@code error} for a
     * running sync) as a stable contract for the admin UI — the parent's default GSON silently
     * drops null map values, so this mirrors the {@code serializeNulls()} override used by
     * {@code PageForAgentResource}/{@code BundleResource}/{@code BriefingResource}.
     */
    private static final Gson NULL_SAFE_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter( java.util.Date.class, UTC_ISO_DATE_SERIALIZER )
            .serializeNulls()
            .create();

    /** Like {@link #sendJson} but serializes explicit {@code null} map values instead of dropping
     *  them — see {@link #NULL_SAFE_GSON}. */
    private void sendJsonWithNulls( final HttpServletResponse response, final Object payload ) throws IOException {
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );
        response.getWriter().write( NULL_SAFE_GSON.toJson( payload ) );
    }

    // -------------------------------------------------------------------------
    // GET
    // -------------------------------------------------------------------------

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final ConnectorRuntime runtime = resolveRuntime();
        if ( runtime == null ) {
            sendServiceUnavailable( response );
            return;
        }

        final String path = extractPathParam( request );
        if ( path == null || path.isEmpty() ) {
            handleList( runtime, response );
            return;
        }

        final String[] segments = path.split( "/" );
        if ( segments.length == 1 ) {
            handleDetail( runtime, segments[ 0 ], response );
        } else if ( segments.length == 2 && "status".equals( segments[ 1 ] ) ) {
            handleStatus( runtime, segments[ 0 ], response );
        } else if ( segments.length == 2 && "runs".equals( segments[ 1 ] ) ) {
            handleRuns( segments[ 0 ], request, response );
        } else if ( segments.length == 2 && "pages".equals( segments[ 1 ] ) ) {
            handlePages( segments[ 0 ], response );
        } else {
            sendNotFound( response, "Unknown connector endpoint: " + path );
        }
    }

    private void handleList( final ConnectorRuntime runtime, final HttpServletResponse response ) throws IOException {
        final ConnectorConfigService configService = resolveConfigService();
        final List< Map< String, Object > > connectors = new ArrayList<>();
        if ( configService != null ) {
            for ( final ConnectorConfigService.ConnectorView view : configService.list() ) {
                connectors.add( summarize( runtime, view ) );
            }
        } else {
            // Legacy fallback (no ConnectorConfigService wired): every connector the runtime
            // knows about originates from wikantik-custom.properties.
            for ( final ConnectorStatus status : runtime.list() ) {
                connectors.add( summarizeFromStatus( status ) );
            }
        }

        final CredentialStore credStore = resolveCredentialStore();
        final Map< String, Object > payload = new LinkedHashMap<>();
        payload.put( "syncingEnabled", runtime.syncingEnabled() );
        payload.put( "credentialStoreEnabled", credStore != null && credStore.enabled() );
        payload.put( "connectors", connectors );

        response.setStatus( HttpServletResponse.SC_OK );
        sendJsonWithNulls( response, payload );
    }

    private void handleDetail( final ConnectorRuntime runtime, final String connectorId,
                                final HttpServletResponse response ) throws IOException {
        final ConnectorConfigService configService = resolveConfigService();
        if ( configService != null ) {
            final Optional< ConnectorConfigService.ConnectorView > view = configService.get( connectorId );
            if ( view.isEmpty() ) {
                sendNotFound( response, "Unknown connector: " + connectorId );
                return;
            }
            response.setStatus( HttpServletResponse.SC_OK );
            sendJsonWithNulls( response, detailPayload( runtime, view.get() ) );
            return;
        }

        // Legacy fallback (no ConnectorConfigService wired): resolve straight from the runtime;
        // an id the registry doesn't know about is unknown (404), same as every other route here.
        try {
            final ConnectorStatus status = runtime.status( connectorId );
            final Map< String, Object > payload = summarizeFromStatus( status );
            payload.put( "config", new com.google.gson.JsonObject() );
            payload.put( "cluster", null );
            payload.put( "defaultTags", null );
            payload.put( "pagePrefix", null );
            response.setStatus( HttpServletResponse.SC_OK );
            sendJsonWithNulls( response, payload );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "ConnectorAdminResource: detail requested for unknown connector '{}': {}",
                connectorId, e.getMessage() );
            sendNotFound( response, e.getMessage() );
        }
    }

    private void handleRuns( final String connectorId, final HttpServletRequest request,
                              final HttpServletResponse response ) throws IOException {
        final JdbcSyncRunStore runStore = resolveRunStore();
        if ( runStore == null ) {
            sendServiceUnavailable( response );
            return;
        }
        final int limit = parseIntParam( request, "limit", 20 );
        final List< Map< String, Object > > runs = new ArrayList<>();
        for ( final SyncRunRow row : runStore.list( connectorId, limit ) ) {
            final Map< String, Object > r = new LinkedHashMap<>();
            r.put( "runId", row.runId() );
            r.put( "trigger", row.trigger() );
            r.put( "started", row.started() != null ? row.started().toString() : null );
            r.put( "finished", row.finished() != null ? row.finished().toString() : null );
            r.put( "status", row.status() );
            r.put( "created", row.created() );
            r.put( "updated", row.updated() );
            r.put( "unchanged", row.unchanged() );
            r.put( "deleted", row.deleted() );
            r.put( "failed", row.failed() );
            r.put( "error", row.error() );
            runs.add( r );
        }
        response.setStatus( HttpServletResponse.SC_OK );
        sendJsonWithNulls( response, Map.of( "runs", runs ) );
    }

    private void handlePages( final String connectorId, final HttpServletResponse response ) throws IOException {
        final SyncStateStore syncState = resolveSyncState();
        if ( syncState == null ) {
            sendServiceUnavailable( response );
            return;
        }
        final List< Map< String, Object > > pages = new ArrayList<>();
        for ( final SyncStateStore.SyncedItem item : syncState.items( connectorId ) ) {
            final Map< String, Object > p = new LinkedHashMap<>();
            p.put( "pageName", item.pageName() );
            p.put( "sourceUri", item.sourceUri() );
            p.put( "lastSynced", item.lastSynced() != null ? item.lastSynced().toString() : null );
            pages.add( p );
        }
        response.setStatus( HttpServletResponse.SC_OK );
        sendJsonWithNulls( response, Map.of( "pages", pages ) );
    }

    private void handleStatus( final ConnectorRuntime runtime, final String connectorId,
                                final HttpServletResponse response ) throws IOException {
        try {
            final ConnectorStatus status = runtime.status( connectorId );
            response.setStatus( HttpServletResponse.SC_OK );
            sendJson( response, status );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "ConnectorAdminResource: status requested for unknown connector '{}': {}",
                connectorId, e.getMessage() );
            sendNotFound( response, e.getMessage() );
        }
    }

    // -------------------------------------------------------------------------
    // List/detail merge helpers
    // -------------------------------------------------------------------------

    /** Builds the common id/type/origin/enabled/syncIntervalHours/lastRun/lastStatus/pageCount/
     *  secretsSet fields shared by the list and detail payloads. */
    private Map< String, Object > summarize( final ConnectorRuntime runtime,
                                               final ConnectorConfigService.ConnectorView view ) {
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "id", view.id() );
        out.put( "type", view.type() );
        out.put( "origin", view.origin() );
        out.put( "enabled", view.enabled() );
        out.put( "syncIntervalHours", view.syncIntervalHours() );
        mergeRuntimeStatus( runtime, view.id(), out );
        out.put( "secretsSet", view.secretsSet() );
        return out;
    }

    /** {@link #summarize} plus {@code config}/{@code cluster}/{@code defaultTags}/{@code pagePrefix}
     *  — the full single-connector detail payload shared by {@code GET .../{id}} and every
     *  mutation route's success response. */
    private Map< String, Object > detailPayload( final ConnectorRuntime runtime,
                                                   final ConnectorConfigService.ConnectorView view ) {
        final Map< String, Object > payload = summarize( runtime, view );
        payload.put( "config", view.config() );
        payload.put( "cluster", view.cluster() );
        payload.put( "defaultTags", view.defaultTags() );
        payload.put( "pagePrefix", view.pagePrefix() );
        return payload;
    }

    /** Legacy (no {@link ConnectorConfigService}) summary built straight from a
     *  {@link ConnectorStatus} — every such connector is properties-origin and enabled (it came
     *  from {@link ConnectorRuntime#list()}, which only ever iterates the live registry). */
    private Map< String, Object > summarizeFromStatus( final ConnectorStatus status ) {
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "id", status.connectorId() );
        out.put( "type", status.connectorType() );
        out.put( "origin", "properties" );
        out.put( "enabled", true );
        out.put( "syncIntervalHours", 0 );
        out.put( "lastRun", status.lastRun() );
        out.put( "lastStatus", status.lastStatus() );
        out.put( "pageCount", status.syncedItemCount() );
        out.put( "secretsSet", List.of() );
        return out;
    }

    /** Fills {@code lastRun}/{@code lastStatus}/{@code pageCount} from {@link ConnectorRuntime#status}
     *  when the connector is live in the registry; a disabled DB row (or one that failed to build)
     *  isn't in the registry — checked explicitly via {@link ConnectorRuntime#registry()} up front
     *  (steady-state for every disabled connector, so it must not log), falling back to
     *  {@link SyncStateStore#items} for the page count. The {@link IllegalArgumentException} catch
     *  below is kept only as a genuine race guard (a concurrent {@code ConnectorConfigService}
     *  rebuild swaps the registry out between the presence check and the {@code status()} call) —
     *  that path is real and unexpected, so it is logged. */
    private void mergeRuntimeStatus( final ConnectorRuntime runtime, final String connectorId,
                                      final Map< String, Object > out ) {
        if ( runtime.registry().get( connectorId ).isEmpty() ) {
            applyNoStatusFallback( connectorId, out );
            return;
        }
        try {
            final ConnectorStatus status = runtime.status( connectorId );
            out.put( "lastRun", status.lastRun() );
            out.put( "lastStatus", status.lastStatus() );
            out.put( "pageCount", status.syncedItemCount() );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "connector '{}' vanished from the registry between the presence check and status() "
                + "— likely a concurrent config rebuild: {}", connectorId, e.getMessage() );
            applyNoStatusFallback( connectorId, out );
        }
    }

    /** {@code lastRun}/{@code lastStatus} are unknown for a connector that isn't live in the
     *  registry; {@code pageCount} instead comes from {@link SyncStateStore#items}. */
    private void applyNoStatusFallback( final String connectorId, final Map< String, Object > out ) {
        out.put( "lastRun", null );
        out.put( "lastStatus", null );
        final SyncStateStore syncState = resolveSyncState();
        out.put( "pageCount", syncState != null ? syncState.items( connectorId ).size() : 0 );
    }

    // -------------------------------------------------------------------------
    // POST
    // -------------------------------------------------------------------------

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final ConnectorRuntime runtime = resolveRuntime();
        if ( runtime == null ) {
            sendServiceUnavailable( response );
            return;
        }

        final String path = extractPathParam( request );
        if ( path == null || path.isEmpty() ) {
            handleCreate( request, response );
            return;
        }

        final String[] segments = path.split( "/" );
        if ( segments.length == 1 && "test".equals( segments[ 0 ] ) ) {
            // Path wins over id: POST /admin/connectors/test is ALWAYS the unsaved-payload dry-run
            // route (single segment), never a saved-connector action for an id literally "test" —
            // that id is rejected at creation time (RESERVED_CONNECTOR_IDS), so this can never bite.
            handleTestUnsaved( request, response );
        } else if ( segments.length == 2 && "sync".equals( segments[ 1 ] ) ) {
            handleSync( runtime, segments[ 0 ], response );
        } else if ( segments.length == 2 && "import".equals( segments[ 1 ] ) ) {
            handleImport( request, segments[ 0 ], response );
        } else if ( segments.length == 2 && "test".equals( segments[ 1 ] ) ) {
            handleTestSaved( runtime, segments[ 0 ], response );
        } else {
            sendNotFound( response, "Unknown connector endpoint: " + path );
        }
    }

    private void handleSync( final ConnectorRuntime runtime, final String connectorId,
                              final HttpServletResponse response ) throws IOException {
        try {
            LOG.info( "ConnectorAdminResource: sync requested for connector '{}'", connectorId );
            final SyncReport report = runtime.syncNow( connectorId );
            LOG.info( "ConnectorAdminResource: sync complete for connector '{}': {}", connectorId, report );
            response.setStatus( HttpServletResponse.SC_OK );
            sendJson( response, report );
        } catch ( final com.wikantik.connectors.runtime.SyncInProgressException e ) {
            LOG.info( "ConnectorAdminResource: sync rejected for connector '{}': {}", connectorId, e.getMessage() );
            sendError( response, HttpServletResponse.SC_CONFLICT, e.getMessage() );
        } catch ( final ConnectorsDisabledException e ) {
            LOG.info( "ConnectorAdminResource: sync rejected for connector '{}': connectors disabled: {}",
                connectorId, e.getMessage() );
            sendError( response, HttpServletResponse.SC_CONFLICT, e.getMessage() );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "ConnectorAdminResource: sync requested for unknown connector '{}': {}",
                connectorId, e.getMessage() );
            sendNotFound( response, e.getMessage() );
        }
    }

    /** {@code POST /admin/connectors} — creates a DB-origin connector row. See the class javadoc. */
    private void handleCreate( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final ConnectorConfigService configService = resolveConfigService();
        if ( configService == null ) {
            sendServiceUnavailable( response, CONFIG_SERVICE_UNAVAILABLE );
            return;
        }
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;   // parseJsonBody already sent 400

        final String id = getJsonString( body, "id" );
        if ( id != null && RESERVED_CONNECTOR_IDS.contains( id ) ) {
            sendValidationErrors( response, new ConnectorConfigCodec.Validation( Map.of( "connector_id", "reserved id" ) ) );
            return;
        }
        final String type = getJsonString( body, "type" );
        final JsonObject config = bodyConfig( body );
        final boolean enabled = getJsonBoolean( body, "enabled", true );
        final int syncIntervalHours = getJsonInt( body, "syncIntervalHours", 0 );
        final String cluster = getJsonString( body, "cluster" );
        final String defaultTags = getJsonString( body, "defaultTags" );
        final String pagePrefix = getJsonString( body, "pagePrefix" );

        final ConnectorConfigCodec.Validation v = configService.create(
            id, type, config, enabled, syncIntervalHours, cluster, defaultTags, pagePrefix );
        if ( !v.ok() ) {
            sendValidationErrors( response, v );
            return;
        }

        LOG.info( "ConnectorAdminResource: created connector '{}' (type={})", id, type );
        recordAudit( "connector.create", currentLogin( request ), id, null );
        respondDetail( configService, id, response, HttpServletResponse.SC_CREATED );
    }

    /** {@code PUT /admin/connectors/{id}} — updates a DB-origin connector row. See the class javadoc. */
    private void handleUpdate( final HttpServletRequest request, final String connectorId,
                                final HttpServletResponse response ) throws IOException {
        final ConnectorConfigService configService = resolveConfigService();
        if ( configService == null ) {
            sendServiceUnavailable( response, CONFIG_SERVICE_UNAVAILABLE );
            return;
        }
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        final JsonObject config = bodyConfig( body );
        final boolean enabled = getJsonBoolean( body, "enabled", true );
        final int syncIntervalHours = getJsonInt( body, "syncIntervalHours", 0 );
        final String cluster = getJsonString( body, "cluster" );
        final String defaultTags = getJsonString( body, "defaultTags" );
        final String pagePrefix = getJsonString( body, "pagePrefix" );

        final ConnectorConfigCodec.Validation v;
        try {
            v = configService.update( connectorId, config, enabled, syncIntervalHours, cluster, defaultTags, pagePrefix );
        } catch ( final ConnectorConfigService.PropertiesOriginException e ) {
            sendError( response, HttpServletResponse.SC_CONFLICT, e.getMessage() );
            return;
        } catch ( final IllegalArgumentException e ) {
            sendNotFound( response, e.getMessage() );
            return;
        }
        if ( !v.ok() ) {
            sendValidationErrors( response, v );
            return;
        }

        LOG.info( "ConnectorAdminResource: updated connector '{}'", connectorId );
        recordAudit( "connector.update", currentLogin( request ), connectorId, null );
        respondDetail( configService, connectorId, response, HttpServletResponse.SC_OK );
    }

    /** {@code DELETE /admin/connectors/{id}?deletePages=true|false} (default {@code false}). See the
     *  class javadoc. */
    private void handleDelete( final HttpServletRequest request, final String connectorId,
                                final HttpServletResponse response ) throws IOException {
        final ConnectorConfigService configService = resolveConfigService();
        if ( configService == null ) {
            sendServiceUnavailable( response, CONFIG_SERVICE_UNAVAILABLE );
            return;
        }
        final boolean deletePages = Boolean.parseBoolean( request.getParameter( "deletePages" ) );

        final ConnectorConfigService.DeleteResult result;
        try {
            result = configService.delete( connectorId, deletePages );
        } catch ( final ConnectorConfigService.PropertiesOriginException e ) {
            sendError( response, HttpServletResponse.SC_CONFLICT, e.getMessage() );
            return;
        } catch ( final IllegalArgumentException e ) {
            sendNotFound( response, e.getMessage() );
            return;
        }

        LOG.info( "ConnectorAdminResource: deleted connector '{}' (deletePages={})", connectorId, deletePages );
        recordAudit( "connector.delete", currentLogin( request ), connectorId, "deletePages=" + deletePages );

        response.setStatus( HttpServletResponse.SC_OK );
        sendJson( response, Map.of(
            "pagesKept", result.pagesKept(),
            "pagesDeleted", result.pagesDeleted(),
            "credentialsDeleted", result.credentialsDeleted() ) );
    }

    /** {@code POST /admin/connectors/{id}/import} — copies a properties-defined connector into the
     *  DB. {@code id} must resolve to a live {@code "properties"}-origin {@link
     *  ConnectorConfigService.ConnectorView} (404 otherwise — either truly unknown or a properties
     *  connector that failed to build) and must not already be a {@code "db"}-origin row (409). The
     *  config JSON is reconstructed from the current startup properties via {@link
     *  #reconstructPropertiesConfig} rather than trusting a caller-supplied body — the whole point of
     *  import is to capture what is actually configured. */
    private void handleImport( final HttpServletRequest request, final String connectorId,
                                final HttpServletResponse response ) throws IOException {
        final ConnectorConfigService configService = resolveConfigService();
        if ( configService == null ) {
            sendServiceUnavailable( response, CONFIG_SERVICE_UNAVAILABLE );
            return;
        }

        final Optional< ConnectorConfigService.ConnectorView > view = configService.get( connectorId );
        if ( view.isEmpty() ) {
            sendNotFound( response, "not a properties-defined connector: " + connectorId );
            return;
        }
        if ( "db".equals( view.get().origin() ) ) {
            sendError( response, HttpServletResponse.SC_CONFLICT,
                "connector '" + connectorId + "' is already imported" );
            return;
        }

        final String type = view.get().type();
        final Properties props = getEngine().getWikiProperties();
        final JsonObject config = reconstructPropertiesConfig( type, connectorId, props );

        final ConnectorConfigCodec.Validation v = configService.importFromProperties( connectorId, config );
        if ( !v.ok() ) {
            sendValidationErrors( response, v );
            return;
        }

        LOG.info( "ConnectorAdminResource: imported connector '{}' (type={})", connectorId, type );
        recordAudit( "connector.import", currentLogin( request ), connectorId, null );
        respondDetail( configService, connectorId, response, HttpServletResponse.SC_OK );
    }

    /** {@code POST /admin/connectors/test} — dry-run probe of an <em>unsaved</em> {@code {type,
     *  config, credentials?}} payload (the wizard's Test step). Validation mirrors {@link
     *  #handleCreate}: an unrecognized/blank {@code type} (including {@code filesystem}, D9) never
     *  reaches {@link ConnectorConfigCodec#validate} (which NPEs on a {@code null} type), and every
     *  field-keyed error is {@code 422} before any connector is assembled. No audit — read-only, no
     *  state change. */
    private void handleTestUnsaved( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;   // parseJsonBody already sent 400

        final String type = getJsonString( body, "type" );
        final JsonObject config = bodyConfig( body );

        final Map< String, String > errors = new LinkedHashMap<>();
        if ( type == null || type.isBlank() || !ConnectorConfigCodec.UI_TYPES.contains( type ) ) {
            errors.put( "connector_type", "unknown connector type: " + type );
        } else {
            errors.putAll( ConnectorConfigCodec.validate( type, config ).errors() );
        }
        if ( !errors.isEmpty() ) {
            sendValidationErrors( response, new ConnectorConfigCodec.Validation( errors ) );
            return;
        }

        final Map< String, String > transientCredentials = extractCredentials( body );
        final ConnectorTestService.TestResult result = probeUnsaved(
            TEST_PROBE_CONNECTOR_ID, type, config, transientCredentials, resolveCredentialStore() );
        response.setStatus( HttpServletResponse.SC_OK );
        sendJson( response, result );
    }

    /** {@code POST /admin/connectors/{id}/test} — dry-run probe of a <em>saved</em> connector at
     *  its live (uncapped) config; {@code 404} for an id the registry doesn't know about. No audit
     *  — read-only, no state change. */
    private void handleTestSaved( final ConnectorRuntime runtime, final String connectorId,
                                   final HttpServletResponse response ) throws IOException {
        final Optional< SourceConnector > connector = runtime.registry().get( connectorId );
        if ( connector.isEmpty() ) {
            sendNotFound( response, "Unknown connector: " + connectorId );
            return;
        }
        final ConnectorTestService.TestResult result = ConnectorTestService.testSaved( connector.get() );
        response.setStatus( HttpServletResponse.SC_OK );
        sendJson( response, result );
    }

    /** {@code credentials} from the request body as a flat name→value map, or an empty map when
     *  absent/not-an-object. Values that aren't JSON primitives are skipped rather than rejecting
     *  the whole request — mirrors {@link #bodyConfig}'s shape-proof idiom. */
    private Map< String, String > extractCredentials( final JsonObject body ) {
        if ( !body.has( "credentials" ) || !body.get( "credentials" ).isJsonObject() ) return Map.of();
        final JsonObject credentials = body.getAsJsonObject( "credentials" );
        final Map< String, String > out = new LinkedHashMap<>();
        for ( final String name : credentials.keySet() ) {
            final String value = getJsonString( credentials, name );
            if ( value != null ) out.put( name, value );
        }
        return Map.copyOf( out );
    }

    /** Testing seam over {@link ConnectorTestService#testUnsaved} — protected so tests can inject
     *  a canned {@link ConnectorTestService.TestResult} without exercising {@link
     *  com.wikantik.derived.ConnectorAssembler}'s real (network-making) connector construction,
     *  mirroring the {@code resolveXxx} pattern below. */
    protected ConnectorTestService.TestResult probeUnsaved( final String id, final String type, final JsonObject config,
            final Map< String, String > transientCredentials, final CredentialStore realStore ) {
        return ConnectorTestService.testUnsaved( id, type, config, transientCredentials, realStore );
    }

    /** Rebuilds the typed config for {@code type}/{@code connectorId} straight from {@code props} —
     *  the same per-id parsers {@code ConnectorWiringHelper} uses at startup — then serializes it
     *  back to the admin-UI config JSON via {@link ConnectorConfigCodec#toJson}. {@code type} not
     *  one of the six admin-UI-creatable types (i.e. {@code filesystem}, D9) or the id no longer
     *  parsing from the live properties both degrade to an empty object rather than throwing —
     *  {@link ConnectorConfigService#importFromProperties} then rejects it with a field-keyed
     *  validation error (422), never a 500. */
    private JsonObject reconstructPropertiesConfig( final String type, final String connectorId, final Properties props ) {
        final Object typed = switch ( type ) {
            case "webcrawler" -> ConnectorWiringHelper.webcrawlerConfigs( props ).get( connectorId );
            case "sitemap" -> ConnectorWiringHelper.sitemapConfigs( props ).get( connectorId );
            case "feed" -> ConnectorWiringHelper.feedConfigs( props ).get( connectorId );
            case "gdrive" -> ConnectorWiringHelper.driveConfigs( props ).get( connectorId );
            case "github" -> ConnectorWiringHelper.githubConfigs( props ).get( connectorId );
            case "confluence" -> ConnectorWiringHelper.confluenceConfigs( props ).get( connectorId );
            default -> null;
        };
        return typed != null ? ConnectorConfigCodec.toJson( type, typed ) : new JsonObject();
    }

    /** {@code config} from the request body, or an empty object when absent/not-an-object — every
     *  {@link ConnectorConfigCodec#validate} call is shape-proof against an empty config (it simply
     *  reports every required field as missing), so this never needs to reject the shape itself. */
    private JsonObject bodyConfig( final JsonObject body ) {
        return body.has( "config" ) && body.get( "config" ).isJsonObject() ? body.getAsJsonObject( "config" ) : new JsonObject();
    }

    /** {@code 422 { "errors": { field: message } } } for a failed {@link ConnectorConfigCodec.Validation}. */
    private void sendValidationErrors( final HttpServletResponse response, final ConnectorConfigCodec.Validation v )
            throws IOException {
        response.setStatus( 422 );
        sendJson( response, Map.of( "errors", v.errors() ) );
    }

    /** Re-fetches {@code connectorId} and sends its {@link #detailPayload} at {@code status} — the
     *  shared success response for create/update/import. */
    private void respondDetail( final ConnectorConfigService configService, final String connectorId,
                                 final HttpServletResponse response, final int status ) throws IOException {
        final Optional< ConnectorConfigService.ConnectorView > view = configService.get( connectorId );
        if ( view.isEmpty() ) {
            // Should be unreachable right after a successful mutation — fail safe rather than NPE.
            LOG.warn( "connector '{}': vanished immediately after a successful mutation", connectorId );
            sendNotFound( response, "Unknown connector: " + connectorId );
            return;
        }
        response.setStatus( status );
        sendJsonWithNulls( response, detailPayload( resolveRuntime(), view.get() ) );
    }

    /** Records a {@code category=ADMIN} audit entry mirroring {@link AdminApiKeysResource}'s idiom
     *  exactly: {@code getEngine() instanceof WikiEngine} to reach {@link
     *  com.wikantik.WikiEngine#getAuditService()}, wrapped in try/catch so a broken audit backend
     *  never fails the mutation itself (mutation already succeeded by the time this runs). Only
     *  called on success paths — a validation/404/409 rejection is not an auditable connector
     *  mutation. */
    private void recordAudit( final String eventType, final String actorLogin, final String connectorId,
                               final String targetLabel ) {
        try {
            final AuditService audit = getEngine() instanceof com.wikantik.WikiEngine wikiEngine
                    ? wikiEngine.getAuditService() : null;
            if ( audit != null ) {
                final AuditEntry.Builder entry = AuditEntry.builder()
                        .eventTime( Instant.now() )
                        .category( AuditCategory.ADMIN )
                        .eventType( eventType )
                        .outcome( AuditOutcome.SUCCESS )
                        .actorPrincipal( actorLogin )
                        .actorType( "user" )
                        .targetType( "connector" )
                        .targetId( connectorId );
                if ( targetLabel != null ) entry.targetLabel( targetLabel );
                audit.record( entry.build() );
            }
        } catch ( final Exception auditEx ) {
            LOG.warn( "Failed to record audit entry for connector {} '{}': {}",
                eventType, connectorId, auditEx.getMessage(), auditEx );
        }
    }

    private static String currentLogin( final HttpServletRequest request ) {
        final Principal p = request.getUserPrincipal();
        return p != null ? p.getName() : null;
    }

    // -------------------------------------------------------------------------
    // PUT / DELETE
    // -------------------------------------------------------------------------

    @Override
    protected void doPut( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final ConnectorRuntime runtime = resolveRuntime();
        if ( runtime == null ) {
            sendServiceUnavailable( response );
            return;
        }
        final String path = extractPathParam( request );
        if ( path == null || path.isEmpty() || path.contains( "/" ) ) {
            sendNotFound( response, "Unknown connector endpoint: " + path );
            return;
        }
        handleUpdate( request, path, response );
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final ConnectorRuntime runtime = resolveRuntime();
        if ( runtime == null ) {
            sendServiceUnavailable( response );
            return;
        }
        final String path = extractPathParam( request );
        if ( path == null || path.isEmpty() || path.contains( "/" ) ) {
            sendNotFound( response, "Unknown connector endpoint: " + path );
            return;
        }
        handleDelete( request, path, response );
    }

    // -------------------------------------------------------------------------
    // Manager resolution — overridable for tests
    // -------------------------------------------------------------------------

    /**
     * Resolves the {@link ConnectorRuntime} manager from the live engine. {@code getManager} is
     * declared on {@link com.wikantik.WikiEngine}, not the {@code Engine} interface returned by
     * {@link #getEngine()}, so this mirrors the resolution pattern used elsewhere in
     * {@code wikantik-rest} for managers not yet exposed via {@link com.wikantik.WikiSubsystems}.
     * Returns {@code null} when the engine is not a {@code WikiEngine} or the manager is not
     * registered (very early startup — {@code ConnectorWiringHelper} wires it unconditionally).
     * Protected so tests can inject a stub without engine infrastructure.
     */
    protected ConnectorRuntime resolveRuntime() {
        return getEngine() instanceof com.wikantik.WikiEngine we ? we.getManager( ConnectorRuntime.class ) : null;
    }

    /**
     * Resolves the {@link ConnectorConfigService} manager. {@code null} when the engine is not a
     * {@code WikiEngine} or the manager is not registered — callers fall back to enriching the
     * list/detail payloads from {@link ConnectorRuntime} alone (origin {@code "properties"}).
     * Protected so tests can inject a stub.
     */
    protected ConnectorConfigService resolveConfigService() {
        return getEngine() instanceof com.wikantik.WikiEngine we ? we.getManager( ConnectorConfigService.class ) : null;
    }

    /**
     * Resolves the {@link JdbcSyncRunStore} manager backing {@code GET .../runs}. Protected so
     * tests can inject a stub.
     */
    protected JdbcSyncRunStore resolveRunStore() {
        return getEngine() instanceof com.wikantik.WikiEngine we ? we.getManager( JdbcSyncRunStore.class ) : null;
    }

    /**
     * Resolves the {@link SyncStateStore} manager backing {@code GET .../pages} and the
     * disabled-connector page-count fallback. Protected so tests can inject a stub.
     */
    protected SyncStateStore resolveSyncState() {
        return getEngine() instanceof com.wikantik.WikiEngine we ? we.getManager( SyncStateStore.class ) : null;
    }

    /**
     * Resolves the {@link CredentialStore} manager, mirroring the idiom used by
     * {@link ConnectorCredentialsResource#resolveStore()}. Protected so tests can inject a stub.
     */
    protected CredentialStore resolveCredentialStore() {
        return getEngine() instanceof com.wikantik.WikiEngine we ? we.getManager( CredentialStore.class ) : null;
    }

    private void sendServiceUnavailable( final HttpServletResponse response ) throws IOException {
        sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
            "Connector runtime is not available (engine not yet fully initialized)" );
    }

    private void sendServiceUnavailable( final HttpServletResponse response, final String message ) throws IOException {
        sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, message );
    }
}
