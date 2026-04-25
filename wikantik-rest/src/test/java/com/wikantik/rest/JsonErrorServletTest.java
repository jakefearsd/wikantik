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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reproduces D10 — was: Tomcat HTML error pages (with Apache Tomcat/11.0.14 footer)
 * leaked through /api/* and /admin/* on malformed inputs. The
 * {@link JsonErrorServlet} is wired in web.xml as the location of {@code <error-page>}
 * blocks for 400/404/405/415/500 so every container-level error returns JSON.
 *
 * <p>Tests assert the JSON body shape, content-type, and that no leakage hits the
 * client (no Tomcat version, no stack trace, no internal paths from arbitrary
 * exception messages).
 */
class JsonErrorServletTest {

    private JsonErrorServlet servlet;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter body;

    @BeforeEach
    void setUp() throws Exception {
        servlet = new JsonErrorServlet();
        request = mock( HttpServletRequest.class );
        response = mock( HttpServletResponse.class );
        body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );
    }

    @Test
    void writes_json_for_400_with_tomcat_message() throws Exception {
        when( request.getAttribute( RequestDispatcher.ERROR_STATUS_CODE ) ).thenReturn( 400 );
        when( request.getAttribute( RequestDispatcher.ERROR_MESSAGE ) )
                .thenReturn( "encoded slash not allowed" );

        servlet.service( request, response );

        verify( response ).setStatus( 400 );
        verify( response ).setContentType( "application/json" );
        verify( response ).setCharacterEncoding( "UTF-8" );

        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertTrue( json.get( "error" ).getAsBoolean() );
        assertEquals( 400, json.get( "status" ).getAsInt() );
        assertTrue( json.has( "message" ) );
    }

    @Test
    void omits_tomcat_version_string_from_message() throws Exception {
        when( request.getAttribute( RequestDispatcher.ERROR_STATUS_CODE ) ).thenReturn( 400 );
        // Real Tomcat 11 error message can include the version footer in the description
        // attribute. We must never echo it back to API consumers.
        when( request.getAttribute( RequestDispatcher.ERROR_MESSAGE ) )
                .thenReturn( "Apache Tomcat/11.0.14 - Bad Request" );

        servlet.service( request, response );

        final String out = body.toString();
        assertFalse( out.contains( "Apache Tomcat" ),
                "JSON body must not leak the Tomcat version string" );
        assertFalse( out.toLowerCase().contains( "tomcat/11" ),
                "JSON body must not include any tomcat/<version> token" );
    }

    @Test
    void omits_stack_trace_and_exception_class_names() throws Exception {
        when( request.getAttribute( RequestDispatcher.ERROR_STATUS_CODE ) ).thenReturn( 500 );
        when( request.getAttribute( RequestDispatcher.ERROR_MESSAGE ) )
                .thenReturn( "java.lang.NullPointerException at com.foo.Bar.baz(Bar.java:42)" );
        when( request.getAttribute( RequestDispatcher.ERROR_EXCEPTION ) )
                .thenReturn( new RuntimeException( "boom" ) );

        servlet.service( request, response );

        final String out = body.toString();
        assertFalse( out.contains( "NullPointerException" ),
                "JSON body must not leak Java exception class names" );
        assertFalse( out.contains( "Bar.java" ),
                "JSON body must not leak source file references" );
        assertFalse( out.contains( "at com.foo" ),
                "JSON body must not include stack frames" );
    }

    @Test
    void uses_safe_default_message_for_404() throws Exception {
        when( request.getAttribute( RequestDispatcher.ERROR_STATUS_CODE ) ).thenReturn( 404 );
        when( request.getAttribute( RequestDispatcher.ERROR_MESSAGE ) ).thenReturn( null );

        servlet.service( request, response );

        verify( response ).setStatus( 404 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertEquals( 404, json.get( "status" ).getAsInt() );
        assertTrue( json.get( "error" ).getAsBoolean() );
        assertTrue( json.has( "message" ) );
        assertFalse( json.get( "message" ).getAsString().isEmpty(),
                "404 must always carry a non-empty client-safe message" );
    }

    @Test
    void defaults_to_500_when_status_attribute_missing() throws Exception {
        // Some Tomcat error paths dispatch without setting the error attribute.
        // The servlet must still emit valid JSON, not blow up.
        when( request.getAttribute( RequestDispatcher.ERROR_STATUS_CODE ) ).thenReturn( null );

        servlet.service( request, response );

        verify( response ).setStatus( 500 );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertEquals( 500, json.get( "status" ).getAsInt() );
    }

    @Test
    void content_type_is_application_json_with_utf8() throws Exception {
        when( request.getAttribute( RequestDispatcher.ERROR_STATUS_CODE ) ).thenReturn( 415 );

        servlet.service( request, response );

        verify( response ).setContentType( "application/json" );
        verify( response ).setCharacterEncoding( "UTF-8" );
    }

    @Test
    void omits_internal_filesystem_paths_from_message() throws Exception {
        when( request.getAttribute( RequestDispatcher.ERROR_STATUS_CODE ) ).thenReturn( 500 );
        when( request.getAttribute( RequestDispatcher.ERROR_MESSAGE ) )
                .thenReturn( "Cannot read /opt/tomcat/webapps/ROOT/WEB-INF/secret.properties" );

        servlet.service( request, response );

        final String out = body.toString();
        assertFalse( out.contains( "/opt/tomcat" ),
                "JSON body must not leak server filesystem paths" );
        assertFalse( out.contains( "WEB-INF" ),
                "JSON body must not leak WEB-INF references" );
    }
}
