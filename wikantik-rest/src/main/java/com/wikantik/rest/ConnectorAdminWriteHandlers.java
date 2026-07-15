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
import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditService;
import com.wikantik.connectors.SyncReport;
import com.wikantik.connectors.config.ConnectorConfigCodec;
import com.wikantik.connectors.runtime.ConnectorRuntime;
import com.wikantik.connectors.runtime.ConnectorsDisabledException;
import com.wikantik.derived.ConnectorConfigService;
import com.wikantik.derived.ConnectorTestService;
import com.wikantik.derived.ConnectorWiringHelper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * POST/PUT/DELETE-side handlers for {@link ConnectorAdminResource}, extracted verbatim (mirroring
 * the {@code com.wikantik.rest.knowledge.KgProposalAdminHandlers} decomposition precedent) to bring
 * the facade back under the CI complexity gate (GodClass / class-level CyclomaticComplexity):
 * create/update/delete/import, the two test-probe dry-runs, sync, and the audit + validation-error
 * response helpers those routes share.
 * <p>
 * Package-private: constructed fresh per request by {@link ConnectorAdminResource#doPost}
 * (and {@code doPut}/{@code doDelete}), holding a reference back to the owning servlet — for the
 * inherited {@link RestServletBase} JSON/error helpers and the {@code resolveXxx}/{@code
 * probeUnsaved} seams, both reachable here because this class shares the {@code com.wikantik.rest}
 * package with {@link RestServletBase} and {@link ConnectorAdminResource} — and to the sibling
 * {@link ConnectorAdminReadHandlers} for {@link ConnectorAdminReadHandlers#detailPayload}, reused
 * by {@link #respondDetail} after every successful mutation. Not part of any documented public API
 * of {@code wikantik-rest}.
 */
final class ConnectorAdminWriteHandlers {

    private static final Logger LOG = LogManager.getLogger( ConnectorAdminWriteHandlers.class );

    private final ConnectorAdminResource resource;
    private final ConnectorAdminReadHandlers readHandlers;

    ConnectorAdminWriteHandlers( final ConnectorAdminResource resource, final ConnectorAdminReadHandlers readHandlers ) {
        this.resource = resource;
        this.readHandlers = readHandlers;
    }

    /** Single entry point for {@link ConnectorAdminResource#doPost} — routes on path shape to the
     *  create/test/sync/import handlers below. Keeping the whole POST route table behind one call
     *  site (rather than {@code doPost} branching directly) is what keeps the facade's own
     *  God-Class/ATFD score down: from the facade's perspective this is a single foreign method
     *  call, not five. */
    void handle( final ConnectorRuntime runtime, final String path, final HttpServletRequest request,
                 final HttpServletResponse response ) throws IOException {
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
            resource.sendNotFound( response, "Unknown connector endpoint: " + path );
        }
    }

    /** Single entry point for {@link ConnectorAdminResource#doPut} — {@code path} must be exactly
     *  one segment (the connector id); anything else is an unknown endpoint. */
    void handlePut( final HttpServletRequest request, final String path, final HttpServletResponse response )
            throws IOException {
        if ( path == null || path.isEmpty() || path.contains( "/" ) ) {
            resource.sendNotFound( response, "Unknown connector endpoint: " + path );
            return;
        }
        handleUpdate( request, path, response );
    }

    /** Single entry point for {@link ConnectorAdminResource#doDelete} — {@code path} must be
     *  exactly one segment (the connector id); anything else is an unknown endpoint. */
    void handleDeleteRoute( final HttpServletRequest request, final String path, final HttpServletResponse response )
            throws IOException {
        if ( path == null || path.isEmpty() || path.contains( "/" ) ) {
            resource.sendNotFound( response, "Unknown connector endpoint: " + path );
            return;
        }
        handleDelete( request, path, response );
    }

    private void handleSync( final ConnectorRuntime runtime, final String connectorId,
                      final HttpServletResponse response ) throws IOException {
        try {
            LOG.info( "ConnectorAdminResource: sync requested for connector '{}'", connectorId );
            final SyncReport report = runtime.syncNow( connectorId );
            LOG.info( "ConnectorAdminResource: sync complete for connector '{}': {}", connectorId, report );
            response.setStatus( HttpServletResponse.SC_OK );
            resource.sendJson( response, report );
        } catch ( final com.wikantik.connectors.runtime.SyncInProgressException e ) {
            LOG.info( "ConnectorAdminResource: sync rejected for connector '{}': {}", connectorId, e.getMessage() );
            resource.sendError( response, HttpServletResponse.SC_CONFLICT, e.getMessage() );
        } catch ( final ConnectorsDisabledException e ) {
            LOG.info( "ConnectorAdminResource: sync rejected for connector '{}': connectors disabled: {}",
                connectorId, e.getMessage() );
            resource.sendError( response, HttpServletResponse.SC_CONFLICT, e.getMessage() );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "ConnectorAdminResource: sync requested for unknown connector '{}': {}",
                connectorId, e.getMessage() );
            resource.sendNotFound( response, e.getMessage() );
        }
    }

    /** {@code POST /admin/connectors} — creates a DB-origin connector row. See
     *  {@link ConnectorAdminResource}'s class javadoc. */
    private void handleCreate( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final ConnectorConfigService configService = resource.resolveConfigService();
        if ( configService == null ) {
            resource.sendServiceUnavailable( response, ConnectorAdminResource.CONFIG_SERVICE_UNAVAILABLE );
            return;
        }
        final JsonObject body = resource.parseJsonBody( request, response );
        if ( body == null ) return;   // parseJsonBody already sent 400

        final String id = resource.getJsonString( body, "id" );
        if ( id != null && ConnectorAdminResource.RESERVED_CONNECTOR_IDS.contains( id ) ) {
            sendValidationErrors( response, new ConnectorConfigCodec.Validation( Map.of( "connector_id", "reserved id" ) ) );
            return;
        }
        final String type = resource.getJsonString( body, "type" );
        final JsonObject config = bodyConfig( body );
        final boolean enabled = resource.getJsonBoolean( body, "enabled", true );
        final int syncIntervalHours = resource.getJsonInt( body, "syncIntervalHours", 0 );
        final String cluster = resource.getJsonString( body, "cluster" );
        final String defaultTags = resource.getJsonString( body, "defaultTags" );
        final String pagePrefix = resource.getJsonString( body, "pagePrefix" );

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

    /** {@code PUT /admin/connectors/{id}} — updates a DB-origin connector row. See
     *  {@link ConnectorAdminResource}'s class javadoc. */
    private void handleUpdate( final HttpServletRequest request, final String connectorId,
                        final HttpServletResponse response ) throws IOException {
        final ConnectorConfigService configService = resource.resolveConfigService();
        if ( configService == null ) {
            resource.sendServiceUnavailable( response, ConnectorAdminResource.CONFIG_SERVICE_UNAVAILABLE );
            return;
        }
        final JsonObject body = resource.parseJsonBody( request, response );
        if ( body == null ) return;

        final JsonObject config = bodyConfig( body );
        final boolean enabled = resource.getJsonBoolean( body, "enabled", true );
        final int syncIntervalHours = resource.getJsonInt( body, "syncIntervalHours", 0 );
        final String cluster = resource.getJsonString( body, "cluster" );
        final String defaultTags = resource.getJsonString( body, "defaultTags" );
        final String pagePrefix = resource.getJsonString( body, "pagePrefix" );

        final ConnectorConfigCodec.Validation v;
        try {
            v = configService.update( connectorId, config, enabled, syncIntervalHours, cluster, defaultTags, pagePrefix );
        } catch ( final ConnectorConfigService.PropertiesOriginException e ) {
            resource.sendError( response, HttpServletResponse.SC_CONFLICT, e.getMessage() );
            return;
        } catch ( final IllegalArgumentException e ) {
            resource.sendNotFound( response, e.getMessage() );
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

    /** {@code DELETE /admin/connectors/{id}?deletePages=true|false} (default {@code false}). See
     *  {@link ConnectorAdminResource}'s class javadoc. */
    private void handleDelete( final HttpServletRequest request, final String connectorId,
                        final HttpServletResponse response ) throws IOException {
        final ConnectorConfigService configService = resource.resolveConfigService();
        if ( configService == null ) {
            resource.sendServiceUnavailable( response, ConnectorAdminResource.CONFIG_SERVICE_UNAVAILABLE );
            return;
        }
        final boolean deletePages = Boolean.parseBoolean( request.getParameter( "deletePages" ) );

        final ConnectorConfigService.DeleteResult result;
        try {
            result = configService.delete( connectorId, deletePages );
        } catch ( final ConnectorConfigService.PropertiesOriginException e ) {
            resource.sendError( response, HttpServletResponse.SC_CONFLICT, e.getMessage() );
            return;
        } catch ( final IllegalArgumentException e ) {
            resource.sendNotFound( response, e.getMessage() );
            return;
        }

        LOG.info( "ConnectorAdminResource: deleted connector '{}' (deletePages={})", connectorId, deletePages );
        recordAudit( "connector.delete", currentLogin( request ), connectorId, "deletePages=" + deletePages );

        response.setStatus( HttpServletResponse.SC_OK );
        resource.sendJson( response, Map.of(
            "pagesKept", result.pagesKept(),
            "pagesDeleted", result.pagesDeleted(),
            "credentialsDeleted", result.credentialsDeleted() ) );
    }

    /** {@code POST /admin/connectors/{id}/import} — copies a properties-defined connector into the
     *  DB via {@link ConnectorConfigService#importFromProperties}, reconstructing its config JSON
     *  from the startup properties. See {@link ConnectorAdminResource}'s class javadoc. */
    private void handleImport( final HttpServletRequest request, final String connectorId,
                        final HttpServletResponse response ) throws IOException {
        final ConnectorConfigService configService = resource.resolveConfigService();
        if ( configService == null ) {
            resource.sendServiceUnavailable( response, ConnectorAdminResource.CONFIG_SERVICE_UNAVAILABLE );
            return;
        }

        final Optional< ConnectorConfigService.ConnectorView > view = configService.get( connectorId );
        if ( view.isEmpty() ) {
            resource.sendNotFound( response, "not a properties-defined connector: " + connectorId );
            return;
        }
        if ( "db".equals( view.get().origin() ) ) {
            resource.sendError( response, HttpServletResponse.SC_CONFLICT,
                "connector '" + connectorId + "' is already imported" );
            return;
        }

        final String type = view.get().type();
        final Properties props = resource.getEngine().getWikiProperties();
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
     *  config, credentials?}} payload (the wizard's Test step). Validation mirrors
     *  {@link #handleCreate}. No audit — read-only, no state change. */
    private void handleTestUnsaved( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final JsonObject body = resource.parseJsonBody( request, response );
        if ( body == null ) return;   // parseJsonBody already sent 400

        final String type = resource.getJsonString( body, "type" );
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
        final ConnectorTestService.TestResult result = resource.probeUnsaved(
            ConnectorAdminResource.TEST_PROBE_CONNECTOR_ID, type, config, transientCredentials,
            resource.resolveCredentialStore() );
        response.setStatus( HttpServletResponse.SC_OK );
        resource.sendJson( response, result );
    }

    /** {@code POST /admin/connectors/{id}/test} — dry-run probe of a <em>saved</em> connector at
     *  its live (uncapped) config; {@code 404} for an id the registry doesn't know about. No audit
     *  — read-only, no state change. */
    private void handleTestSaved( final ConnectorRuntime runtime, final String connectorId,
                           final HttpServletResponse response ) throws IOException {
        final Optional< SourceConnector > connector = runtime.registry().get( connectorId );
        if ( connector.isEmpty() ) {
            resource.sendNotFound( response, "Unknown connector: " + connectorId );
            return;
        }
        final ConnectorTestService.TestResult result = ConnectorTestService.testSaved( connector.get() );
        response.setStatus( HttpServletResponse.SC_OK );
        resource.sendJson( response, result );
    }

    /** {@code credentials} from the request body as a flat name→value map, or an empty map when
     *  absent/not-an-object. Values that aren't JSON primitives are skipped rather than rejecting
     *  the whole request — mirrors {@link #bodyConfig}'s shape-proof idiom. */
    private Map< String, String > extractCredentials( final JsonObject body ) {
        if ( !body.has( "credentials" ) || !body.get( "credentials" ).isJsonObject() ) return Map.of();
        final JsonObject credentials = body.getAsJsonObject( "credentials" );
        final Map< String, String > out = new LinkedHashMap<>();
        for ( final String name : credentials.keySet() ) {
            final String value = resource.getJsonString( credentials, name );
            if ( value != null ) out.put( name, value );
        }
        return Map.copyOf( out );
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
        resource.sendJson( response, Map.of( "errors", v.errors() ) );
    }

    /** Re-fetches {@code connectorId} and sends its detail payload at {@code status} — the shared
     *  success response for create/update/import. */
    private void respondDetail( final ConnectorConfigService configService, final String connectorId,
                                 final HttpServletResponse response, final int status ) throws IOException {
        final Optional< ConnectorConfigService.ConnectorView > view = configService.get( connectorId );
        if ( view.isEmpty() ) {
            // Should be unreachable right after a successful mutation — fail safe rather than NPE.
            LOG.warn( "connector '{}': vanished immediately after a successful mutation", connectorId );
            resource.sendNotFound( response, "Unknown connector: " + connectorId );
            return;
        }
        response.setStatus( status );
        resource.sendJsonWithNulls( response, readHandlers.detailPayload( resource.resolveRuntime(), view.get() ) );
    }

    /** Records a {@code category=ADMIN} audit entry mirroring {@code AdminApiKeysResource}'s idiom
     *  exactly: {@code getEngine() instanceof WikiEngine} to reach
     *  {@link com.wikantik.WikiEngine#getAuditService()}, wrapped in try/catch so a broken audit
     *  backend never fails the mutation itself (mutation already succeeded by the time this runs).
     *  Only called on success paths — a validation/404/409 rejection is not an auditable connector
     *  mutation. */
    private void recordAudit( final String eventType, final String actorLogin, final String connectorId,
                               final String targetLabel ) {
        try {
            final AuditService audit = resource.getEngine() instanceof com.wikantik.WikiEngine wikiEngine
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
}
