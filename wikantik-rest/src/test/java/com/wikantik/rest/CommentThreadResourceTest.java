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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.HttpMockFactory;
import com.wikantik.comments.CommentStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CommentThreadResourceTest {

    private final Gson gson = new Gson();
    private CommentStore store;
    private CommentThreadResource servlet;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        // Unique DB name per test method so the shared in-memory store does not
        // leak threads across methods (DB_CLOSE_DELAY=-1 keeps it alive for the
        // duration of this test's connections).
        h2.setURL( "jdbc:h2:mem:ctr_" + java.util.UUID.randomUUID().toString().replace( "-", "" )
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        try ( Connection c = h2.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "CREATE TABLE IF NOT EXISTS comment_threads (id UUID PRIMARY KEY, " +
                "canonical_id TEXT NOT NULL, anchor_exact TEXT NOT NULL, anchor_prefix TEXT, " +
                "anchor_suffix TEXT, status TEXT NOT NULL DEFAULT 'open', created_by TEXT NOT NULL, " +
                "created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "resolved_by TEXT, resolved_at TIMESTAMP WITH TIME ZONE)" );
            s.executeUpdate( "CREATE TABLE IF NOT EXISTS comments (id UUID PRIMARY KEY, " +
                "thread_id UUID NOT NULL REFERENCES comment_threads(id) ON DELETE CASCADE, " +
                "author TEXT NOT NULL, body TEXT NOT NULL, " +
                "created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "edited_at TIMESTAMP WITH TIME ZONE)" );
        }
        this.store = new CommentStore( h2 );

        this.servlet = Mockito.spy( new CommentThreadResource() );
        Mockito.doReturn( store ).when( servlet ).commentStore();
        Mockito.doReturn( Optional.of( "CID1" ) ).when( servlet ).resolveCanonicalId( "PageOne" );
        Mockito.doReturn( Optional.of( "PageOne" ) ).when( servlet ).resolveSlug( "CID1" );
        Mockito.doReturn( true ).when( servlet ).checkPagePermission(
                Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString() );
        Mockito.doReturn( "alice" ).when( servlet ).currentUser( Mockito.any() );
    }

    @Test
    void create_then_list_roundtrips() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "exact", "hello" );
        body.addProperty( "prefix", "say " );
        body.addProperty( "suffix", " world" );
        body.addProperty( "text", "what does this mean?" );

        final JsonObject created = post( "?page=PageOne", null, body );
        assertFalse( created.has( "error" ), created.toString() );
        assertTrue( created.has( "id" ) );
        assertEquals( "open", created.get( "status" ).getAsString() );

        final JsonObject list = get( "?page=PageOne&status=all" );
        final JsonArray threads = list.getAsJsonArray( "threads" );
        assertEquals( 1, threads.size() );
        assertEquals( "hello", threads.get( 0 ).getAsJsonObject()
                .getAsJsonObject( "anchor" ).get( "exact" ).getAsString() );
    }

    @Test
    void reply_resolve_reopen_cycle() throws Exception {
        final String threadId = createThread();

        final JsonObject reply = new JsonObject();
        reply.addProperty( "text", "a reply" );
        assertFalse( post( null, "/" + threadId + "/comments", reply ).has( "error" ) );

        assertFalse( post( null, "/" + threadId + "/resolve", new JsonObject() ).has( "error" ) );
        assertEquals( 1, store.listByCanonicalId( "CID1", "resolved" ).size() );

        assertFalse( post( null, "/" + threadId + "/reopen", new JsonObject() ).has( "error" ) );
        assertEquals( 1, store.listByCanonicalId( "CID1", "open" ).size() );
    }

    @Test
    void edit_by_non_author_is_forbidden_with_reason() throws Exception {
        final String threadId = createThread();
        final String commentId = store.listByCanonicalId( "CID1", "all" )
                .get( 0 ).comments().get( 0 ).id().toString();

        Mockito.doReturn( "mallory" ).when( servlet ).currentUser( Mockito.any() );
        final JsonObject body = new JsonObject();
        body.addProperty( "text", "hijack" );

        final JsonObject res = patch( "/" + threadId + "/comments/" + commentId, body );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 403, res.get( "status" ).getAsInt() );
        assertTrue( res.get( "message" ).getAsString().toLowerCase().contains( "author" ),
                "refusal must cite the reason: " + res );
    }

    @Test
    void edit_comment_from_a_different_thread_is_not_found() throws Exception {
        final String threadA = createThread();
        final String threadB = createThread();
        // comment id that belongs to thread B
        final String threadBCommentId = store.findThread( java.util.UUID.fromString( threadB ) )
                .orElseThrow().comments().get( 0 ).id().toString();

        final JsonObject body = new JsonObject();
        body.addProperty( "text", "cross-thread edit" );
        // target thread A's path but B's comment id
        final JsonObject res = patch( "/" + threadA + "/comments/" + threadBCommentId, body );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 404, res.get( "status" ).getAsInt() );
    }

    @Test
    void create_missing_text_is_400() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "exact", "hello" );
        final JsonObject res = post( "?page=PageOne", null, body );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 400, res.get( "status" ).getAsInt() );
    }

    @Test
    void create_unknown_page_is_404() throws Exception {
        Mockito.doReturn( Optional.empty() ).when( servlet ).resolveCanonicalId( "Ghost" );
        final JsonObject body = new JsonObject();
        body.addProperty( "exact", "hello" );
        body.addProperty( "text", "hi" );
        final JsonObject res = post( "?page=Ghost", null, body );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 404, res.get( "status" ).getAsInt() );
    }

    private String createThread() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "exact", "hello" );
        body.addProperty( "text", "first" );
        return post( "?page=PageOne", null, body ).get( "id" ).getAsString();
    }

    private JsonObject get( final String query ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/comment-threads" + query );
        Mockito.doReturn( null ).when( req ).getPathInfo();
        Mockito.doReturn( paramOf( query, "page" ) ).when( req ).getParameter( "page" );
        Mockito.doReturn( paramOf( query, "status" ) ).when( req ).getParameter( "status" );
        return invoke( req, "GET", null );
    }

    private JsonObject post( final String query, final String pathInfo, final JsonObject body ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest(
                "/api/comment-threads" + ( query == null ? "" : query ) );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        if ( query != null ) Mockito.doReturn( paramOf( query, "page" ) ).when( req ).getParameter( "page" );
        return invoke( req, "POST", body );
    }

    private JsonObject patch( final String pathInfo, final JsonObject body ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/comment-threads" );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        return invoke( req, "PATCH", body );
    }

    private JsonObject invoke( final HttpServletRequest req, final String method, final JsonObject body )
            throws Exception {
        Mockito.doReturn( method ).when( req ).getMethod();
        if ( body != null ) {
            Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( req ).getReader();
        }
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        servlet.service( req, resp );
        return gson.fromJson( sw.toString().isBlank() ? "{}" : sw.toString(), JsonObject.class );
    }

    private static String paramOf( final String query, final String key ) {
        if ( query == null ) return null;
        for ( final String pair : query.replaceFirst( "^\\?", "" ).split( "&" ) ) {
            final String[] kv = pair.split( "=", 2 );
            if ( kv.length == 2 && kv[ 0 ].equals( key ) ) return kv[ 1 ];
        }
        return null;
    }
}
