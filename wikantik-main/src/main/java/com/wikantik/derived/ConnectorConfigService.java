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
package com.wikantik.derived;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.api.connectors.DriveAuthCoordinator;
import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.api.connectors.SyncStateStore;
import com.wikantik.connectors.config.ConnectorConfigCodec;
import com.wikantik.connectors.config.ConnectorConfigRow;
import com.wikantik.connectors.config.JdbcConnectorConfigStore;
import com.wikantik.connectors.gdrive.DefaultDriveAuthCoordinator;
import com.wikantik.connectors.gdrive.DriveConfig;
import com.wikantik.connectors.gdrive.GoogleDriveOAuthService;
import com.wikantik.connectors.runtime.ConnectorRegistry;
import com.wikantik.connectors.runtime.ConnectorRuntime;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

/**
 * DB-backed CRUD over {@code connector_configs} plus the rebuild-and-swap hot-apply: every
 * mutation re-reads the DB rows and the startup properties snapshot, rebuilds a
 * {@link ConnectorRegistry} (and, when any gdrive config exists, a fresh
 * {@link DriveAuthCoordinator}) exactly the way {@code ConnectorWiringHelper} does at startup —
 * {@link ConnectorAssembler} is the one shared build path — and swaps them atomically into
 * {@link ConnectorRuntime}. Per-connector sync locks live in {@code ConnectorRuntime} and survive
 * the swap; an in-flight sync finishes against its old connector instance.
 *
 * <p>A connector's <em>origin</em> is {@code properties} (wired from {@code wikantik-custom.properties}
 * at startup) or {@code db} (an admin-managed {@code connector_configs} row). A DB row always
 * shadows a same-id properties entry. Mutations against a properties-origin id that has never
 * been imported throw {@link PropertiesOriginException} (design {@code D1};
 * {@code docs/superpowers/specs/2026-07-15-connector-admin-ui-design.md}).
 */
public final class ConnectorConfigService {

    private static final Logger LOG = LogManager.getLogger( ConnectorConfigService.class );

    private static final String GLOBAL_INTERVAL_PROP = "wikantik.connectors.sync.interval.hours";
    private static final String GDRIVE_CALLBACK_PATH = "/admin/connector-oauth/gdrive/callback";

    /** Config keys an admin must never put secret material under — those belong in the credentials
     *  endpoint ({@link CredentialStore}), not the plaintext {@code connector_configs.config} column.
     *  Matched case-insensitively against incoming config keys by {@link #secretKeyErrors}; see
     *  {@link #create} and {@link #update}. */
    private static final Set< String > SECRET_CONFIG_KEYS = Set.of(
        "client_secret", "token", "api_token", "refresh_token", "password", "secret" );

    private static final String SECRET_KEY_MESSAGE = "secret values must be stored via the credentials endpoint, not config";

    private final JdbcConnectorConfigStore configStore;
    private final SyncStateStore syncState;
    private final CredentialStore credStore;
    private final ConnectorRuntime runtime;
    private final Map< String, SourceConnector > propertiesConnectors;
    private final Map< String, String > propertiesTypes;
    private final Map< String, DriveConfig > propertiesDriveConfigs;
    private final Consumer< String > pageDeleter;
    private final Consumer< String > orphanStamper;
    private final Properties props;
    private final Consumer< DriveAuthCoordinator > coordinatorInstaller;
    /** Purges the connector's {@code connector_sync_run} history rows on {@link #delete} so a later
     *  same-id recreation starts with a clean run history instead of inheriting the deleted
     *  connector's (misleading) runs. Backed by {@code JdbcSyncRunStore::purgeRuns} in production. */
    private final Consumer< String > runHistoryPurger;

    /** The properties-origin connector wiring (built once at startup) that a DB row can shadow but
     *  never mutates. Parameter object grouping the three properties-derived maps that would
     *  otherwise be three separate constructor arguments. */
    public record PropertiesOrigin( Map< String, SourceConnector > connectors, Map< String, String > types,
            Map< String, DriveConfig > driveConfigs ) {
        public PropertiesOrigin {
            connectors = Map.copyOf( connectors );
            types = Map.copyOf( types );
            driveConfigs = Map.copyOf( driveConfigs );
        }
    }

