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
package com.wikantik.connectors.confluence;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class HttpConfluenceApiTest {

    private static HttpServer server;
    private static String base;
    private static final Map< String, String > seenAuth = new ConcurrentHashMap<>();

    @BeforeAll static void start() throws Exception {
        server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        server.createContext( "/wiki/api/v2/spaces", ex -> {
            seenAuth.put( ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst( "Authorization" ) );
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery();
            if ( path.equals( "/wiki/api/v2/spaces" ) && query.contains( "keys=ENG" ) ) {
                respond( ex, 200, "{\"results\":[{\"id\":\"777\",\"key\":\"ENG\"}]}" );
            } else if ( path.equals( "/wiki/api/v2/spaces" ) && query.contains( "keys=NOPE" ) ) {
                respond( ex, 200, "{\"results\":[]}" );
            } else if ( path.equals( "/wiki/api/v2/spaces/777/pages" ) && !query.contains( "cursor=p2" ) ) {
                respond( ex, 200, "{\"results\":[{\"id\":\"1\",\"title\":\"A\","
                    + "\"version\":{\"number\":4},"
                    + "\"body\":{\"storage\":{\"value\":\"<p>a</p>\"}},"
                    + "\"_links\":{\"webui\":\"/spaces/ENG/pages/1/A\"}}],"
                    + "\"_links\":{\"next\":\"/wiki/api/v2/spaces/777/pages?cursor=p2\"}}" );
            } else if ( path.equals( "/wiki/api/v2/spaces/777/pages" ) ) {   // cursor=p2 — final page
                respond( ex, 200, "{\"results\":[{\"id\":\"2\",\"title\":\"B\","
                    + "\"version\":{\"number\":1},"
                    + "\"body\":{\"storage\":{\"value\":\"<p>b</p>\"}},"
                    + "\"_links\":{\"webui\":\"/spaces/ENG/pages/2/B\"}}],"
                    + "\"_links\":{}}" );
            } else {
                respond( ex, 500, "oops" );
            }
        } );
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterAll static void stop() { server.stop( 0 ); }

    private static void respond( final com.sun.net.httpserver.HttpExchange ex, final int code, final String body ) throws IOException {
        byte[] b = body.getBytes( StandardCharsets.UTF_8 );
        ex.sendResponseHeaders( code, b.length );
        try ( OutputStream os = ex.getResponseBody() ) { os.write( b ); }
    }

    @Test void listPagesFollowsPaginationAndParses() throws IOException {
        HttpConfluenceApi api = new HttpConfluenceApi( base, "ENG", "bot@acme.com", "TOK" );
        List< ConfluencePage > pages = api.listPages( 500 );
        assertEquals( 2, pages.size() );
        assertEquals( new ConfluencePage( "1", "A", 4, "/spaces/ENG/pages/1/A", "<p>a</p>" ), pages.get( 0 ) );
        assertEquals( new ConfluencePage( "2", "B", 1, "/spaces/ENG/pages/2/B", "<p>b</p>" ), pages.get( 1 ) );
        String expectedAuth = "Basic " + Base64.getEncoder()
            .encodeToString( "bot@acme.com:TOK".getBytes( StandardCharsets.UTF_8 ) );
        assertEquals( expectedAuth, seenAuth.get( "/wiki/api/v2/spaces" ) );
    }

    @Test void maxPagesStopsPagination() throws IOException {
        HttpConfluenceApi api = new HttpConfluenceApi( base, "ENG", "bot@acme.com", "TOK" );
        assertEquals( 1, api.listPages( 1 ).size() );   // stops before following the next link
    }

    @Test void unknownSpaceThrows() {
        HttpConfluenceApi api = new HttpConfluenceApi( base, "NOPE", "bot@acme.com", "TOK" );
        assertThrows( IOException.class, () -> api.listPages( 500 ) );
    }
}
