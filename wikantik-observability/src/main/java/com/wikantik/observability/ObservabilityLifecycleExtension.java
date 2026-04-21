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
import com.wikantik.api.observability.MeterRegistryHolder;
import com.wikantik.observability.health.DatabaseHealthCheck;
import com.wikantik.observability.health.EngineHealthCheck;
import com.wikantik.observability.health.HealthCheck;
import com.wikantik.observability.health.SearchIndexHealthCheck;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

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

    private static final String PROP_DB_DATASOURCE = "wikantik.datasource";
    private static final String DEFAULT_DB_DATASOURCE = "jdbc/WikiDatabase";

    /**
     * Process-wide shared registry, created in {@link #onInit} so engine code
     * that runs during {@code initialize()} (e.g. {@code WikiEngine.initKnowledgeGraph})
     * can register meters against the same registry later scraped by
     * {@link MetricsServlet}. Public getter is {@link #getSharedRegistry()}.
     */
    private static volatile PrometheusMeterRegistry sharedRegistry;

    private PrometheusMeterRegistry registry;
    private JvmGcMetrics jvmGcMetrics;

    /**
     * Returns the process-wide Prometheus registry that will be scraped at
     * {@code /observability/metrics}, or {@code null} if the observability
     * extension has not yet run {@code onInit} (e.g. test contexts that bypass
     * {@code Engine.start()}). Callers should fall back to a local
     * {@code SimpleMeterRegistry} with a WARN when this returns null.
     */
    @SuppressFBWarnings( value = "MS_EXPOSE_REP",
            justification = "Exposing the process-wide Prometheus registry is the contract of this holder method; callers scrape or bind meters to the same shared instance." )
    public static MeterRegistry getSharedRegistry() {
        return sharedRegistry;
    }

    /**
     * Lazily create the shared registry and bind JVM metrics to it. Synchronizes
     * on the class monitor because the registry is a static field and the
     * extension instance is, in principle, callable from multiple engine host
     * threads (test harnesses that tear down and recreate engines are the
     * realistic trigger). Callers hold the instance-level {@link #jvmGcMetrics}
     * reference so shutdown can close the binder cleanly.
     */
    private void ensureSharedRegistry() {
        synchronized ( ObservabilityLifecycleExtension.class ) {
            if ( sharedRegistry == null ) {
                final PrometheusMeterRegistry created = new PrometheusMeterRegistry( PrometheusConfig.DEFAULT );
                new JvmMemoryMetrics().bindTo( created );
                jvmGcMetrics = new JvmGcMetrics();
                jvmGcMetrics.bindTo( created );
                new JvmThreadMetrics().bindTo( created );
                new ClassLoaderMetrics().bindTo( created );
                sharedRegistry = created;
                MeterRegistryHolder.set( created );
            }
        }
    }

    @Override
    public void onInit( final Properties properties ) {
        // Create the registry early so components that wire up during
        // Engine#initialize() (notably the knowledge-graph chunker and rebuild
        // service) can register meters against the same registry that later
        // gets scraped at /observability/metrics. JVM binders are attached
        // here too so they publish regardless of onStart ordering.
        ensureSharedRegistry();
        LOG.info( "Shared Prometheus meter registry ready after onInit" );
    }

    @Override
    public void onStart( final Engine engine, final Properties properties ) {
        LOG.info( "Initializing observability subsystem" );

        // Reuse the registry created in onInit. If onInit was somehow skipped
        // (unexpected host lifecycle), create one here as a safety net.
        if ( sharedRegistry == null ) {
            LOG.warn( "onStart reached without onInit — creating registry late; "
                    + "meters registered during Engine#initialize will not publish to /metrics" );
            ensureSharedRegistry();
        }
        registry = sharedRegistry;

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
    @SuppressFBWarnings( value = "ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD",
            justification = "Clearing the process-wide shared registry on engine shutdown from the lifecycle instance is intentional and guarded by the class monitor." )
    public void onShutdown( final Engine engine, final Properties properties ) {
        if ( jvmGcMetrics != null ) {
            jvmGcMetrics.close();
        }
        if ( registry != null ) {
            registry.close();
        }
        // Clear the static holder under the class monitor so a subsequent
        // engine restart (e.g. in integration tests that tear down and
        // re-create the container) does not race with ensureSharedRegistry()
        // and does not leave a closed registry in place.
        synchronized ( ObservabilityLifecycleExtension.class ) {
            sharedRegistry = null;
            MeterRegistryHolder.clear();
        }
        LOG.info( "Observability subsystem shut down" );
    }

}