    /** The four callback seams {@link #delete} and {@link #rebuild} invoke against the wider engine
     *  (page deletion/orphan-stamping, installing a rebuilt {@link DriveAuthCoordinator}, purging
     *  run history) — grouped so the constructor doesn't carry them as four separate parameters. */
    public record Seams( Consumer< String > pageDeleter, Consumer< String > orphanStamper,
            Consumer< DriveAuthCoordinator > coordinatorInstaller, Consumer< String > runHistoryPurger ) {}

    /** Per-connector content defaults for pages the connector creates (design D10): applied only
     *  at page creation, never overwriting later curation. */
    public record ContentDefaults( String cluster, List< String > tags, String pagePrefix ) {
        public ContentDefaults {
            tags = List.copyOf( tags );
        }
        public static final ContentDefaults EMPTY = new ContentDefaults( null, List.of(), null );
    }

    /** REST-facing view of one connector (no secrets, both origins). {@code config} is the parsed
     *  config JSON for a DB-origin row, or an empty object for a properties-origin entry (the UI
     *  renders those read-only from status alone). */
    public record ConnectorView( String id, String type, String origin, boolean enabled,
            int syncIntervalHours, JsonObject config, String cluster, String defaultTags,
            String pagePrefix, List< String > secretsSet ) {}

    /** Outcome of {@link #delete}. */
    public record DeleteResult( int pagesKept, int pagesDeleted, int credentialsDeleted ) {}

    /** Thrown for mutations (update/delete) against a properties-origin connector id that has
     *  never been imported into the DB. The REST layer maps this to HTTP 409. */
    public static final class PropertiesOriginException extends RuntimeException {
        private final String connectorId;

        public PropertiesOriginException( final String connectorId ) {
            super( "connector '" + connectorId + "' is defined in wikantik-custom.properties" );
            this.connectorId = connectorId;
        }

        public String connectorId() { return connectorId; }
    }

    public ConnectorConfigService( final JdbcConnectorConfigStore configStore, final SyncStateStore syncState,
            final CredentialStore credStore, final ConnectorRuntime runtime,
            final PropertiesOrigin propertiesOrigin, final Seams seams, final Properties props ) {
        this.configStore = configStore;
        this.syncState = syncState;
        this.credStore = credStore;
        this.runtime = runtime;
        this.propertiesConnectors = propertiesOrigin.connectors();
        this.propertiesTypes = propertiesOrigin.types();
        this.propertiesDriveConfigs = propertiesOrigin.driveConfigs();
        this.pageDeleter = seams.pageDeleter();
        this.orphanStamper = seams.orphanStamper();
        this.props = props;
        this.coordinatorInstaller = seams.coordinatorInstaller();
        this.runHistoryPurger = seams.runHistoryPurger();
    }

    /** Both origins; a DB row shadows a same-id properties entry. */
    public List< ConnectorView > list() {
        final Map< String, ConnectorView > byId = new LinkedHashMap<>();
        for ( final String id : propertiesConnectors.keySet() ) {
            byId.put( id, propertiesView( id ) );
        }
        for ( final ConnectorConfigRow row : configStore.list() ) {
            byId.put( row.connectorId(), dbView( row ) );
        }
        return new ArrayList<>( byId.values() );
    }

    public Optional< ConnectorView > get( final String id ) {
        final Optional< ConnectorConfigRow > row = configStore.get( id );
        if ( row.isPresent() ) return Optional.of( dbView( row.get() ) );
        if ( propertiesConnectors.containsKey( id ) ) return Optional.of( propertiesView( id ) );
        return Optional.empty();
    }

