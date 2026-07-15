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

import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.api.connectors.SyncStateStore;
import com.wikantik.connectors.runtime.ConnectorRuntime;
import com.wikantik.connectors.runtime.ConnectorStatus;
import com.wikantik.connectors.state.JdbcSyncRunStore;
import com.wikantik.connectors.state.SyncRunRow;
import com.wikantik.derived.ConnectorConfigService;

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
 * GET-side handlers for {@link ConnectorAdminResource}, extracted verbatim (mirroring the
 * {@code com.wikantik.rest.knowledge.KgProposalAdminHandlers} decomposition precedent) to bring
 * the facade back under the CI complexity gate (GodClass / class-level CyclomaticComplexity):
 * list/detail/runs/pages/status plus the list/detail payload-building helpers those routes — and
 * the write-side {@code ConnectorAdminWriteHandlers#respondDetail} after a successful mutation —
 * all share.
 * <p>
 * Package-private: constructed fresh per request by {@link ConnectorAdminResource#doGet}, holding
 * only a reference back to the owning servlet so it can reach the inherited
 * {@link RestServletBase} JSON/error helpers and the {@code resolveXxx} manager-resolution seams.
 * Both are {@code protected} and reachable here because this class shares the
 * {@code com.wikantik.rest} package with {@link RestServletBase} and {@link ConnectorAdminResource}
 * — no separate IO-duplication helper (unlike {@code AdminKnowledgeIo}) is needed for that reason.
 * Not part of any documented public API of {@code wikantik-rest}.
 */
final class ConnectorAdminReadHandlers {

    private static final Logger LOG = LogManager.getLogger( ConnectorAdminReadHandlers.class );

    private final ConnectorAdminResource resource;

    ConnectorAdminReadHandlers( final ConnectorAdminResource resource ) {
        this.resource = resource;
    }

    /** Single entry point for {@link ConnectorAdminResource#doGet} — routes on path shape to the
     *  list/detail/runs/pages/status handlers below. Keeping the whole GET route table behind one
     *  call site (rather than {@code doGet} branching directly) is what keeps the facade's own
     *  God-Class/ATFD score down: from the facade's perspective this is a single foreign method
     *  call, not five. */
    void handle( final ConnectorRuntime runtime, final String path, final HttpServletRequest request,
                 final HttpServletResponse response ) throws IOException {
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
            resource.sendNotFound( response, "Unknown connector endpoint: " + path );
        }
    }

    private void handleList( final ConnectorRuntime runtime, final HttpServletResponse response ) throws IOException {
        final ConnectorConfigService configService = resource.resolveConfigService();
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

        final CredentialStore credStore = resource.resolveCredentialStore();
        final Map< String, Object > payload = new LinkedHashMap<>();
        payload.put( "syncingEnabled", runtime.syncingEnabled() );
        payload.put( "credentialStoreEnabled", credStore != null && credStore.enabled() );
        payload.put( "connectors", connectors );

        response.setStatus( HttpServletResponse.SC_OK );
        resource.sendJsonWithNulls( response, payload );
    }

    private void handleDetail( final ConnectorRuntime runtime, final String connectorId,
                        final HttpServletResponse response ) throws IOException {
        final ConnectorConfigService configService = resource.resolveConfigService();
        if ( configService != null ) {
            final Optional< ConnectorConfigService.ConnectorView > view = configService.get( connectorId );
            if ( view.isEmpty() ) {
                resource.sendNotFound( response, "Unknown connector: " + connectorId );
                return;
            }
            response.setStatus( HttpServletResponse.SC_OK );
            resource.sendJsonWithNulls( response, detailPayload( runtime, view.get() ) );
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
            resource.sendJsonWithNulls( response, payload );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "ConnectorAdminResource: detail requested for unknown connector '{}': {}",
                connectorId, e.getMessage() );
            resource.sendNotFound( response, e.getMessage() );
        }
    }

    private void handleRuns( final String connectorId, final HttpServletRequest request,
                      final HttpServletResponse response ) throws IOException {
        final JdbcSyncRunStore runStore = resource.resolveRunStore();
        if ( runStore == null ) {
            resource.sendServiceUnavailable( response );
            return;
        }
        final int limit = resource.parseIntParam( request, "limit", 20 );
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
        resource.sendJsonWithNulls( response, Map.of( "runs", runs ) );
    }

    private void handlePages( final String connectorId, final HttpServletResponse response ) throws IOException {
        final SyncStateStore syncState = resource.resolveSyncState();
        if ( syncState == null ) {
            resource.sendServiceUnavailable( response );
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
        resource.sendJsonWithNulls( response, Map.of( "pages", pages ) );
    }

    private void handleStatus( final ConnectorRuntime runtime, final String connectorId,
                        final HttpServletResponse response ) throws IOException {
        try {
            final ConnectorStatus status = runtime.status( connectorId );
            response.setStatus( HttpServletResponse.SC_OK );
            resource.sendJson( response, status );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "ConnectorAdminResource: status requested for unknown connector '{}': {}",
                connectorId, e.getMessage() );
            resource.sendNotFound( response, e.getMessage() );
        }
    }

    // -------------------------------------------------------------------------
    // List/detail merge helpers
    // -------------------------------------------------------------------------

    /** Builds the common id/type/origin/enabled/syncIntervalHours/lastRun/lastStatus/pageCount/
     *  secretsSet fields shared by the list and detail payloads. */
    Map< String, Object > summarize( final ConnectorRuntime runtime,
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
     *  mutation route's success response (via {@code ConnectorAdminWriteHandlers#respondDetail}). */
    Map< String, Object > detailPayload( final ConnectorRuntime runtime,
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
    Map< String, Object > summarizeFromStatus( final ConnectorStatus status ) {
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
        final SyncStateStore syncState = resource.resolveSyncState();
        out.put( "pageCount", syncState != null ? syncState.items( connectorId ).size() : 0 );
    }
}
