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

import com.wikantik.WikiEngine;
import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.connectors.SyncOrchestrator;
import com.wikantik.connectors.filesystem.FilesystemSourceConnector;
import com.wikantik.connectors.runtime.ConnectorRegistry;
import com.wikantik.connectors.runtime.ConnectorRuntime;
import com.wikantik.connectors.runtime.ConnectorStatusReader;
import com.wikantik.connectors.state.JdbcSyncStateStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/** Thin startup wiring: builds the connector runtime from config and registers it. No-op unless
 *  {@code wikantik.connectors.enabled=true}. Lives in the existing derived package (invariant #6).
 *  Named {@code *WiringHelper} so the decomposition ArchUnit rule permits engine access; it calls
 *  only {@code setManager} (never {@code getManager}), mirroring {@code OntologyWiringHelper}. */
public final class ConnectorWiringHelper {

    private static final Logger LOG = LogManager.getLogger( ConnectorWiringHelper.class );
    private static final String PREFIX = "wikantik.connectors.";

    private ConnectorWiringHelper() {}

    public static Optional< ConnectorRuntime > wireConnectors( final WikiEngine engine, final Properties props,
            final DataSource ds, final PageManager pm, final AttachmentManager am ) {
        if ( !Boolean.parseBoolean( props.getProperty( PREFIX + "enabled", "false" ) ) ) {
            return Optional.empty();
        }
        final Map< String, String > roots = filesystemRoots( props );
        if ( roots.isEmpty() ) {
            LOG.info( "connectors enabled but no wikantik.connectors.filesystem.*.root configured — nothing to sync" );
            return Optional.empty();
        }
        final Map< String, SourceConnector > byId = new LinkedHashMap<>();
        final Map< String, String > typeById = new LinkedHashMap<>();
        for ( final Map.Entry< String, String > e : roots.entrySet() ) {
            byId.put( e.getKey(), new FilesystemSourceConnector( e.getKey(), Path.of( e.getValue() ) ) );
            typeById.put( e.getKey(), "filesystem" );
        }
        final DerivedPageIngestionService ingestion = DerivedIngestionServiceFactory.build( engine, pm, am );
        final DerivedPageSinkAdapter sink = new DerivedPageSinkAdapter( ingestion, pm::deletePage, "connector-sync" );
        final SyncOrchestrator orchestrator = new SyncOrchestrator( new JdbcSyncStateStore( ds ), sink );
        final ConnectorRuntime runtime = new ConnectorRuntime(
            new ConnectorRegistry( byId, typeById ), orchestrator, new ConnectorStatusReader( ds ) );

        engine.setManager( ConnectorRuntime.class, runtime );
        final long intervalHours = parseLong( props, "sync.interval.hours", 0L );
        runtime.startScheduler( intervalHours );
        LOG.info( "connector runtime wired: {} connector(s), scheduler interval {}h", roots.size(), intervalHours );
        return Optional.of( runtime );
    }

    /** id → root for every {@code wikantik.connectors.filesystem.<id>.root} key. Package-visible for testing. */
    static Map< String, String > filesystemRoots( final Properties props ) {
        final String p = PREFIX + "filesystem.";
        final Map< String, String > out = new LinkedHashMap<>();
        for ( final String key : props.stringPropertyNames() ) {
            if ( key.startsWith( p ) && key.endsWith( ".root" ) ) {
                final String id = key.substring( p.length(), key.length() - ".root".length() );
                if ( !id.isBlank() && !id.contains( "." ) ) out.put( id, props.getProperty( key ).trim() );
            }
        }
        return out;
    }

    private static long parseLong( final Properties props, final String suffix, final long def ) {
        try { return Long.parseLong( props.getProperty( PREFIX + suffix, String.valueOf( def ) ).trim() ); }
        catch ( final NumberFormatException e ) { return def; }
    }
}
