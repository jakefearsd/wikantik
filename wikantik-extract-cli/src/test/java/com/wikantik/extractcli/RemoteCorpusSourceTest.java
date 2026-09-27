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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteCorpusSourceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if ( server != null ) {
            server.stop( 0 );
            server = null;
        }
    }

    /** Starts a loopback HTTP server that always answers the given status/body pair. */
    private String startFixedResponseServer( final int httpStatus, final String body ) throws Exception {
        server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        server.createContext( RemoteCorpusSource.SITEMAP_PATH, exchange -> {
            final byte[] resp = body.getBytes( StandardCharsets.UTF_8 );
            exchange.sendResponseHeaders( httpStatus, resp.length );
            try ( OutputStream os = exchange.getResponseBody() ) {
                os.write( resp );
            }
        } );
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @Test
    void httpFetcher_readsTheSitemapOverARealLoopbackConnection() throws Exception {
        // Base URL with a trailing slash exercises the "strip trailing slash" branch too.
        final String baseUrl = startFixedResponseServer( 200, TWO_PAGES );

        final CorpusSnapshot snap = new RemoteCorpusSource( RemoteCorpusSource.httpFetcher( baseUrl ) ).load();

        assertTrue( snap.complete() );
        assertEquals( 2, snap.pages().size() );
    }

    @Test
    void httpFetcher_nonOkStatusIsWrappedAsAnIncompleteSnapshot() throws Exception {
        final String baseUrl = startFixedResponseServer( 500, "internal error" );

        final CorpusSnapshot snap = new RemoteCorpusSource( RemoteCorpusSource.httpFetcher( baseUrl ) ).load();

        assertFalse( snap.complete() );
        assertTrue( snap.errors().get( 0 ).contains( "500" ), snap.errors().toString() );
    }

    private static final String TWO_PAGES = """
            {"data":{"pages":[
              {"id":"01H8G3Z1K6Q5W7P9X2V4R0T8MN","slug":"MLHub","type":"hub","cluster":"machine-learning"},
              {"id":"01H8G3Z1K6Q5W7P9X2V4R0T8AA","slug":"AgentLoops Hub","type":"hub","cluster":"agentic-ai/agent-loops"}
            ],"count":2}}
            """;

    @Test
    void reads_every_page_from_the_structural_sitemap() {
        final CorpusSnapshot snap = new RemoteCorpusSource( path -> TWO_PAGES ).load();

        assertTrue( snap.complete() );
        assertEquals( 2, snap.pages().size() );
        assertEquals( "machine-learning", snap.pages().get( "MLHub" ).cluster() );
        assertEquals( "agentic-ai/agent-loops", snap.pages().get( "AgentLoops Hub" ).cluster() );
    }

    @Test
    void a_page_with_no_cluster_reads_as_null_rather_than_empty_string() {
        final CorpusSnapshot snap = new RemoteCorpusSource(
                path -> "{\"data\":{\"pages\":[{\"id\":\"X\",\"slug\":\"Main\",\"type\":\"unknown\"}],\"count\":1}}" ).load();

        assertEquals( null, snap.pages().get( "Main" ).cluster() );
    }

    /**
     * The structural API serialises an absent `type` as `PageType.UNKNOWN` → "unknown",
     * while the repository simply has no field. Both mean "untyped", so reporting them as
     * a difference is a false positive — and 20-odd system pages would produce one each.
     */
    @Test
    void an_unknown_type_normalises_to_null_so_it_matches_an_absent_one() {
        final CorpusSnapshot snap = new RemoteCorpusSource(
                path -> "{\"data\":{\"pages\":[{\"id\":\"X\",\"slug\":\"SandBox\",\"type\":\"unknown\"}],\"count\":1}}" ).load();

        assertEquals( null, snap.pages().get( "SandBox" ).type() );
    }

    /** A transport failure must never look like an empty production corpus. */
    @Test
    void a_failed_request_marks_the_snapshot_incomplete() {
        final CorpusSnapshot snap = new RemoteCorpusSource( path -> {
            throw new IOException( "connection refused" );
        } ).load();

        assertFalse( snap.complete() );
        assertTrue( snap.errors().get( 0 ).contains( "connection refused" ) );
        assertTrue( snap.pages().isEmpty() );
    }

    /**
     * The server states how many pages it holds. If fewer arrive than it claims, the
     * response was truncated somewhere — exactly the silent-partial failure that made
     * `pages-pull` untrustworthy — so the snapshot must not present itself as total.
     */
    @Test
    void a_response_shorter_than_its_own_count_marks_the_snapshot_incomplete() {
        final CorpusSnapshot snap = new RemoteCorpusSource(
                path -> "{\"data\":{\"pages\":[{\"id\":\"X\",\"slug\":\"A\",\"type\":\"article\"}],\"count\":1200}}" ).load();

        assertFalse( snap.complete(), "1 page delivered against a claimed 1200 must not read as complete" );
        assertTrue( snap.errors().get( 0 ).contains( "1200" ) );
    }

    /** A page entry with no slug at all can't be keyed into the snapshot map — it is skipped. */
    @Test
    void a_page_with_no_slug_is_skipped_rather_than_crashing() {
        final CorpusSnapshot snap = new RemoteCorpusSource(
                path -> "{\"data\":{\"pages\":["
                        + "{\"id\":\"X\",\"type\":\"article\"},"
                        + "{\"id\":\"Y\",\"slug\":\"Kept\",\"type\":\"article\"}"
                        + "],\"count\":2}}" ).load();

        assertEquals( 1, snap.pages().size() );
        assertTrue( snap.pages().containsKey( "Kept" ) );
    }

    /** With no {@code count} field at all, the claimed size falls back to the array length. */
    @Test
    void a_response_without_a_count_field_is_complete_by_construction() {
        final CorpusSnapshot snap = new RemoteCorpusSource(
                path -> "{\"data\":{\"pages\":[{\"id\":\"X\",\"slug\":\"A\",\"type\":\"article\"}]}}" ).load();

        assertTrue( snap.complete() );
        assertEquals( 1, snap.pages().size() );
    }
}
