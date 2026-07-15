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
import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.api.connectors.SyncStateStore;
import com.wikantik.connectors.SyncReport;
import com.wikantik.connectors.runtime.ConnectorRuntime;
import com.wikantik.connectors.runtime.ConnectorStatus;
import com.wikantik.connectors.state.JdbcSyncRunStore;
import com.wikantik.connectors.state.SyncRunRow;
import com.wikantik.derived.ConnectorConfigService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 *       returning the resulting {@link SyncReport}.</li>
 * </ul>
 *
 * <p>All endpoints are protected by {@code AdminAuthFilter} (the {@code /admin/*} filter mapping).
 * {@link ConnectorRuntime} is wired unconditionally at engine startup, so a {@code 503 Service
 * Unavailable} here means the engine is not yet a fully-initialized {@code WikiEngine} (very early
 * startup), not that connectors are disabled. An unknown connector id responds {@code 404}.
 */
public class ConnectorAdminResource extends RestServletBase {

    private static final long   serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( ConnectorAdminResource.class );

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
            final Map< String, Object > payload = summarize( runtime, view.get() );
            payload.put( "config", view.get().config() );
            payload.put( "cluster", view.get().cluster() );
            payload.put( "defaultTags", view.get().defaultTags() );
            payload.put( "pagePrefix", view.get().pagePrefix() );
            response.setStatus( HttpServletResponse.SC_OK );
            sendJsonWithNulls( response, payload );
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
            sendNotFound( response, "Unknown connector endpoint" );
            return;
        }

        final String[] segments = path.split( "/" );
        if ( segments.length == 2 && "sync".equals( segments[ 1 ] ) ) {
            handleSync( runtime, segments[ 0 ], response );
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
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "ConnectorAdminResource: sync requested for unknown connector '{}': {}",
                connectorId, e.getMessage() );
            sendNotFound( response, e.getMessage() );
        }
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
}
