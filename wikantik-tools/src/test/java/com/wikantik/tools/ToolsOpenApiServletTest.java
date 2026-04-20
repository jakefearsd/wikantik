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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class ToolsOpenApiServletTest {

    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;

    @Test
    void placeholderSpecServedWhenEngineMissing() throws Exception {
        final ToolsOpenApiServlet servlet = new ToolsOpenApiServlet();
        when( request.getPathInfo() ).thenReturn( "/openapi.json" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        servlet.doGet( request, response );

        verify( response ).setStatus( HttpServletResponse.SC_OK );
        verify( response ).setContentType( "application/json" );
        assertTrue( body.toString().contains( "\"openapi\"" ) );
        assertTrue( body.toString().contains( "Wikantik Tool Server" ) );
    }

    @Test
    void realOpenApiDocIncludesSearchAndPageOperations() throws Exception {
        final ToolsOpenApiServlet servlet = new ToolsOpenApiServlet(
                null, new ToolsConfig( new Properties() ), new ToolsMetrics() );
        when( request.getPathInfo() ).thenReturn( "/openapi.json" );
        when( request.getScheme() ).thenReturn( "https" );
        when( request.getServerName() ).thenReturn( "wiki.example.com" );
        when( request.getServerPort() ).thenReturn( 443 );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        servlet.doGet( request, response );

        final String json = body.toString();
        assertTrue( json.contains( "\"search_wiki\"" ), json );
        assertTrue( json.contains( "\"get_page\"" ), json );
        assertTrue( json.contains( "bearerAuth" ), json );
        assertTrue( json.contains( "https://wiki.example.com/tools" ), json );
    }

    @Test
    void unknownGetPathReturns501() throws Exception {
        final ToolsOpenApiServlet servlet = new ToolsOpenApiServlet();
        when( request.getPathInfo() ).thenReturn( "/unknown" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        servlet.doGet( request, response );

        verify( response ).setStatus( HttpServletResponse.SC_NOT_IMPLEMENTED );
    }

    @Test
    void unknownPostPathReturns501() throws Exception {
        final ToolsOpenApiServlet servlet = new ToolsOpenApiServlet();
        when( request.getPathInfo() ).thenReturn( "/unknown" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        servlet.doPost( request, response );

        verify( response ).setStatus( HttpServletResponse.SC_NOT_IMPLEMENTED );
    }

    @Test
    void searchReturns503WhenEngineMissing() throws Exception {
        final ToolsOpenApiServlet servlet = new ToolsOpenApiServlet(
                null, new ToolsConfig( new Properties() ), new ToolsMetrics() );
        when( request.getPathInfo() ).thenReturn( "/search_wiki" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        servlet.doPost( request, response );

        verify( response ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        assertTrue( body.toString().contains( "not initialized" ), body.toString() );
    }

    @Test
    void getPageReturns403WhenPermissionDenied() throws Exception {
        final GetPageTool denying = new GetPageTool( null, new ToolsConfig( new Properties() ) ) {
            @Override
            java.util.Map< String, Object > execute( final String pageName, final int maxChars,
                                                     final HttpServletRequest request ) {
                throw new PageAccessDeniedException( "SecretPage" );
            }
        };
        final ToolsMetrics metrics = new ToolsMetrics();
        final ToolsOpenApiServlet servlet = new ToolsOpenApiServlet(
                new ToolsConfig( new Properties() ), metrics, null, denying );

        when( request.getPathInfo() ).thenReturn( "/page/SecretPage" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        servlet.doGet( request, response );

        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
        assertEquals( 1, metrics.getPageForbidden(),
                "403 must be counted distinctly from 500 so dashboards can alert on real errors only" );
    }
}
