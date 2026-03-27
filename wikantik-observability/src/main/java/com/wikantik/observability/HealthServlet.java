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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wikantik.observability.health.HealthCheck;
import com.wikantik.observability.health.HealthResult;
import com.wikantik.observability.health.HealthStatus;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Health check endpoint that reports the status of all registered health checks.
 * Returns HTTP 200 when all checks pass, HTTP 503 when any check is DOWN.
 *
 * <p>Health check instances are stored in the ServletContext by
 * {@link ObservabilityLifecycleExtension} under the key {@value #HEALTH_CHECKS_ATTR}.</p>
 */
public class HealthServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    static final String HEALTH_CHECKS_ATTR = "com.wikantik.observability.healthChecks";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );

        @SuppressWarnings( "unchecked" )
        final List<HealthCheck> checks = (List<HealthCheck>) getServletContext().getAttribute( HEALTH_CHECKS_ATTR );

        if ( checks == null || checks.isEmpty() ) {
            response.setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
            response.getWriter().write( GSON.toJson( Map.of(
                    "status", HealthStatus.DOWN.name(),
                    "checks", Map.of(),
                    "error", "No health checks registered — engine may not have started"
            ) ) );
            return;
        }

        final Map<String, Object> checksMap = new LinkedHashMap<>();
        HealthStatus overall = HealthStatus.UP;

        for ( final HealthCheck check : checks ) {
            final HealthResult result = check.check();
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put( "status", result.status().name() );
            if ( result.responseTimeMs() >= 0 ) {
                entry.put( "responseTimeMs", result.responseTimeMs() );
            }
            if ( !result.detail().isEmpty() ) {
                entry.putAll( result.detail() );
            }
            checksMap.put( check.name(), entry );

            if ( result.status() == HealthStatus.DOWN ) {
                overall = HealthStatus.DOWN;
            } else if ( result.status() == HealthStatus.DEGRADED && overall == HealthStatus.UP ) {
                overall = HealthStatus.DEGRADED;
            }
        }

        final Map<String, Object> body = new LinkedHashMap<>();
        body.put( "status", overall.name() );
        body.put( "checks", checksMap );

        response.setStatus( overall == HealthStatus.DOWN
                ? HttpServletResponse.SC_SERVICE_UNAVAILABLE
                : HttpServletResponse.SC_OK );
        response.getWriter().write( GSON.toJson( body ) );
    }

}
