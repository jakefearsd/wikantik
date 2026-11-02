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
package com.wikantik.extractcli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AclShapeAuditCliTest {

    private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream( outBuf, true, StandardCharsets.UTF_8 );
    private final PrintStream err = new PrintStream( errBuf, true, StandardCharsets.UTF_8 );

    private String output() {
        return outBuf.toString( StandardCharsets.UTF_8 );
    }

    private static void page( final Path dir, final String name, final String text ) throws IOException {
        Files.writeString( dir.resolve( name + ".md" ), text );
    }

    @Test
    void clean_directory_exits_zero_and_prints_every_page(@TempDir final Path dir) throws IOException {
        page( dir, "Plain", "text\n" );
        page( dir, "Guarded", "[{ALLOW view Admin}]\n" );
        final int code = AclShapeAuditCli.execute( new String[]{ "--dir", dir.toString() }, k -> null, 0, out, err );
        assertEquals( 0, code );
        final String o = output();
        assertTrue( o.contains( "Guarded\n  Reading: Rules" ) );
        assertTrue( o.contains( "new ACL:           view:Admin" ) );
        assertTrue( o.contains( "locked: 0" ) );
        assertTrue( o.contains( "changed: 0" ) );
    }

    @Test
    void locked_changed_and_escaped_pages_are_listed_and_exit_one(@TempDir final Path dir) throws IOException {
        page( dir, "Denied", "[{DENY view Bob}]\n" );
        page( dir, "Wrapped", "[{ALLOW view Admin,\nBob}]\n" );
        page( dir, "Esc", "\\[{ALLOW view Admin}]\n" );
        page( dir, "Fine", "x\n" );
        final int code = AclShapeAuditCli.execute( new String[]{ "--dir", dir.toString() }, k -> null, 0, out, err );
        assertEquals( 1, code );
        final String o = output();
        final String summary = o.substring( o.indexOf( "== Summary ==" ) );
        assertTrue( summary.contains( "locked: 1\n  Denied" ) );
        assertTrue( summary.contains( "changed: 3" ) );
        assertTrue( summary.contains( "ESCAPED: 1\n  Esc" ) );
    }

    @Test
    void json_format_carries_pages_and_summary(@TempDir final Path dir) throws IOException {
        page( dir, "Denied", "[{DENY view Bob}]\n" );
        final int code = AclShapeAuditCli.execute( new String[]{ "--dir", dir.toString(), "--format", "json" }, k -> null, 0, out, err );
        assertEquals( 1, code );
        final var root = com.google.gson.JsonParser.parseString( output() ).getAsJsonObject();
        assertEquals( "Locked", root.getAsJsonArray( "pages" ).get( 0 ).getAsJsonObject().get( "reading" ).getAsString() );
        assertEquals( "Denied", root.getAsJsonObject( "summary" ).getAsJsonArray( "locked" ).get( 0 ).getAsString() );
    }

    @Test
    void usage_errors_exit_64() {
        assertEquals( 64, AclShapeAuditCli.execute( new String[]{}, k -> null, 0, out, err ) );
        assertEquals( 64, AclShapeAuditCli.execute( new String[]{ "--dir", "a", "--url", "b" }, k -> null, 0, out, err ) );
        assertEquals( 64, AclShapeAuditCli.execute( new String[]{ "--dir", "a", "--format", "xml" }, k -> null, 0, out, err ) );
        assertEquals( 64, AclShapeAuditCli.execute( new String[]{ "--url", "http://x" }, k -> null, 0, out, err ) );
        assertTrue( errBuf.toString( StandardCharsets.UTF_8 ).contains( "WIKANTIK_AUDIT_TOKEN" ) );
    }

    @Test
    void authorization_prefers_the_token_and_never_needs_to_print_it() {
        assertEquals( "Bearer tok", AclShapeAuditCli.authorization( Map.of( "WIKANTIK_AUDIT_TOKEN", "tok" )::get ) );
        assertEquals( "Basic dTpw", AclShapeAuditCli.authorization(
                Map.of( "WIKANTIK_AUDIT_USER", "u", "WIKANTIK_AUDIT_PASSWORD", "p" )::get ) );
        assertNull( AclShapeAuditCli.authorization( k -> null ) );
    }

    @Test
    void parsePage_rebuilds_frontmatter_and_body_and_keeps_server_reasons() {
        final AclShapeAuditCli.Fetched f = AclShapeAuditSources.parsePage( """
            {"name":"P","content":"Body\n","metadata":{"summary":"[{ALLOW view Admin}]","tags":["a","b"]},
             "accessRules":{"locked":true,"reasons":[{"shape":"IN_FRONTMATTER","field":"summary","text":"x"}]}}
            """ );
        assertTrue( f.text().startsWith( "---\n" ) );
        assertTrue( f.text().contains( "summary: '[{ALLOW view Admin}]'" ) );
        assertTrue( f.text().endsWith( "---\nBody\n" ) );
        assertEquals( List.of( "server: IN_FRONTMATTER field summary: x" ), f.serverLockReasons() );
    }

    /** A stub wiki: two pages listed one per request, one of them failing to fetch. */
    @Test
    void url_mode_lists_fetches_authenticates_and_reports_failures() throws Exception {
        final List< String > auth = new CopyOnWriteArrayList<>();
        final List< String > paths = new CopyOnWriteArrayList<>();
        final HttpServer server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        server.createContext( "/", ex -> {
            final String path = ex.getRequestURI().getRawPath() + ( ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery() );
            paths.add( path );
            auth.add( ex.getRequestHeaders().getFirst( "Authorization" ) );
            final int status;
            final String body;
            if ( path.startsWith( "/api/pages?" ) ) {
                final boolean second = path.contains( "offset=0" );
                status = 200;
                body = second ? "{\"total\":3,\"pages\":[{\"name\":\"Good Page\"},{\"name\":\"Broken\"},{\"name\":\"Locked\"}]}"
                              : "{\"total\":3,\"pages\":[]}";
            } else if ( path.equals( "/api/pages/Good%20Page" ) ) {
                status = 200;
                body = "{\"name\":\"Good Page\",\"content\":\"[{ALLOW view Admin}]\\n\",\"metadata\":{}}";
            } else if ( path.equals( "/api/pages/Locked" ) ) {
                status = 200;
                body = "{\"name\":\"Locked\",\"content\":\"[{DENY view Bob}]\\n\",\"metadata\":{}}";
            } else {
                status = 500;
                body = "{}";
            }
            final byte[] bytes = body.getBytes( StandardCharsets.UTF_8 );
            ex.sendResponseHeaders( status, bytes.length );
            ex.getResponseBody().write( bytes );
            ex.close();
        } );
        server.start();
        try {
            final String base = "http://127.0.0.1:" + server.getAddress().getPort();
            final int code = AclShapeAuditCli.execute( new String[]{ "--url", base }, Map.of( "WIKANTIK_AUDIT_TOKEN", "secret-token" )::get, 0, out, err );
            assertEquals( 2, code, "a failed fetch outranks a locked page" );
            final String o = output();
            assertTrue( o.contains( "Good Page\n  Reading: Rules" ) );
            assertTrue( o.contains( "Broken\n  FETCH FAILED: HTTP 500" ) );
            assertTrue( o.contains( "fetch failures: 1\n  Broken" ) );
            assertTrue( o.contains( "locked: 1\n  Locked" ) );
            assertTrue( auth.stream().allMatch( "Bearer secret-token"::equals ) );
            assertFalse( o.contains( "secret-token" ) );
            assertFalse( errBuf.toString( StandardCharsets.UTF_8 ).contains( "secret-token" ) );
        } finally {
            server.stop( 0 );
        }
    }

    @Test
    void unlistable_wiki_is_refused_with_exit_two() throws Exception {
        final HttpServer server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        server.createContext( "/", ex -> {
            ex.sendResponseHeaders( 401, -1 );
            ex.close();
        } );
        server.start();
        try {
            final String base = "http://127.0.0.1:" + server.getAddress().getPort();
            final int code = AclShapeAuditCli.execute( new String[]{ "--url", base }, Map.of( "WIKANTIK_AUDIT_TOKEN", "t" )::get, 0, out, err );
            assertEquals( 2, code );
            assertTrue( errBuf.toString( StandardCharsets.UTF_8 ).contains( "REFUSED" ) );
        } finally {
            server.stop( 0 );
        }
    }

    @Test
    void throttle_spaces_requests(@TempDir final Path dir) throws Exception {
        final long start = System.currentTimeMillis();
        final HttpServer server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        server.createContext( "/", ex -> {
            final String body = ex.getRequestURI().getRawPath().equals( "/api/pages" )
                    ? "{\"total\":3,\"pages\":[{\"name\":\"A\"},{\"name\":\"B\"},{\"name\":\"C\"}]}"
                    : "{\"content\":\"x\",\"metadata\":{}}";
            final byte[] bytes = body.getBytes( StandardCharsets.UTF_8 );
            ex.sendResponseHeaders( 200, bytes.length );
            ex.getResponseBody().write( bytes );
            ex.close();
        } );
        server.start();
        try {
            final String base = "http://127.0.0.1:" + server.getAddress().getPort();
            AclShapeAuditCli.execute( new String[]{ "--url", base }, Map.of( "WIKANTIK_AUDIT_TOKEN", "t" )::get, 100, out, err );
            assertTrue( System.currentTimeMillis() - start >= 300, "4 requests at 100ms spacing take at least 300ms" );
        } finally {
            server.stop( 0 );
        }
    }

    @Test
    void url_with_userinfo_is_refused_without_echoing_it() {
        final int code = AclShapeAuditCli.execute( new String[]{ "--url", "https://bob:hunter2@wiki.example.com" },
                Map.of( "WIKANTIK_AUDIT_TOKEN", "t" )::get, 0, out, err );
        assertEquals( 64, code );
        final String all = errBuf.toString( StandardCharsets.UTF_8 ) + output();
        assertFalse( all.contains( "hunter2" ) || all.contains( "bob" ), all );
    }

    @Test
    void plain_http_with_credentials_is_refused_except_loopback() {
        assertEquals( 64, AclShapeAuditCli.execute( new String[]{ "--url", "http://wiki.example.com" },
                Map.of( "WIKANTIK_AUDIT_TOKEN", "t" )::get, 0, out, err ) );
        assertNull( AclShapeAuditCli.unsafeUrlReason( "https://wiki.example.com" ) );
        assertNull( AclShapeAuditCli.unsafeUrlReason( "http://localhost:8080" ) );
        assertNull( AclShapeAuditCli.unsafeUrlReason( "http://127.0.0.1:8080" ) );
    }

    @Test
    void malformed_url_is_refused_without_echoing_it() {
        final String reason = AclShapeAuditCli.unsafeUrlReason( "https://u:pw@ho st/" );
        assertFalse( reason.contains( "pw" ) );
    }

    @Test
    void empty_directory_is_refused_exit_two(@TempDir final Path dir) {
        assertEquals( 2, AclShapeAuditCli.execute( new String[]{ "--dir", dir.toString() }, k -> null, 0, out, err ) );
        assertTrue( errBuf.toString( StandardCharsets.UTF_8 ).contains( "REFUSED" ) );
    }

    @Test
    void nonexistent_directory_is_refused_exit_two(@TempDir final Path dir) {
        assertEquals( 2, AclShapeAuditCli.execute( new String[]{ "--dir", dir.resolve( "nope" ).toString() }, k -> null, 0, out, err ) );
        assertTrue( errBuf.toString( StandardCharsets.UTF_8 ).contains( "REFUSED" ) );
    }

    @Test
    void messageless_exception_records_its_class_name() {
        final AclShapeAuditCli.PageSource source = new AclShapeAuditCli.PageSource() {
            @Override
            public List< String > names() {
                return List.of( "Boom" );
            }

            @Override
            public AclShapeAuditCli.Fetched fetch( final String name ) {
                throw new IllegalStateException();
            }
        };
        assertEquals( 2, AclShapeAuditCli.run( source, false, out, err ) );
        final String o = output();
        assertTrue( o.contains( "IllegalStateException" ), o );
        assertFalse( o.contains( "null" ), o );
    }
}
