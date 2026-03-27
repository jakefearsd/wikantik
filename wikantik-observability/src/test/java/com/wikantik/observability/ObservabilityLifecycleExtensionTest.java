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
import com.wikantik.observability.health.HealthCheck;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import jakarta.servlet.ServletContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class ObservabilityLifecycleExtensionTest {

    @Mock private Engine engine;
    @Mock private ServletContext servletContext;

    @Test
    void onStartRegistersHealthChecksAndMetricsInServletContext() {
        when( engine.getServletContext() ).thenReturn( servletContext );

        final ObservabilityLifecycleExtension ext = new ObservabilityLifecycleExtension();
        ext.onStart( engine, new Properties() );

        // Verify health checks were stored
        final ArgumentCaptor<List> healthCaptor = ArgumentCaptor.forClass( List.class );
        verify( servletContext ).setAttribute( eq( HealthServlet.HEALTH_CHECKS_ATTR ), healthCaptor.capture() );
        @SuppressWarnings( "unchecked" )
        final List<HealthCheck> checks = healthCaptor.getValue();
        assertEquals( 3, checks.size() );
        assertEquals( "engine", checks.get( 0 ).name() );
        assertEquals( "database", checks.get( 1 ).name() );
        assertEquals( "searchIndex", checks.get( 2 ).name() );

        // Verify meter registry was stored
        verify( servletContext ).setAttribute( eq( MetricsServlet.REGISTRY_ATTR ), any( PrometheusMeterRegistry.class ) );

        // Clean up
        ext.onShutdown( engine, new Properties() );
    }

    @Test
    void onStartUsesCustomDatasourceFromProperties() {
        when( engine.getServletContext() ).thenReturn( servletContext );

        final Properties props = new Properties();
        props.setProperty( "wikantik.userdatabase.datasource", "jdbc/CustomDB" );

        final ObservabilityLifecycleExtension ext = new ObservabilityLifecycleExtension();
        ext.onStart( engine, props );

        final ArgumentCaptor<List> healthCaptor = ArgumentCaptor.forClass( List.class );
        verify( servletContext ).setAttribute( eq( HealthServlet.HEALTH_CHECKS_ATTR ), healthCaptor.capture() );
        // DatabaseHealthCheck is created with the custom JNDI name — verifying it's present
        @SuppressWarnings( "unchecked" )
        final List<HealthCheck> checks = healthCaptor.getValue();
        assertEquals( "database", checks.get( 1 ).name() );

        ext.onShutdown( engine, new Properties() );
    }

    @Test
    void onStartRegistersJvmMetrics() {
        when( engine.getServletContext() ).thenReturn( servletContext );

        final ObservabilityLifecycleExtension ext = new ObservabilityLifecycleExtension();
        ext.onStart( engine, new Properties() );

        // Capture the registry and verify JVM metrics are registered
        final ArgumentCaptor<PrometheusMeterRegistry> captor =
                ArgumentCaptor.forClass( PrometheusMeterRegistry.class );
        verify( servletContext ).setAttribute( eq( MetricsServlet.REGISTRY_ATTR ), captor.capture() );

        final String scrape = captor.getValue().scrape();
        assertTrue( scrape.contains( "jvm_memory" ), "Should contain JVM memory metrics" );
        assertTrue( scrape.contains( "jvm_threads" ), "Should contain JVM thread metrics" );
        assertTrue( scrape.contains( "jvm_classes" ), "Should contain classloader metrics" );

        ext.onShutdown( engine, new Properties() );
    }

    @Test
    void onStartRegistersWikiEventMetrics() {
        when( engine.getServletContext() ).thenReturn( servletContext );

        final ObservabilityLifecycleExtension ext = new ObservabilityLifecycleExtension();
        ext.onStart( engine, new Properties() );

        final ArgumentCaptor<PrometheusMeterRegistry> captor =
                ArgumentCaptor.forClass( PrometheusMeterRegistry.class );
        verify( servletContext ).setAttribute( eq( MetricsServlet.REGISTRY_ATTR ), captor.capture() );

        final String scrape = captor.getValue().scrape();
        assertTrue( scrape.contains( "wikantik_page_views" ), "Should contain page views counter" );
        assertTrue( scrape.contains( "wikantik_page_edits" ), "Should contain page edits counter" );
        assertTrue( scrape.contains( "wikantik_auth_logins" ), "Should contain auth logins counter" );

        ext.onShutdown( engine, new Properties() );
    }

    @Test
    void onStartHandlesNullServletContext() {
        when( engine.getServletContext() ).thenReturn( null );

        final ObservabilityLifecycleExtension ext = new ObservabilityLifecycleExtension();
        // Should not throw — just logs a warning
        assertDoesNotThrow( () -> ext.onStart( engine, new Properties() ) );

        // No attributes stored since context is null
        verify( servletContext, never() ).setAttribute( anyString(), any() );

        ext.onShutdown( engine, new Properties() );
    }

    @Test
    void onShutdownIsIdempotentWhenCalledBeforeStart() {
        final ObservabilityLifecycleExtension ext = new ObservabilityLifecycleExtension();
        // Should not throw when called without onStart
        assertDoesNotThrow( () -> ext.onShutdown( engine, new Properties() ) );
    }

    @Test
    void onShutdownClosesRegistryAndGcMetrics() {
        when( engine.getServletContext() ).thenReturn( servletContext );

        final ObservabilityLifecycleExtension ext = new ObservabilityLifecycleExtension();
        ext.onStart( engine, new Properties() );

        // Capture the registry before shutdown
        final ArgumentCaptor<PrometheusMeterRegistry> captor =
                ArgumentCaptor.forClass( PrometheusMeterRegistry.class );
        verify( servletContext ).setAttribute( eq( MetricsServlet.REGISTRY_ATTR ), captor.capture() );
        final PrometheusMeterRegistry reg = captor.getValue();

        ext.onShutdown( engine, new Properties() );

        assertTrue( reg.isClosed(), "Registry should be closed after shutdown" );
    }

}
