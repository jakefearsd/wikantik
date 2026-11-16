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
package com.wikantik.its;

import com.google.gson.JsonParser;
import com.wikantik.its.environment.Env;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With {@code wikantik.work.enabled} off (the default, and the state of every IT module but the REST one) work is not
 * there: its REST routes and {@code /work-mcp} answer 404, with a key or without, and no cookie is set. Skipped
 * (an assumption, with the reason) in a module whose wiki has work on. Needs no browser.
 */
public class WorkDisabledIT {

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NEVER ).build();
    private static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
        + "\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"1\"}}}";

    private static HttpResponse< String > send( final String method, final String path, final String body,
                                                final String... headers ) throws Exception {
        final String base = Env.TESTS_BASE_URL.endsWith( "/" ) ? Env.TESTS_BASE_URL.substring( 0, Env.TESTS_BASE_URL.length() - 1 )
            : Env.TESTS_BASE_URL;
        final HttpRequest.Builder b = HttpRequest.newBuilder().uri( URI.create( base + path ) ).header( "Accept", "application/json" )
            .method( method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString( body ) );
        if ( body != null ) {
            b.header( "Content-Type", "application/json" );
        }
        for ( int i = 0; i + 1 < headers.length; i += 2 ) {
            b.setHeader( headers[ i ], headers[ i + 1 ] );
        }
        return HTTP.send( b.build(), HttpResponse.BodyHandlers.ofString() );
    }

    @BeforeAll
    static void onlyWhereWorkIsOff() throws Exception {
        final HttpResponse< String > caps = send( "GET", "/api/capabilities", null );
        assertEquals( 200, caps.statusCode(), "capabilities: " + caps.body() );
        Assumptions.assumeFalse( JsonParser.parseString( caps.body() ).getAsJsonObject().get( "work" ).getAsBoolean(),
            "work management is enabled in this module's wiki; WorkDisabledIT only applies where it is off" );
    }

    @Test
    void capabilitiesReportWorkOff() throws Exception {
        final HttpResponse< String > caps = send( "GET", "/api/capabilities", null );
        assertEquals( false, JsonParser.parseString( caps.body() ).getAsJsonObject().get( "work" ).getAsBoolean() );
        // positive control for the 404s below: the same server answers an ordinary route
        assertEquals( 200, send( "GET", "/api/capabilities", null ).statusCode() );
    }

    @Test
    void workRestRoutesAreNotThere() throws Exception {
        assertEquals( 404, send( "GET", "/api/work/me", null ).statusCode() );
        final HttpResponse< String > keyed = send( "GET", "/api/work/me", null, "Authorization", "Bearer wkk_not-a-real-key" );
        assertEquals( 404, keyed.statusCode(), "a bearer request is 404 too, not a 401" );
        assertTrue( keyed.headers().allValues( "Set-Cookie" ).isEmpty(), "and sets no cookie: " + keyed.headers().allValues( "Set-Cookie" ) );
    }

    @Test
    void workMcpIsNotThere() throws Exception {
        assertEquals( 404, send( "POST", "/work-mcp", INIT, "Accept", "application/json, text/event-stream" ).statusCode() );
        assertEquals( 404, send( "POST", "/work-mcp", INIT, "Accept", "application/json, text/event-stream",
            "Authorization", "Bearer wkk_not-a-real-key" ).statusCode() );
    }
}
