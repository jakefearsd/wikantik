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
package com.wikantik.its.rest;

import com.wikantik.its.rest.WorkIt.Caller;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.wikantik.its.rest.WorkIt.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code file_agent_report}, the knowledge endpoint's one write, over the wire with a read-scope key: it is listed,
 * the review tool of the admin side is not on this endpoint, and a call is refused when it cannot be attributed.
 */
public class AgentReportFilingIT {

    private static McpSyncClient mcp;
    private static String readKey;

    @BeforeAll
    static void setUp() throws Exception {
        final Caller admin = WorkIt.admin();
        final Caller person = WorkIt.account( admin, "arfiler", "Report Filer" );
        final HttpResponse< String > minted = person.send( "POST", "/api/self/apikeys",
            Map.of( "label", "report-filer-it", "scope", "mcp_read" ) );
        assertEquals( 201, minted.statusCode(), minted.body() );
        readKey = json( minted ).getAsJsonObject().get( "token" ).getAsString();

        final String base = WorkIt.baseUrl();
        final HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
            .builder( base.endsWith( "/" ) ? base : base + "/" ).endpoint( "knowledge-mcp" )
            .connectTimeout( Duration.ofSeconds( 15 ) )
            .addHttpRequestCustomizer( ( rb, method, uri, body, context ) ->
                rb.header( "Authorization", "Bearer " + readKey ) )
            .build();
        mcp = McpClient.sync( transport )
            .clientInfo( new McpSchema.Implementation( "wikantik-it-report-filer", "1.0.0" ) )
            .requestTimeout( Duration.ofSeconds( 30 ) ).initializationTimeout( Duration.ofSeconds( 30 ) ).build();
        mcp.initialize();
    }

    @AfterAll
    static void tearDown() {
        if ( mcp != null ) {
            mcp.close();
        }
    }

    private static String text( final McpSchema.CallToolResult r ) {
        return ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
    }

    @Test
    void toolListHasFileAgentReportAndNoReviewTool() {
        final Set< String > names = mcp.listTools().tools().stream().map( McpSchema.Tool::name )
            .collect( Collectors.toSet() );
        assertTrue( names.contains( "file_agent_report" ), names.toString() );
        assertFalse( names.contains( "list_agent_reports" ), "reading reports is an admin-side tool: " + names );
    }

    /**
     * This module runs {@code /knowledge-mcp} with {@code mcp.access.allowUnrestricted=true}: the access filter then
     * admits any caller without resolving its key, so no owner is known and the tool must refuse rather than store a
     * report without one. That is the fail-closed half; the key-to-owner chain and the scope refusal on the admin
     * endpoint are proven against the real filter in {@code FileAgentReportToolTest}.
     */
    @Test
    void aCallWithNoResolvableKeyOwnerIsRefusedAndNothingIsFiled() {
        final McpSchema.CallToolResult r = mcp.callTool( new McpSchema.CallToolRequest( "file_agent_report", Map.of(
            "kind_of_work", "build", "task", "Wire the filing IT", "outcome", "completed",
            "client", "spoofed", "filed_by", "someone-else" ) ) );
        assertTrue( Boolean.TRUE.equals( r.isError() ), text( r ) );
        assertTrue( text( r ).contains( "API key owner" ), text( r ) );
    }

    @Test
    void aMalformedCallIsRejectedBySchemaValidation() {
        // The MCP SDK's JSON-schema validation rejects this before the tool's own validator runs.
        final McpSchema.CallToolResult r = mcp.callTool( new McpSchema.CallToolRequest( "file_agent_report", Map.of(
            "kind_of_work", "build", "task", "x", "outcome", "completed",
            "friction", java.util.List.of( Map.of( "product", "wikantik", "what", "slow", "blocking", "yes" ) ) ) ) );
        assertTrue( Boolean.TRUE.equals( r.isError() ), text( r ) );
        assertTrue( text( r ).contains( "blocking" ), text( r ) );
    }
}
