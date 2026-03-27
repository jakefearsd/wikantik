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

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Serves Prometheus-format metrics at {@code /metrics}. The meter registry is stored
 * in the ServletContext by {@link ObservabilityLifecycleExtension}.
 */
public class MetricsServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    static final String REGISTRY_ATTR = "com.wikantik.observability.meterRegistry";

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        final PrometheusMeterRegistry registry =
                (PrometheusMeterRegistry) getServletContext().getAttribute( REGISTRY_ATTR );

        if ( registry == null ) {
            response.setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
            response.setContentType( "text/plain" );
            response.getWriter().write( "# Metrics not available — engine may not have started\n" );
            return;
        }

        response.setContentType( "text/plain; version=0.0.4; charset=utf-8" );
        response.getWriter().write( registry.scrape() );
    }

}
