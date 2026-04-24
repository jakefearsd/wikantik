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
package com.wikantik.mcp;

import com.wikantik.api.core.Engine;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.apikeys.ApiKeyServiceHolder;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.EnumSet;

/**
 * Bootstraps the servlet-container side of an MCP endpoint: builds a shared
 * {@link McpAccessFilter} with rate limiting + API-key resolution, registers it
 * ahead of the transport servlet, then creates the streamable HTTP transport
 * and wires it into the {@link ServletContext}.
 *
 * <p>Both {@code McpServerInitializer} (admin endpoint) and
 * {@code KnowledgeMcpInitializer} (read-only knowledge endpoint) share the same
 * access-filter + transport registration sequence — this builder centralises
 * the boilerplate so the two listeners only have to configure the endpoint URL,
 * filter/servlet names, and servlet load order.</p>
 *
 * <p>Callers are expected to invoke {@link #registerAccessFilter(ServletContext)}
 * and {@link #registerTransport(ServletContext)} in that order, each inside its
 * own phase-scoped try-catch so distinct log messages can pinpoint which phase
 * failed at startup.</p>
 */
public final class McpEndpointBootstrapper {

    private static final Logger LOG = LogManager.getLogger( McpEndpointBootstrapper.class );

    private final String logTag;
    private final String endpointPath;
    private final String filterName;
    private final String servletName;
    private final int loadOnStartup;
    private final Engine engine;

    private McpEndpointBootstrapper( final Builder b ) {
        this.logTag = b.logTag;
        this.endpointPath = b.endpointPath;
        this.filterName = b.filterName;
        this.servletName = b.servletName;
        this.loadOnStartup = b.loadOnStartup;
        this.engine = b.engine;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builds a fresh {@link McpAccessFilter} (loading {@link McpConfig}, the
     * shared {@link McpRateLimiter}, and an optional {@link ApiKeyService})
     * and registers it at {@link #endpointPath} on the supplied
     * {@link ServletContext}. Must run before {@link #registerTransport} so
     * the filter is fronting the transport servlet on first request.
     */
    public void registerAccessFilter( final ServletContext servletContext ) {
        final McpConfig config = new McpConfig();
        LOG.info( "{}: config loaded — name={}, title={}, version={}, instructions={}",
                logTag, config.serverName(), config.serverTitle(), config.serverVersion(),
                config.instructions() != null ? config.instructions().length() + " chars" : "none" );

        final McpRateLimiter rateLimiter = new McpRateLimiter(
                config.rateLimitGlobal(), config.rateLimitPerClient() );
        LOG.info( "{}: rate limiting — global={}/s, perClient={}/s",
                logTag, config.rateLimitGlobal(), config.rateLimitPerClient() );

        final ApiKeyService apiKeyService = ApiKeyServiceHolder.get( engine.getWikiProperties() );
        if ( apiKeyService != null ) {
            LOG.info( "{}: DB-backed API keys enabled — bearer tokens resolve to principals.", logTag );
        } else {
            LOG.info( "{}: DB-backed API keys unavailable (no datasource) — legacy property keys only.", logTag );
        }

        final McpAccessFilter accessFilter = new McpAccessFilter( config, rateLimiter, apiKeyService );
        final FilterRegistration.Dynamic filterReg =
                servletContext.addFilter( filterName, accessFilter );
        filterReg.addMappingForUrlPatterns( EnumSet.of( DispatcherType.REQUEST ), false, endpointPath );
        filterReg.setAsyncSupported( true );
    }

    /**
     * Creates the streamable HTTP transport provider and registers it as a
     * servlet at {@link #endpointPath} on the supplied {@link ServletContext}.
     * Returns the provider so the caller can hand it to
     * {@code McpServer.sync(...)} when building the server.
     */
    public HttpServletStreamableServerTransportProvider registerTransport( final ServletContext servletContext ) {
        final HttpServletStreamableServerTransportProvider transportProvider =
                HttpServletStreamableServerTransportProvider.builder()
                        .mcpEndpoint( endpointPath )
                        .build();
        final ServletRegistration.Dynamic registration =
                servletContext.addServlet( servletName, transportProvider );
        registration.addMapping( endpointPath );
        registration.setAsyncSupported( true );
        registration.setLoadOnStartup( loadOnStartup );
        return transportProvider;
    }

    public static final class Builder {
        private String logTag;
        private String endpointPath;
        private String filterName;
        private String servletName;
        private int loadOnStartup = 2;
        private Engine engine;

        private Builder() { }

        /** Short identifier used in log messages (e.g. "MCP" or "Knowledge MCP"). */
        public Builder logTag( final String value ) { this.logTag = value; return this; }

        /** The servlet-context path that both the filter and transport servlet map to. */
        public Builder endpointPath( final String value ) { this.endpointPath = value; return this; }

        /** Unique name for the access-control filter registration. */
        public Builder filterName( final String value ) { this.filterName = value; return this; }

        /** Unique name for the transport servlet registration. */
        public Builder servletName( final String value ) { this.servletName = value; return this; }

        /** {@code load-on-startup} ordering — lower numbers start earlier. */
        public Builder loadOnStartup( final int value ) { this.loadOnStartup = value; return this; }

        /** Engine used to resolve {@link ApiKeyService} from the wiki properties. */
        public Builder engine( final Engine value ) { this.engine = value; return this; }

        public McpEndpointBootstrapper build() {
            if ( logTag == null || endpointPath == null || filterName == null
                    || servletName == null || engine == null ) {
                throw new IllegalStateException(
                    "logTag, endpointPath, filterName, servletName, and engine are required" );
            }
            return new McpEndpointBootstrapper( this );
        }
    }
}
