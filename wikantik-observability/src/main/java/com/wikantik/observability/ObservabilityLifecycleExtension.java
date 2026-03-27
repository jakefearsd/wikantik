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
package com.wikantik.observability;

import com.wikantik.api.core.Engine;
import com.wikantik.api.engine.EngineLifecycleExtension;
import com.wikantik.observability.health.DatabaseHealthCheck;
import com.wikantik.observability.health.EngineHealthCheck;
import com.wikantik.observability.health.HealthCheck;
import com.wikantik.observability.health.SearchIndexHealthCheck;

import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import jakarta.servlet.ServletContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Properties;

/**
 * Engine lifecycle extension that initializes the observability subsystem:
 * <ul>
 *   <li>Creates a Prometheus meter registry with JVM metrics</li>
 *   <li>Registers wiki-specific event-driven metrics</li>
 *   <li>Creates and registers health check providers</li>
 *   <li>Stores the registry and health checks in the ServletContext for servlets to use</li>
 * </ul>
 */
public class ObservabilityLifecycleExtension implements EngineLifecycleExtension {

    private static final Logger LOG = LogManager.getLogger( ObservabilityLifecycleExtension.class );

    private static final String PROP_DB_DATASOURCE = "wikantik.userdatabase.datasource";
    private static final String DEFAULT_DB_DATASOURCE = "jdbc/UserDatabase";

    private PrometheusMeterRegistry registry;
    private JvmGcMetrics jvmGcMetrics;

    @Override
    public void onStart( final Engine engine, final Properties properties ) {
        LOG.info( "Initializing observability subsystem" );

        // Meter registry
        registry = new PrometheusMeterRegistry( PrometheusConfig.DEFAULT );
        new JvmMemoryMetrics().bindTo( registry );
        jvmGcMetrics = new JvmGcMetrics();
        jvmGcMetrics.bindTo( registry );
        new JvmThreadMetrics().bindTo( registry );
        new ClassLoaderMetrics().bindTo( registry );

        // Wiki-specific event-driven metrics
        new WikiMetrics( registry, engine );

        // Health checks
        final String datasource = properties.getProperty( PROP_DB_DATASOURCE, DEFAULT_DB_DATASOURCE );
        final List<HealthCheck> healthChecks = List.of(
                new EngineHealthCheck( engine ),
                new DatabaseHealthCheck( datasource ),
                new SearchIndexHealthCheck( engine )
        );

        // Store in ServletContext for HealthServlet and MetricsServlet
        final ServletContext ctx = engine.getServletContext();
        if ( ctx != null ) {
            ctx.setAttribute( HealthServlet.HEALTH_CHECKS_ATTR, healthChecks );
            ctx.setAttribute( MetricsServlet.REGISTRY_ATTR, registry );
            LOG.info( "Observability initialized: {} health checks, Prometheus metrics at /metrics",
                    healthChecks.size() );
        } else {
            LOG.warn( "ServletContext not available — health checks and metrics servlets will not work" );
        }
    }

    @Override
    public void onShutdown( final Engine engine, final Properties properties ) {
        if ( jvmGcMetrics != null ) {
            jvmGcMetrics.close();
        }
        if ( registry != null ) {
            registry.close();
        }
        LOG.info( "Observability subsystem shut down" );
    }

}
