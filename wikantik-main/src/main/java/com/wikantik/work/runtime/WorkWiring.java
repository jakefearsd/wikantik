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
package com.wikantik.work.runtime;

import com.wikantik.api.core.Engine;
import com.wikantik.api.observability.MeterRegistryHolder;
import com.wikantik.work.JdbcWorkStore;
import com.wikantik.work.WorkMetrics;
import com.wikantik.work.WorkService;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Builds the {@link WorkService} at startup, following the build-once-return-null-when-off pattern of
 * {@code InsightsWiring}; see {@code WikiEngine#initDataSourceSubsystems} for the call site.
 */
public final class WorkWiring {

    private static final Logger LOG = LogManager.getLogger( WorkWiring.class );

    /** The feature flag; default {@code false}. */
    public static final String PROP_ENABLED = "wikantik.work.enabled";

    private WorkWiring() {
    }

    /**
     * @return the wired service, or {@code null} when {@code wikantik.work.enabled} is false or {@code ds} is
     *         {@code null}; callers no-op on {@code null}
     */
    public static WorkService build( final DataSource ds, final Properties props, final Engine engine ) {
        if ( !Boolean.parseBoolean( props.getProperty( PROP_ENABLED, "false" ).trim() ) ) {
            return null;
        }
        if ( ds == null ) {
            LOG.warn( "{} is true but no datasource is available -- work management is not started", PROP_ENABLED );
            return null;
        }
        final JdbcWorkStore store = new JdbcWorkStore( ds );
        final MeterRegistry registry = MeterRegistryHolder.get();
        Consumer< com.wikantik.work.Action > onDenied = action -> { };
        if ( registry != null ) {
            final WorkMetrics metrics = new WorkMetrics( registry, store );
            onDenied = metrics::denied;
        }
        final WorkService service = new WorkService( store, new WikiWorkRenderer( engine ),
            new WikiWorkDirectory( engine ), Clock.systemUTC(), onDenied );
        LOG.info( "Work management started ({}=true, metrics {})", PROP_ENABLED, registry != null ? "on" : "off" );
        return service;
    }
}
