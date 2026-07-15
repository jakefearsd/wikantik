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
import com.wikantik.connectors.runtime.ConnectorRuntime;
import com.wikantik.connectors.state.JdbcSyncRunStore;
import com.wikantik.derived.ConnectorConfigService;
import com.wikantik.derived.ConnectorTestService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Map;
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
 *   <li>{@code GET  /admin/connectors/{id}/runs?limit=20} — recent
 *       {@link com.wikantik.connectors.state.SyncRunRow} history for connector {@code id}, newest
 *       first.</li>
 *   <li>{@code GET  /admin/connectors/{id}/pages} — the connector's synced items
 *       ({@link SyncStateStore.SyncedItem}).</li>
 *   <li>{@code GET  /admin/connectors/{id}/status} — returns the
 *       {@link com.wikantik.connectors.runtime.ConnectorStatus} for connector {@code id}.</li>
 *   <li>{@code POST /admin/connectors/{id}/sync} — triggers an immediate sync for connector {@code id},
 *       returning the resulting {@link com.wikantik.connectors.SyncReport}. {@code 409} when syncing
 *       is disabled ({@link com.wikantik.connectors.runtime.ConnectorsDisabledException}) or already
 *       in progress.</li>
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
 * {@link com.wikantik.audit.AuditService} never fails the request itself.
 *
 * <p>This class only owns routing (parsing the path, dispatching to a handler) and the
 * test-overridable manager-resolution seams below; the route bodies themselves live in
 * {@link ConnectorAdminReadHandlers} (GET) and {@link ConnectorAdminWriteHandlers}
 * (POST/PUT/DELETE) — extracted verbatim, mirroring the
 * {@code com.wikantik.rest.knowledge.KgProposalAdminHandlers} decomposition precedent, to keep
 * this facade under the CI complexity gate.
 */
public class ConnectorAdminResource extends RestServletBase {

    private static final long   serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( ConnectorAdminResource.class );

    /** 503 message for every mutation route when {@link ConnectorConfigService} isn't wired.
     *  Package-private — read by {@link ConnectorAdminWriteHandlers}. */
    static final String CONFIG_SERVICE_UNAVAILABLE = "Connector configuration service unavailable";

    /** Connector ids a create request may never use — {@code "test"} would otherwise be shadowed
     *  by the {@code POST /admin/connectors/test} dry-run route (a single-segment path always
     *  routes there, never to a saved connector named "test"; see {@link #doPost}). Rejecting the
     *  id here at creation time means that routing precedence never actually has to matter.
     *  Package-private — read by {@link ConnectorAdminWriteHandlers}. */
    static final Set< String > RESERVED_CONNECTOR_IDS = Set.of( "test" );

    /** Throwaway assembly id for {@code POST /admin/connectors/test} (unsaved payload) — never
     *  persisted; see {@link ConnectorTestService#testUnsaved}'s javadoc for why the literal value
     *  doesn't matter. Package-private — read by {@link ConnectorAdminWriteHandlers}. */
    static final String TEST_PROBE_CONNECTOR_ID = "connector-test-probe";

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
     *  them — see {@link #NULL_SAFE_GSON}. Package-private — called by
     *  {@link ConnectorAdminReadHandlers} and {@link ConnectorAdminWriteHandlers}. */
    void sendJsonWithNulls( final HttpServletResponse response, final Object payload ) throws IOException {
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

        new ConnectorAdminReadHandlers( this ).handle( runtime, extractPathParam( request ), request, response );
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
        newWriteHandlers().handle( runtime, extractPathParam( request ), request, response );
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
        newWriteHandlers().handlePut( request, extractPathParam( request ), response );
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final ConnectorRuntime runtime = resolveRuntime();
        if ( runtime == null ) {
            sendServiceUnavailable( response );
            return;
        }
        newWriteHandlers().handleDeleteRoute( request, extractPathParam( request ), response );
    }

    /** {@link ConnectorAdminWriteHandlers} needs a sibling {@link ConnectorAdminReadHandlers} for
     *  {@code respondDetail} (the shared success-response payload after a mutation) — one helper so
     *  the three write-route callers above don't each repeat the two-object construction. */
    private ConnectorAdminWriteHandlers newWriteHandlers() {
        return new ConnectorAdminWriteHandlers( this, new ConnectorAdminReadHandlers( this ) );
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

    /** Testing seam over {@link ConnectorTestService#testUnsaved} — protected so tests can inject
     *  a canned {@link ConnectorTestService.TestResult} without exercising {@link
     *  com.wikantik.derived.ConnectorAssembler}'s real (network-making) connector construction,
     *  mirroring the {@code resolveXxx} pattern above. */
    protected ConnectorTestService.TestResult probeUnsaved( final String id, final String type,
            final com.google.gson.JsonObject config, final Map< String, String > transientCredentials,
            final CredentialStore realStore ) {
        return ConnectorTestService.testUnsaved( id, type, config, transientCredentials, realStore );
    }

    void sendServiceUnavailable( final HttpServletResponse response ) throws IOException {
        sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
            "Connector runtime is not available (engine not yet fully initialized)" );
    }

    void sendServiceUnavailable( final HttpServletResponse response, final String message ) throws IOException {
        sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, message );
    }
}
