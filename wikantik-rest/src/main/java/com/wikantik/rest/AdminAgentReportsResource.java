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
package com.wikantik.rest;

import com.wikantik.agentreports.AgentReport;
import com.wikantik.agentreports.AgentReportService;
import com.wikantik.agentreports.AgentReportView;
import com.wikantik.agentreports.ReportQuery;
import com.wikantik.agentreports.ReportQueryParser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin REST view of the agent field reports: {@code GET /admin/agent-reports} with the same filters as the
 * {@code list_agent_reports} MCP tool ({@code since}, {@code until}, {@code kind_of_work}, {@code product},
 * {@code client}, {@code skill}, {@code outcome}, {@code limit}). Pre-authorized by {@link AdminAuthFilter}.
 */
public class AdminAgentReportsResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminAgentReportsResource.class );

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final AgentReportService service =
                getEngine() instanceof com.wikantik.WikiEngine we ? we.agentReportService() : null;
        if ( service == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "report store unavailable" );
            return;
        }
        final ReportQuery query;
        try {
            query = ReportQueryParser.parse( request::getParameter, Clock.systemUTC() );
        } catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage() );
            return;
        }
        final List< AgentReport > reports;
        try {
            reports = service.list( query );
        } catch ( final RuntimeException e ) {
            LOG.warn( "GET /admin/agent-reports failed: {}", e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to list agent reports" );
            return;
        }
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "reports", AgentReportView.toMaps( reports ) );
        out.put( "count", reports.size() );
        sendJson( response, out );
    }
}