    /** Creates a DB-origin connector row. {@code type} must be one of
     *  {@link ConnectorConfigCodec#UI_TYPES} (never {@code filesystem}, design D9) — an
     *  unrecognized/blank/null type yields a {@code connector_type} error without ever invoking
     *  {@link ConnectorConfigCodec#validate} (which NPEs on a null type). For {@code gdrive}, a
     *  missing {@code redirect_uri} is defaulted from {@code wikantik.baseURL} before validation
     *  so the wizard need not compute it. On success the row is persisted and the registry is
     *  hot-rebuilt. */
    public ConnectorConfigCodec.Validation create( final String id, final String type, final JsonObject config,
            final boolean enabled, final int syncIntervalHours, final String cluster,
            final String defaultTags, final String pagePrefix ) {
        final Map< String, String > errors = new LinkedHashMap<>( ConnectorConfigCodec.validateId( id ).errors() );
        if ( configStore.get( id ).isPresent() || propertiesConnectors.containsKey( id ) ) {
            errors.put( "connector_id", "a connector with this id already exists" );
        }
        if ( type == null || type.isBlank() || !ConnectorConfigCodec.UI_TYPES.contains( type ) ) {
            errors.put( "connector_type", "unknown connector type: " + type );
            return new ConnectorConfigCodec.Validation( errors );
        }
        errors.putAll( secretKeyErrors( config ) );
        if ( !errors.isEmpty() ) return new ConnectorConfigCodec.Validation( errors );
        if ( "gdrive".equals( type ) && config != null && isAbsent( config, "redirect_uri" ) ) {
            config.addProperty( "redirect_uri", props.getProperty( "wikantik.baseURL", "" ) + GDRIVE_CALLBACK_PATH );
        }
        errors.putAll( ConnectorConfigCodec.validate( type, config ).errors() );
        if ( !errors.isEmpty() ) return new ConnectorConfigCodec.Validation( errors );

        configStore.upsert( new ConnectorConfigRow( id, type, enabled, syncIntervalHours,
            config.toString(), cluster, defaultTags, pagePrefix ) );
        rebuild();
        return new ConnectorConfigCodec.Validation( Map.of() );
    }

    /** Updates a DB-origin connector row. {@code id} must already be a DB row; a properties-origin
     *  id (never imported) throws {@link PropertiesOriginException}. Type is immutable — taken from
     *  the existing row, never from the caller. On success the row is persisted and the registry
     *  is hot-rebuilt. */
    public ConnectorConfigCodec.Validation update( final String id, final JsonObject config,
            final boolean enabled, final int syncIntervalHours, final String cluster,
            final String defaultTags, final String pagePrefix ) {
        final Optional< ConnectorConfigRow > existing = configStore.get( id );
        if ( existing.isEmpty() ) {
            if ( propertiesConnectors.containsKey( id ) ) throw new PropertiesOriginException( id );
            throw new IllegalArgumentException( "unknown connector: " + id );
        }
        final Map< String, String > secretErrors = secretKeyErrors( config );
        if ( !secretErrors.isEmpty() ) return new ConnectorConfigCodec.Validation( secretErrors );
        final String type = existing.get().connectorType();
        final ConnectorConfigCodec.Validation v = ConnectorConfigCodec.validate( type, config );
        if ( !v.ok() ) return v;

        configStore.upsert( new ConnectorConfigRow( id, type, enabled, syncIntervalHours,
            config.toString(), cluster, defaultTags, pagePrefix ) );
        rebuild();
        return v;
    }

    /** DB-origin only — a properties-origin id throws {@link PropertiesOriginException} (design D3).
     *  Every page this connector synced is either stamped {@code derived_orphaned: true} (kept, the
     *  default) or hard-deleted, per {@code deletePages}; sync state, run history, and stored
     *  credentials are always removed (so a later same-id recreation starts clean). Ends with a
     *  registry rebuild. */
    public DeleteResult delete( final String id, final boolean deletePages ) {
        final Optional< ConnectorConfigRow > existing = configStore.get( id );
        if ( existing.isEmpty() ) {
            if ( propertiesConnectors.containsKey( id ) ) throw new PropertiesOriginException( id );
            throw new IllegalArgumentException( "unknown connector: " + id );
        }

        int pagesKept = 0;
        int pagesDeleted = 0;
        for ( final SyncStateStore.SyncedItem item : syncState.items( id ) ) {
            if ( deletePages ) {
                pageDeleter.accept( item.pageName() );
                pagesDeleted++;
            } else {
                orphanStamper.accept( item.pageName() );
                pagesKept++;
            }
        }
        syncState.purge( id );
        try {
            runHistoryPurger.accept( id );
        } catch ( final RuntimeException e ) {
            // Stale run-history rows are cosmetic (misleading history for a future same-id
            // recreation) — never worth aborting the delete over.
            LOG.warn( "connector '{}': run-history purge failed ({}) — continuing with the delete",
                id, e.getMessage() );
        }

        int credentialsDeleted = 0;
        for ( final String name : credStore.list( id ) ) {
            credStore.delete( id, name );
            credentialsDeleted++;
        }

        configStore.delete( id );
        rebuild();
        return new DeleteResult( pagesKept, pagesDeleted, credentialsDeleted );
    }

