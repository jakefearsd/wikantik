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
package com.wikantik.connectors.github;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class HttpGithubApiTest {

    private static HttpServer server;
    private static String base;
    private static final Map< String, String > seenAuth = new ConcurrentHashMap<>();

    @BeforeAll static void start() throws Exception {
        server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        server.createContext( "/repos/acme/handbook", ex -> {
            seenAuth.put( ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst( "Authorization" ) );
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery();
            if ( path.equals( "/repos/acme/handbook" ) ) {
                respond( ex, 200, "{\"default_branch\":\"trunk\"}" );
            } else if ( path.equals( "/repos/acme/handbook/git/trees/main" ) && "recursive=1".equals( query ) ) {
                respond( ex, 200, "{\"truncated\":false,\"tree\":["
                    + "{\"path\":\"docs/a.md\",\"type\":\"blob\",\"sha\":\"s1\",\"size\":7},"
                    + "{\"path\":\"docs\",\"type\":\"tree\",\"sha\":\"s2\"},"
                    + "{\"path\":\"b.md\",\"type\":\"blob\",\"sha\":\"s3\",\"size\":3}]}" );
            } else if ( path.equals( "/repos/acme/handbook/contents/docs/a.md" ) ) {
                respond( ex, 200, "# hello" );
            } else if ( path.equals( "/repos/acme/handbook/contents/gone.md" ) ) {
                respond( ex, 404, "{\"message\":\"Not Found\"}" );
            } else if ( path.equals( "/repos/acme/handbook/contents/docs/Getting Started.md" ) ) {
                respond( ex, 200, "# spaced" );
            } else if ( path.equals( "/repos/acme/handbook/git/trees/boom" ) ) {
                respond( ex, 500, "oops" );
            } else {
                respond( ex, 404, "{}" );
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

    private HttpGithubApi api() { return new HttpGithubApi( base, "acme/handbook", "PAT123" ); }

    @Test void defaultBranchParsesAndSendsBearerAuth() throws IOException {
        assertEquals( "trunk", api().defaultBranch() );
        assertEquals( "Bearer PAT123", seenAuth.get( "/repos/acme/handbook" ) );
    }

    @Test void listTreeParsesBlobsOnly() throws IOException {
        TreeListing t = api().listTree( "main" );
        assertFalse( t.truncated() );
        assertEquals( 2, t.files().size() );                                  // the "tree" entry excluded
        assertEquals( "docs/a.md", t.files().get( 0 ).path() );
        assertEquals( "s1", t.files().get( 0 ).sha() );
        assertEquals( 7, t.files().get( 0 ).size() );
    }

    @Test void rawContentReturnsBytesAnd404MapsToEmpty() throws IOException {
        assertArrayEquals( "# hello".getBytes( StandardCharsets.UTF_8 ),
            api().rawContent( "docs/a.md", "main" ).orElseThrow() );
        assertTrue( api().rawContent( "gone.md", "main" ).isEmpty() );
    }

    @Test void non2xxNon404Throws() {
        assertThrows( IOException.class, () -> api().listTree( "boom" ) );
    }

    @Test void rawContentEncodesUriIllegalPathSegments() throws IOException {
        assertArrayEquals( "# spaced".getBytes( StandardCharsets.UTF_8 ),
            api().rawContent( "docs/Getting Started.md", "main" ).orElseThrow() );
    }
}
