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
package com.wikantik.tools;

import com.wikantik.api.core.Engine;
import com.wikantik.api.observability.MeterRegistryHolder;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.apikeys.ApiKeyServiceHolder;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRegistration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.EnumSet;

/**
 * Bootstraps the OpenAPI tool server on application startup.
 *
 * <p>Registers the access filter at {@code /tools/*}, scans the page corpus for view ACLs
 * so operators must acknowledge the bypass risk before keys can query restricted content,
 * and registers the dispatcher servlet with the same URL pattern.</p>
 */
public class ToolsServerInitializer implements ServletContextListener {

    private static final Logger LOG = LogManager.getLogger( ToolsServerInitializer.class );
    private static final String URL_PATTERN = "/tools/*";

    @Override
    public void contextInitialized( final ServletContextEvent sce ) {
        final ServletContext servletContext = sce.getServletContext();

        Engine engine = null;
        try {
            engine = Wiki.engine().find( servletContext, null );
        } catch ( final Exception e ) {
            LOG.warn( "WikiEngine could not be created — tool server will serve a placeholder spec only: {}",
                    e.getMessage() );
        }

        try {
            final ToolsConfig config = new ToolsConfig();
            final ToolsRateLimiter rateLimiter = new ToolsRateLimiter(
                    config.rateLimitGlobal(), config.rateLimitPerClient() );
            LOG.info( "Tools rate limiting: global={}/s, perClient={}/s",
                    config.rateLimitGlobal(), config.rateLimitPerClient() );

            final ApiKeyService apiKeyService = engine != null
                    ? ApiKeyServiceHolder.get( engine.getWikiProperties() )
                    : null;
            if ( apiKeyService != null ) {
                LOG.info( "Tool server: DB-backed API keys enabled — bearer tokens resolve to principals." );
            } else {
                LOG.info( "Tool server: DB-backed API keys unavailable (no datasource) — legacy property keys only." );
            }
            final ToolsAccessFilter accessFilter = new ToolsAccessFilter( config, rateLimiter, apiKeyService );
            final FilterRegistration.Dynamic filterReg =
                    servletContext.addFilter( "ToolsAccessFilter", accessFilter );
            filterReg.addMappingForUrlPatterns( EnumSet.of( DispatcherType.REQUEST ), false, URL_PATTERN );
            filterReg.setAsyncSupported( true );

            final ToolsMetrics metrics = new ToolsMetrics();
            ToolsMetricsBridge.register( MeterRegistryHolder.get(), metrics );

            final ToolsOpenApiServlet servlet = new ToolsOpenApiServlet( engine, config, metrics );
            final ServletRegistration.Dynamic registration =
                    servletContext.addServlet( "ToolsOpenApiServlet", servlet );
            registration.addMapping( URL_PATTERN );
            registration.setAsyncSupported( true );
            registration.setLoadOnStartup( 2 );

            LOG.info( "Tool server listening at /tools/ (engine={})", engine != null );
        } catch ( final Exception e ) {
            // Justified LOG.error: startup-time misconfiguration silently hides the tool server; operators must see it.
            LOG.error( "Failed to start tool server: {}", e.getMessage(), e );
        }
    }

    @Override
    public void contextDestroyed( final ServletContextEvent sce ) {
        // No resources to close — tool server is stateless.
    }
}