    /** Copies a properties-defined connector into the DB, after which the DB row shadows the
     *  properties definition (design D1). {@code id} must name a live properties connector and
     *  must not already be a DB row. The type is taken from the properties wiring, never from the
     *  caller; enabled defaults to {@code true} and the interval to the global default (content
     *  defaults start empty — the operator can add them via {@link #update} afterwards). */
    public ConnectorConfigCodec.Validation importFromProperties( final String id, final JsonObject configFromCaller ) {
        final String type = propertiesTypes.get( id );
        if ( type == null ) {
            return new ConnectorConfigCodec.Validation( Map.of( "connector_id", "not a properties-defined connector" ) );
        }
        if ( configStore.get( id ).isPresent() ) {
            return new ConnectorConfigCodec.Validation( Map.of( "connector_id", "already imported" ) );
        }
        final ConnectorConfigCodec.Validation v = ConnectorConfigCodec.validate( type, configFromCaller );
        if ( !v.ok() ) return v;

        configStore.upsert( new ConnectorConfigRow( id, type, true, ( int ) globalIntervalDefault(),
            configFromCaller.toString(), null, null, null ) );
        rebuild();
        return v;
    }

    public ContentDefaults defaultsFor( final String connectorId ) {
        final Optional< ConnectorConfigRow > row = configStore.get( connectorId );
        if ( row.isEmpty() ) return ContentDefaults.EMPTY;
        final ConnectorConfigRow r = row.get();
        return new ContentDefaults( r.cluster(), parseTags( r.defaultTags() ), r.pagePrefix() );
    }

    /** DB row → its stored value; properties-origin (no DB row) → the global
     *  {@code wikantik.connectors.sync.interval.hours} default (design D4). */
    public long intervalHoursFor( final String connectorId ) {
        final Optional< ConnectorConfigRow > row = configStore.get( connectorId );
        if ( row.isPresent() ) return row.get().syncIntervalHours();
        return globalIntervalDefault();
    }

