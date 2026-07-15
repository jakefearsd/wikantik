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
import com.wikantik.api.connectors.SyncBatch;
import com.wikantik.api.connectors.SyncCursor;
import com.wikantik.api.connectors.SyncStateStore;
import com.wikantik.connectors.SyncOrchestrator;
import com.wikantik.connectors.config.ConnectorConfigCodec;
import com.wikantik.connectors.config.ConnectorConfigRow;
import com.wikantik.connectors.config.JdbcConnectorConfigStore;
import com.wikantik.connectors.gdrive.DriveConfig;
import com.wikantik.connectors.runtime.ConnectorRegistry;
import com.wikantik.connectors.runtime.ConnectorRuntime;
import com.wikantik.connectors.runtime.ConnectorStatusReader;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ConnectorConfigServiceTest {

    private JdbcConnectorConfigStore configStore;
    private FakeSyncStateStore syncState;
    private FakeCredentialStore credStore;
    private ConnectorRuntime runtime;
    private List< String > pageDeletes;
    private List< String > orphanStamps;
    private List< DriveAuthCoordinator > installedCoordinators;
    private Properties props;

    @BeforeEach void schema() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:svc" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL" );
        final DataSource ds = h2;
        try ( Connection c = ds.getConnection(); var s = c.createStatement() ) {
            s.execute( "CREATE TABLE IF NOT EXISTS connector_configs (connector_id VARCHAR PRIMARY KEY,"
                + " connector_type VARCHAR NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,"
                + " sync_interval_hours INT NOT NULL DEFAULT 0, config VARCHAR NOT NULL,"
                + " cluster VARCHAR, default_tags VARCHAR, page_prefix VARCHAR,"
                + " created TIMESTAMP WITH TIME ZONE DEFAULT now(), modified TIMESTAMP WITH TIME ZONE DEFAULT now())" );
        }
        configStore = new JdbcConnectorConfigStore( ds );
        syncState = new FakeSyncStateStore();
        credStore = new FakeCredentialStore();
        pageDeletes = new ArrayList<>();
        orphanStamps = new ArrayList<>();
        installedCoordinators = new ArrayList<>();
        props = new Properties();

        final SyncOrchestrator orch = mock( SyncOrchestrator.class );
        final ConnectorRegistry emptyRegistry = new ConnectorRegistry( Map.of(), Map.of() );
        runtime = new ConnectorRuntime( emptyRegistry, orch, new ConnectorStatusReader( mock( DataSource.class ) ) );
    }

    private ConnectorConfigService service( final Map< String, SourceConnector > propertiesConnectors,
            final Map< String, String > propertiesTypes, final Map< String, DriveConfig > propertiesDriveConfigs ) {
        final Consumer< String > pageDeleter = pageDeletes::add;
        final Consumer< String > orphanStamper = orphanStamps::add;
        final Consumer< DriveAuthCoordinator > coordinatorInstaller = installedCoordinators::add;
        return new ConnectorConfigService( configStore, syncState, credStore, runtime,
            propertiesConnectors, propertiesTypes, propertiesDriveConfigs,
            pageDeleter, orphanStamper, props, coordinatorInstaller );
    }

    private static SourceConnector stubConnector( final String id ) {
        return new SourceConnector() {
            @Override public String connectorId() { return id; }
            @Override public SyncBatch poll( final SyncCursor c ) {
                return new SyncBatch( List.of(), List.of(), new SyncCursor( "done" ), true );
            }
        };
    }

    private static JsonObject json( final String raw ) {
        return JsonParser.parseString( raw ).getAsJsonObject();
    }

    // ---- create ------------------------------------------------------------------------------

    @Test void createValidRowAppearsInRegistryAndList() {
        final ConnectorConfigService svc = service( Map.of(), Map.of(), Map.of() );
        final JsonObject cfg = json( "{\"repo\":\"jake/notes\"}" );
        final ConnectorConfigCodec.Validation v = svc.create( "gh", "github", cfg, true, 24, "Eng", "notes,gh", "Gh-" );
        assertTrue( v.ok(), "expected no validation errors: " + v.errors() );

        assertTrue( runtime.registry().get( "gh" ).isPresent() );
        assertEquals( "db", runtime.registry().originOf( "gh" ) );

        final Optional< ConnectorConfigService.ConnectorView > view = svc.get( "gh" );
        assertTrue( view.isPresent() );
        assertEquals( "db", view.get().origin() );
        assertTrue( view.get().secretsSet().isEmpty() );
        assertTrue( svc.list().stream().anyMatch( vw -> vw.id().equals( "gh" ) ) );
    }

    @Test void createRejectsDuplicateOfPropertiesId() {
        final ConnectorConfigService svc = service(
            Map.of( "legacy", stubConnector( "legacy" ) ), Map.of( "legacy", "feed" ), Map.of() );
        final JsonObject cfg = json( "{\"feed_urls\":[\"https://example.com/feed.xml\"]}" );
        final ConnectorConfigCodec.Validation v = svc.create( "legacy", "feed", cfg, true, 0, null, null, null );
        assertFalse( v.ok() );
        assertTrue( v.errors().containsKey( "connector_id" ), v.errors().toString() );
        assertTrue( configStore.get( "legacy" ).isEmpty(), "nothing should be persisted" );
    }

    @Test void createRejectsBadConfig() {
        final ConnectorConfigService svc = service( Map.of(), Map.of(), Map.of() );
        final JsonObject cfg = json( "{}" );   // github requires "repo"
        final ConnectorConfigCodec.Validation v = svc.create( "gh2", "github", cfg, true, 0, null, null, null );
        assertFalse( v.ok() );
        assertTrue( v.errors().containsKey( "repo" ), v.errors().toString() );
        assertTrue( configStore.get( "gh2" ).isEmpty() );
        assertTrue( runtime.registry().get( "gh2" ).isEmpty() );
    }

    @Test void createRejectsUnknownType() {
        final ConnectorConfigService svc = service( Map.of(), Map.of(), Map.of() );
        final ConnectorConfigCodec.Validation v1 = svc.create( "fs1", "filesystem", json( "{}" ), true, 0, null, null, null );
        assertFalse( v1.ok() );
        assertTrue( v1.errors().containsKey( "connector_type" ) );

        final ConnectorConfigCodec.Validation v2 = svc.create( "fs2", null, json( "{}" ), true, 0, null, null, null );
        assertFalse( v2.ok() );
        assertTrue( v2.errors().containsKey( "connector_type" ) );
    }

    // ---- update ------------------------------------------------------------------------------

    @Test void updatePropertiesOriginThrows() {
        final ConnectorConfigService svc = service(
            Map.of( "legacy", stubConnector( "legacy" ) ), Map.of( "legacy", "feed" ), Map.of() );
        final JsonObject cfg = json( "{\"feed_urls\":[\"https://example.com/feed.xml\"]}" );
        assertThrows( ConnectorConfigService.PropertiesOriginException.class,
            () -> svc.update( "legacy", cfg, true, 0, null, null, null ) );
    }

    // ---- enabled/disabled ------------------------------------------------------------------------

    @Test void disabledRowIsListedButNotRegistered() {
        final ConnectorConfigService svc = service( Map.of(), Map.of(), Map.of() );
        final JsonObject cfg = json( "{\"feed_urls\":[\"https://example.com/feed.xml\"]}" );
        final ConnectorConfigCodec.Validation v = svc.create( "f1", "feed", cfg, false, 0, null, null, null );
        assertTrue( v.ok(), v.errors().toString() );

        assertTrue( svc.list().stream().anyMatch( vw -> vw.id().equals( "f1" ) ) );
        assertTrue( runtime.registry().get( "f1" ).isEmpty() );
    }

    // ---- delete ------------------------------------------------------------------------------

    @Test void deleteKeepStampsOrphansAndPurges() {
        final ConnectorConfigService svc = service( Map.of(), Map.of(), Map.of() );
        final JsonObject cfg = json( "{\"repo\":\"jake/notes\"}" );
        svc.create( "gh3", "github", cfg, true, 0, null, null, null );
        credStore.put( "gh3", "token", "secret-value" );
        syncState.seed( "gh3", "Page1", "Page2" );

        final ConnectorConfigService.DeleteResult result = svc.delete( "gh3", false );

        assertEquals( new ConnectorConfigService.DeleteResult( 2, 0, 1 ), result );
        assertEquals( List.of(), pageDeletes );
        assertEquals( List.of( "Page1", "Page2" ), orphanStamps );
        assertTrue( configStore.get( "gh3" ).isEmpty() );
        assertTrue( runtime.registry().get( "gh3" ).isEmpty() );
        assertTrue( syncState.purged.contains( "gh3" ) );
        assertTrue( credStore.list( "gh3" ).isEmpty() );
    }

    @Test void deleteCascadeDeletesPages() {
        final ConnectorConfigService svc = service( Map.of(), Map.of(), Map.of() );
        final JsonObject cfg = json( "{\"repo\":\"jake/notes\"}" );
        svc.create( "gh4", "github", cfg, true, 0, null, null, null );
        syncState.seed( "gh4", "Page1", "Page2" );

        final ConnectorConfigService.DeleteResult result = svc.delete( "gh4", true );

        assertEquals( new ConnectorConfigService.DeleteResult( 0, 2, 0 ), result );
        assertEquals( List.of( "Page1", "Page2" ), pageDeletes );
        assertEquals( List.of(), orphanStamps );
        assertTrue( configStore.get( "gh4" ).isEmpty() );
    }

    @Test void deletePropertiesOriginThrows() {
        final ConnectorConfigService svc = service(
            Map.of( "legacy", stubConnector( "legacy" ) ), Map.of( "legacy", "feed" ), Map.of() );
        assertThrows( ConnectorConfigService.PropertiesOriginException.class,
            () -> svc.delete( "legacy", false ) );
    }

    // ---- import ------------------------------------------------------------------------------

    @Test void importPersistsPropertiesConnector() {
        final ConnectorConfigService svc = service(
            Map.of( "legacy", stubConnector( "legacy" ) ), Map.of( "legacy", "feed" ), Map.of() );
        final JsonObject cfg = json( "{\"feed_urls\":[\"https://example.com/feed.xml\"]}" );

        final ConnectorConfigCodec.Validation v = svc.importFromProperties( "legacy", cfg );

        assertTrue( v.ok(), v.errors().toString() );
        assertTrue( configStore.get( "legacy" ).isPresent() );
        assertEquals( "db", runtime.registry().originOf( "legacy" ) );
    }

    @Test void importRejectsNonPropertiesId() {
        final ConnectorConfigService svc = service( Map.of(), Map.of(), Map.of() );
        final ConnectorConfigCodec.Validation v = svc.importFromProperties( "nope", json( "{}" ) );
        assertFalse( v.ok() );
        assertTrue( v.errors().containsKey( "connector_id" ) );
    }

    @Test void importRejectsAlreadyImported() {
        final ConnectorConfigService svc = service(
            Map.of( "legacy", stubConnector( "legacy" ) ), Map.of( "legacy", "feed" ), Map.of() );
        final JsonObject cfg = json( "{\"feed_urls\":[\"https://example.com/feed.xml\"]}" );
        svc.importFromProperties( "legacy", cfg );

        final ConnectorConfigCodec.Validation v2 = svc.importFromProperties( "legacy", cfg );
        assertFalse( v2.ok() );
        assertTrue( v2.errors().containsKey( "connector_id" ) );
    }

    // ---- gdrive redirect default + coordinator rebuild ----------------------------------------

    @Test void gdriveCreateDefaultsRedirectUri() {
        props.setProperty( "wikantik.baseURL", "https://w.example" );
        final ConnectorConfigService svc = service( Map.of(), Map.of(), Map.of() );
        final JsonObject cfg = json( "{\"folder_ids\":[\"f1\"],\"client_id\":\"cid\"}" );

        final ConnectorConfigCodec.Validation v = svc.create( "gd1", "gdrive", cfg, true, 0, null, null, null );

        assertTrue( v.ok(), v.errors().toString() );
        final String stored = configStore.get( "gd1" ).orElseThrow().configJson();
        assertTrue( stored.contains( "https://w.example/admin/connector-oauth/gdrive/callback" ), stored );
        assertEquals( 1, installedCoordinators.size() );
        assertNotNull( installedCoordinators.get( 0 ) );
    }

    // ---- rebuild resilience ---------------------------------------------------------------------

    @Test void badDbRowIsSkippedNotFatal() {
        final ConnectorConfigService svc = service( Map.of(), Map.of(), Map.of() );
        // A valid connector first.
        svc.create( "good1", "feed", json( "{\"feed_urls\":[\"https://example.com/feed.xml\"]}" ), true, 0, null, null, null );
        // Hand-insert an invalid row directly (bypasses create()'s validation).
        configStore.upsert( new ConnectorConfigRow( "bad-gh", "github", true, 0, "{}", null, null, null ) );

        svc.rebuild();

        assertTrue( runtime.registry().get( "good1" ).isPresent(), "good connector must survive a bad sibling row" );
        assertTrue( runtime.registry().get( "bad-gh" ).isEmpty(), "invalid row must not produce a registry entry" );
    }

    // ---- fakes -------------------------------------------------------------------------------

    private static final class FakeCredentialStore implements CredentialStore {
        private final Map< String, Map< String, String > > data = new LinkedHashMap<>();

        @Override public boolean enabled() { return true; }

        @Override public void put( final String connectorId, final String name, final String secret ) {
            data.computeIfAbsent( connectorId, k -> new LinkedHashMap<>() ).put( name, secret );
        }

        @Override public Optional< String > get( final String connectorId, final String name ) {
            return Optional.ofNullable( data.getOrDefault( connectorId, Map.of() ).get( name ) );
        }

        @Override public List< String > list( final String connectorId ) {
            return new ArrayList<>( data.getOrDefault( connectorId, Map.of() ).keySet() );
        }

        @Override public void delete( final String connectorId, final String name ) {
            final Map< String, String > m = data.get( connectorId );
            if ( m != null ) m.remove( name );
        }
    }

    private static final class FakeSyncStateStore implements SyncStateStore {
        private final Map< String, List< SyncedItem > > byConnector = new LinkedHashMap<>();
        private final List< String > purged = new ArrayList<>();

        void seed( final String connectorId, final String... pageNames ) {
            final List< SyncedItem > items = new ArrayList<>();
            for ( final String page : pageNames ) items.add( new SyncedItem( "uri:" + page, page, Instant.now() ) );
            byConnector.put( connectorId, items );
        }

        @Override public Optional< SyncCursor > loadCursor( final String connectorId ) { return Optional.empty(); }
        @Override public void saveCursor( final String connectorId, final SyncCursor cursor ) { }
        @Override public Optional< String > syncedHash( final String connectorId, final String sourceUri ) { return Optional.empty(); }
        @Override public void recordSynced( final String connectorId, final String sourceUri, final String contentHash,
                final String pageName, final List< String > aclRefs ) { }
        @Override public Optional< String > pageNameFor( final String connectorId, final String sourceUri ) { return Optional.empty(); }
        @Override public List< String > knownUris( final String connectorId ) { return List.of(); }
        @Override public void removeSynced( final String connectorId, final String sourceUri ) { }

        @Override public void purge( final String connectorId ) {
            purged.add( connectorId );
            byConnector.remove( connectorId );
        }

        @Override public List< SyncedItem > items( final String connectorId ) {
            return byConnector.getOrDefault( connectorId, List.of() );
        }
    }
}