    /** Re-reads DB rows + the startup properties snapshot, rebuilds the registry from scratch, and
     *  hot-swaps it into {@link ConnectorRuntime}. A DB row shadows a same-id properties entry —
     *  disabled or unbuildable rows are removed from the registry (but stay listed, see {@link #list}).
     *  One bad row (malformed JSON, a config that no longer validates) is skipped with a
     *  {@code LOG.warn} rather than failing the whole rebuild. Also rebuilds the
     *  {@link DriveAuthCoordinator} — combining gdrive configs from both origins, secret-resolved
     *  for DB rows the same way {@link ConnectorAssembler} resolves them at build time — and installs
     *  it via the {@code coordinatorInstaller} seam, but only when that combined map is non-empty. */
    public synchronized void rebuild() {
        final Map< String, SourceConnector > byId = new LinkedHashMap<>( propertiesConnectors );
        final Map< String, String > typeById = new LinkedHashMap<>( propertiesTypes );
        final Map< String, String > originById = new LinkedHashMap<>();
        for ( final String id : propertiesConnectors.keySet() ) originById.put( id, "properties" );
        final Map< String, DriveConfig > drives = new LinkedHashMap<>( propertiesDriveConfigs );

        for ( final ConnectorConfigRow row : configStore.list() ) {
            final String id = row.connectorId();
            final String type = row.connectorType();
            typeById.put( id, type );
            originById.put( id, "db" );
            byId.remove( id );   // a DB row always shadows a same-id properties entry, enabled or not
            try {
                final JsonObject cfg = JsonParser.parseString( row.configJson() ).getAsJsonObject();
                final Object typed = ConnectorConfigCodec.toConfig( type, cfg );
                if ( "gdrive".equals( type ) ) {
                    drives.put( id, resolveDriveSecret( id, ( DriveConfig ) typed ) );
                }
                if ( row.enabled() ) {
                    ConnectorAssembler.build( id, type, typed, credStore ).ifPresent( c -> byId.put( id, c ) );
                }
            } catch ( final RuntimeException e ) {
                LOG.warn( "connector '{}': failed to rebuild from its stored config ({}) — skipping this connector",
                    id, e.getMessage() );
            }
        }

        runtime.swapRegistry( new ConnectorRegistry( byId, typeById, originById ) );

        if ( !drives.isEmpty() ) {
            coordinatorInstaller.accept( new DefaultDriveAuthCoordinator( drives, new GoogleDriveOAuthService(), credStore ) );
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private ConnectorView propertiesView( final String id ) {
        final String type = propertiesTypes.getOrDefault( id, "unknown" );
        return new ConnectorView( id, type, "properties", true, ( int ) intervalHoursFor( id ),
            new JsonObject(), null, null, null, credStore.list( id ) );
    }

    private ConnectorView dbView( final ConnectorConfigRow row ) {
        JsonObject cfg;
        try {
            cfg = JsonParser.parseString( row.configJson() ).getAsJsonObject();
        } catch ( final RuntimeException e ) {
            LOG.warn( "connector '{}': stored config is not valid JSON ({}) — showing an empty config",
                row.connectorId(), e.getMessage() );
            cfg = new JsonObject();
        }
        return new ConnectorView( row.connectorId(), row.connectorType(), "db", row.enabled(),
            row.syncIntervalHours(), cfg, row.cluster(), row.defaultTags(), row.pagePrefix(),
            credStore.list( row.connectorId() ) );
    }

    /** DB-origin {@code DriveConfig} records carry no client secret (never persisted in the config
     *  row) — it lives in the {@link CredentialStore} under "client_secret" instead. Mirrors
     *  {@code ConnectorAssembler.resolveDriveSecret}; duplicated here (small, private) rather than
     *  shared, per the design brief. */
    private DriveConfig resolveDriveSecret( final String id, final DriveConfig cfg ) {
        if ( cfg.clientSecret() != null ) return cfg;
        return new DriveConfig( cfg.folderIds(), cfg.maxFiles(), cfg.clientId(),
            credStore.get( id, "client_secret" ).orElse( null ), cfg.redirectUri(), cfg.exportMimeType() );
    }

    private long globalIntervalDefault() {
        try {
            return Long.parseLong( props.getProperty( GLOBAL_INTERVAL_PROP, "0" ).trim() );
        } catch ( final NumberFormatException e ) {
            return 0L;
        }
    }

    /** Field-keyed errors for every key in {@code config} that exact-matches (case-insensitive)
     *  {@link #SECRET_CONFIG_KEYS} — defense against an admin storing a secret in the plaintext
     *  {@code config} column instead of the credentials endpoint. Empty when {@code config} is
     *  {@code null} or carries no denylisted key; unmodeled non-secret keys (e.g. {@code user_agent})
     *  pass through untouched. The error key preserves the caller's original field casing. */
    private static Map< String, String > secretKeyErrors( final JsonObject config ) {
        if ( config == null ) return Map.of();
        final Map< String, String > errors = new LinkedHashMap<>();
        for ( final String key : config.keySet() ) {
            if ( SECRET_CONFIG_KEYS.contains( key.toLowerCase( Locale.ROOT ) ) ) {
                errors.put( key, SECRET_KEY_MESSAGE );
            }
        }
        return errors;
    }

    private static boolean isAbsent( final JsonObject config, final String key ) {
        if ( !config.has( key ) || config.get( key ).isJsonNull() ) return true;
        try {
            return config.get( key ).getAsString().isBlank();
        } catch ( final RuntimeException e ) {
            return true;
        }
    }

    private static List< String > parseTags( final String raw ) {
        if ( raw == null || raw.isBlank() ) return List.of();
        final List< String > out = new ArrayList<>();
        for ( final String tag : raw.split( "," ) ) {
            final String trimmed = tag.trim();
            if ( !trimmed.isEmpty() ) out.add( trimmed );
        }
        return List.copyOf( out );
    }
}
