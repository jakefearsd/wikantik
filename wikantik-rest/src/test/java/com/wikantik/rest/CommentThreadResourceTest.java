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
import com.wikantik.WikiSubsystems;
import com.wikantik.comments.CommentStore;
import com.wikantik.comments.PageOwnerService;
import com.wikantik.comments.mentions.MentionService;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystem;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CommentThreadResourceTest {

    private final Gson gson = new Gson();
    private CommentStore store;
    MentionService mentionService;
    private PageOwnerService pageOwnerSvc;
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
            s.executeUpdate( """
                CREATE TABLE IF NOT EXISTS comment_mentions (
                    id UUID PRIMARY KEY,
                    comment_id UUID NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
                    mentioned_login TEXT NOT NULL, mentioning_login TEXT NOT NULL,
                    is_owner_mention BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    read_at TIMESTAMP WITH TIME ZONE,
                    CONSTRAINT uq_comment_mentions UNIQUE (comment_id, mentioned_login)
                )""" );
        }
        this.store = new CommentStore( h2 );
        final Set< String > users = new HashSet<>( List.of( "alice", "bob", "carol", "admin" ) );
        this.mentionService = new MentionService( h2, users::contains );
        this.pageOwnerSvc = Mockito.mock( PageOwnerService.class );
        Mockito.when( pageOwnerSvc.getOwner( "CID1" ) ).thenReturn( "alice" );

        this.servlet = Mockito.spy( new CommentThreadResource() );
        Mockito.doReturn( store ).when( servlet ).commentStore();
        Mockito.doReturn( mentionService ).when( servlet ).mentionService();
        Mockito.doReturn( pageOwnerSvc ).when( servlet ).pageOwnerService();
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

    @Test
    void delete_by_author_succeeds() throws Exception {
        final String threadId = createThread();
        final String commentId = store.findThread( java.util.UUID.fromString( threadId ) )
                .orElseThrow().comments().get( 0 ).id().toString();
        // currentUser is "alice" (the author) by default. canModerate is computed
        // eagerly before the && short-circuit, so hasPagePermission must be stubbed.
        Mockito.doReturn( false ).when( servlet ).hasPagePermission(
                Mockito.any(), Mockito.anyString(), Mockito.anyString() );
        final JsonObject res = del( "/" + threadId + "/comments/" + commentId );
        assertFalse( res.has( "error" ), res.toString() );
        assertTrue( res.get( "deleted" ).getAsBoolean() );
        assertEquals( commentId, res.get( "id" ).getAsString() );
        assertTrue( store.findComment( java.util.UUID.fromString( commentId ) ).isEmpty() );
    }

    @Test
    void delete_by_moderator_succeeds() throws Exception {
        final String threadId = createThread();
        final String commentId = store.findThread( java.util.UUID.fromString( threadId ) )
                .orElseThrow().comments().get( 0 ).id().toString();
        // Not the author, but a moderator (hasPagePermission(..., "delete") => true).
        Mockito.doReturn( "mallory" ).when( servlet ).currentUser( Mockito.any() );
        Mockito.doReturn( true ).when( servlet ).hasPagePermission(
                Mockito.any(), Mockito.eq( "PageOne" ), Mockito.eq( "delete" ) );
        final JsonObject res = del( "/" + threadId + "/comments/" + commentId );
        assertFalse( res.has( "error" ), res.toString() );
        assertTrue( res.get( "deleted" ).getAsBoolean() );
    }

    @Test
    void delete_by_non_author_non_moderator_is_forbidden_with_reason() throws Exception {
        final String threadId = createThread();
        final String commentId = store.findThread( java.util.UUID.fromString( threadId ) )
                .orElseThrow().comments().get( 0 ).id().toString();
        Mockito.doReturn( "mallory" ).when( servlet ).currentUser( Mockito.any() );
        Mockito.doReturn( false ).when( servlet ).hasPagePermission(
                Mockito.any(), Mockito.anyString(), Mockito.anyString() );
        final JsonObject res = del( "/" + threadId + "/comments/" + commentId );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 403, res.get( "status" ).getAsInt() );
        final String msg = res.get( "message" ).getAsString().toLowerCase();
        assertTrue( msg.contains( "author" ) || msg.contains( "moderator" ),
                "refusal must cite the reason: " + res );
    }

    @Test
    void patch_invalid_uuid_is_400() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "text", "x" );
        final JsonObject res = patch( "/not-a-uuid/comments/also-bad", body );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 400, res.get( "status" ).getAsInt() );
        assertTrue( res.get( "message" ).getAsString().contains( "Invalid id" ) );
    }

    @Test
    void thread_not_found_is_404() throws Exception {
        final JsonObject reply = new JsonObject();
        reply.addProperty( "text", "hi" );
        final JsonObject res = post( null, "/" + java.util.UUID.randomUUID() + "/comments", reply );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 404, res.get( "status" ).getAsInt() );
        assertTrue( res.get( "message" ).getAsString().toLowerCase().contains( "thread not found" ) );
    }

    @Test
    void page_for_thread_gone_is_404() throws Exception {
        final String threadId = createThread();
        // The thread resolves, but its page slug no longer exists.
        Mockito.doReturn( Optional.empty() ).when( servlet ).resolveSlug( "CID1" );
        final JsonObject reply = new JsonObject();
        reply.addProperty( "text", "hi" );
        final JsonObject res = post( null, "/" + threadId + "/comments", reply );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 404, res.get( "status" ).getAsInt() );
    }

    @Test
    void reply_missing_text_is_400() throws Exception {
        final String threadId = createThread();
        final JsonObject res = post( null, "/" + threadId + "/comments", new JsonObject() );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 400, res.get( "status" ).getAsInt() );
    }

    @Test
    void edit_nonexistent_comment_is_404() throws Exception {
        final String threadId = createThread();
        final JsonObject body = new JsonObject();
        body.addProperty( "text", "x" );
        final JsonObject res = patch(
                "/" + threadId + "/comments/" + java.util.UUID.randomUUID(), body );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 404, res.get( "status" ).getAsInt() );
    }

    @Test
    void unknown_post_route_is_404() throws Exception {
        final JsonObject res = post( null, "/" + java.util.UUID.randomUUID() + "/bogus", new JsonObject() );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 404, res.get( "status" ).getAsInt() );
    }

    @Test
    void unknown_patch_route_is_404() throws Exception {
        final JsonObject res = patch( "/" + java.util.UUID.randomUUID() + "/wrong", new JsonObject() );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 404, res.get( "status" ).getAsInt() );
    }

    @Test
    void unknown_delete_route_is_404() throws Exception {
        final JsonObject res = del( "/" + java.util.UUID.randomUUID() + "/wrong" );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 404, res.get( "status" ).getAsInt() );
    }

    @Test
    void list_filters_by_status_open_and_resolved() throws Exception {
        final String openId = createThread();
        final String resolvedId = createThread();
        assertFalse( post( null, "/" + resolvedId + "/resolve", new JsonObject() ).has( "error" ) );

        final JsonArray openThreads = get( "?page=PageOne&status=open" ).getAsJsonArray( "threads" );
        assertEquals( 1, openThreads.size() );
        assertEquals( openId, openThreads.get( 0 ).getAsJsonObject().get( "id" ).getAsString() );

        final JsonArray resolvedThreads = get( "?page=PageOne&status=resolved" ).getAsJsonArray( "threads" );
        assertEquals( 1, resolvedThreads.size() );
        assertEquals( resolvedId, resolvedThreads.get( 0 ).getAsJsonObject().get( "id" ).getAsString() );
    }

    @Test
    void edit_by_author_succeeds() throws Exception {
        final String threadId = createThread();
        final String commentId = store.findThread( java.util.UUID.fromString( threadId ) )
                .orElseThrow().comments().get( 0 ).id().toString();
        final JsonObject body = new JsonObject();
        body.addProperty( "text", "corrected" );
        final JsonObject res = patch( "/" + threadId + "/comments/" + commentId, body );
        assertFalse( res.has( "error" ), res.toString() );
        assertEquals( "corrected", res.get( "body" ).getAsString() );
        assertFalse( res.get( "editedAt" ).isJsonNull() );
    }

    @Test
    void edit_missing_text_is_400() throws Exception {
        final String threadId = createThread();
        final String commentId = store.findThread( java.util.UUID.fromString( threadId ) )
                .orElseThrow().comments().get( 0 ).id().toString();
        final JsonObject res = patch( "/" + threadId + "/comments/" + commentId, new JsonObject() );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 400, res.get( "status" ).getAsInt() );
    }

    @Test
    void delete_comment_from_a_different_thread_is_not_found() throws Exception {
        final String threadA = createThread();
        final String threadB = createThread();
        final String threadBCommentId = store.findThread( java.util.UUID.fromString( threadB ) )
                .orElseThrow().comments().get( 0 ).id().toString();
        final JsonObject res = del( "/" + threadA + "/comments/" + threadBCommentId );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 404, res.get( "status" ).getAsInt() );
    }

    @Test
    void delete_nonexistent_comment_is_404() throws Exception {
        final String threadId = createThread();
        final JsonObject res = del( "/" + threadId + "/comments/" + java.util.UUID.randomUUID() );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 404, res.get( "status" ).getAsInt() );
    }

    // ---- thread-delete (moderator-only) ----

    @Test
    void delete_thread_by_moderator_succeeds_and_cascades() throws Exception {
        final String threadId = createThread();
        // setUp stubs checkPagePermission(any,any,any,any) -> true, so the "delete" check passes.
        // Add a reply first to prove the cascade really removes everything.
        final JsonObject reply = new JsonObject();
        reply.addProperty( "text", "still relevant" );
        post( null, "/" + threadId + "/comments", reply );

        final JsonObject res = del( "/" + threadId );
        assertFalse( res.has( "error" ), res.toString() );
        assertTrue( res.get( "deleted" ).getAsBoolean() );
        assertEquals( threadId, res.get( "id" ).getAsString() );
        // Thread + all its comments are gone from the store.
        assertTrue( store.findThread( java.util.UUID.fromString( threadId ) ).isEmpty() );
        assertTrue( store.listByCanonicalId( "CID1", "all" ).isEmpty() );
    }

    @Test
    void delete_thread_denied_when_caller_lacks_page_delete_permission() throws Exception {
        final String threadId = createThread();
        // Override the blanket-true stub specifically for the "delete" action.
        Mockito.doReturn( false ).when( servlet ).checkPagePermission(
                Mockito.any(), Mockito.any(), Mockito.eq( "PageOne" ), Mockito.eq( "delete" ) );
        del( "/" + threadId );
        // Authz blocked the delete: thread is still in the store.
        assertTrue( store.findThread( java.util.UUID.fromString( threadId ) ).isPresent() );
        // And the resource really asked for the "delete" permission on the page.
        Mockito.verify( servlet, Mockito.atLeastOnce() ).checkPagePermission(
                Mockito.any(), Mockito.any(), Mockito.eq( "PageOne" ), Mockito.eq( "delete" ) );
    }

    @Test
    void delete_thread_unknown_id_is_404() throws Exception {
        final String missing = java.util.UUID.randomUUID().toString();
        final JsonObject res = del( "/" + missing );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 404, res.get( "status" ).getAsInt() );
    }

    @Test
    void get_missing_page_param_is_400() throws Exception {
        final JsonObject res = get( "" );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 400, res.get( "status" ).getAsInt() );
    }

    @Test
    void get_view_forbidden_short_circuits() throws Exception {
        // checkPagePermission returns false (the real impl would have already sent 403);
        // the handler must short-circuit and not write a threads payload.
        Mockito.doReturn( false ).when( servlet ).checkPagePermission(
                Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString() );
        final JsonObject res = get( "?page=PageOne&status=all" );
        assertFalse( res.has( "threads" ), res.toString() );
    }

    @Test
    void get_unknown_page_is_404() throws Exception {
        Mockito.doReturn( Optional.empty() ).when( servlet ).resolveCanonicalId( "Ghost" );
        final JsonObject res = get( "?page=Ghost&status=all" );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 404, res.get( "status" ).getAsInt() );
    }

    @Test
    void create_missing_page_param_is_400() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "exact", "hello" );
        body.addProperty( "text", "hi" );
        final JsonObject res = post( "", null, body );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 400, res.get( "status" ).getAsInt() );
    }

    @Test
    void create_blank_exact_is_400() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "exact", "   " );
        body.addProperty( "text", "hi" );
        final JsonObject res = post( "?page=PageOne", null, body );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 400, res.get( "status" ).getAsInt() );
    }

    @Test
    void reply_forbidden_on_thread_short_circuits() throws Exception {
        final String threadId = createThread();
        // Permit the create above, then deny the comment action on the resolved thread.
        Mockito.doReturn( false ).when( servlet ).checkPagePermission(
                Mockito.any(), Mockito.any(), Mockito.eq( "PageOne" ), Mockito.eq( "comment" ) );
        final JsonObject reply = new JsonObject();
        reply.addProperty( "text", "hi" );
        final JsonObject res = post( null, "/" + threadId + "/comments", reply );
        assertFalse( res.has( "id" ), res.toString() );
    }

    @Test
    void create_thread_with_an_at_mention_records_a_direct_mention() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "exact", "hello" );
        body.addProperty( "text", "ping @bob" );
        final String threadId = post( "?page=PageOne", null, body ).get( "id" ).getAsString();
        final java.util.UUID commentId = store.findThread( java.util.UUID.fromString( threadId ) )
                .orElseThrow().comments().get( 0 ).id();
        final var rows = mentionService.findByComment( commentId );
        assertEquals( 1, rows.size() );
        assertEquals( "bob", rows.get( 0 ).mentionedLogin() );
        assertFalse( rows.get( 0 ).isOwnerMention() );
    }

    @Test
    void create_thread_writes_owner_mention_when_owner_differs_from_author() throws Exception {
        Mockito.when( pageOwnerSvc.getOwner( "CID1" ) ).thenReturn( "carol" );
        final JsonObject body = new JsonObject();
        body.addProperty( "exact", "hello" );
        body.addProperty( "text", "fyi" );
        final String threadId = post( "?page=PageOne", null, body ).get( "id" ).getAsString();
        final java.util.UUID commentId = store.findThread( java.util.UUID.fromString( threadId ) )
                .orElseThrow().comments().get( 0 ).id();
        final var rows = mentionService.findByComment( commentId );
        assertEquals( 1, rows.size() );
        assertEquals( "carol", rows.get( 0 ).mentionedLogin() );
        assertTrue( rows.get( 0 ).isOwnerMention() );
    }

    @Test
    void edit_diff_preserves_read_state_on_surviving_mentions() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "exact", "hello" );
        body.addProperty( "text", "@bob @carol" );
        final String threadId = post( "?page=PageOne", null, body ).get( "id" ).getAsString();
        final java.util.UUID commentId = store.findThread( java.util.UUID.fromString( threadId ) )
                .orElseThrow().comments().get( 0 ).id();
        final var bobMention = mentionService.findByComment( commentId ).stream()
                .filter( m -> m.mentionedLogin().equals( "bob" ) ).findFirst().orElseThrow();
        mentionService.markRead( bobMention.id(), "bob" );

        // Edit to remove @carol.
        final JsonObject editBody = new JsonObject();
        editBody.addProperty( "text", "@bob only" );
        patch( "/" + threadId + "/comments/" + commentId, editBody );

        final var rows = mentionService.findByComment( commentId );
        assertEquals( 1, rows.size() );
        assertEquals( "bob", rows.get( 0 ).mentionedLogin() );
        assertNotNull( rows.get( 0 ).readAt(), "bob's read_at preserved across the edit" );
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

    private JsonObject del( final String pathInfo ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/comment-threads" );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        return invoke( req, "DELETE", null );
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

    // ----- Real (unstubbed) subsystem-accessor seams -----
    //
    // Every other test in this file stubs commentStore()/mentionService()/
    // pageOwnerService()/resolveCanonicalId()/resolveSlug() on a spy, so the real
    // one-line bodies (delegating to getSubsystems()) are never exercised. These
    // tests use a plain instance with a mocked WikiSubsystems to pin the actual
    // delegation without needing a fully-wired persistence subsystem.

    @Test
    void commentStoreDelegatesToPersistenceSubsystem() {
        final CommentStore expected = Mockito.mock( CommentStore.class );
        final PersistenceSubsystem.Services persistence = Mockito.mock( PersistenceSubsystem.Services.class );
        Mockito.when( persistence.comments() ).thenReturn( expected );
        final CommentThreadResource plain = new CommentThreadResource() {
            @Override protected WikiSubsystems getSubsystems() {
                final WikiSubsystems subs = Mockito.mock( WikiSubsystems.class );
                Mockito.when( subs.persistence() ).thenReturn( persistence );
                return subs;
            }
        };

        assertSame( expected, plain.commentStore() );
    }

    @Test
    void mentionServiceDelegatesToPersistenceSubsystem() {
        final MentionService expected = Mockito.mock( MentionService.class );
        final PersistenceSubsystem.Services persistence = Mockito.mock( PersistenceSubsystem.Services.class );
        Mockito.when( persistence.mentions() ).thenReturn( expected );
        final CommentThreadResource plain = new CommentThreadResource() {
            @Override protected WikiSubsystems getSubsystems() {
                final WikiSubsystems subs = Mockito.mock( WikiSubsystems.class );
                Mockito.when( subs.persistence() ).thenReturn( persistence );
                return subs;
            }
        };

        assertSame( expected, plain.mentionService() );
    }

    @Test
    void pageOwnerServiceDelegatesToPersistenceSubsystem() {
        final PageOwnerService expected = Mockito.mock( PageOwnerService.class );
        final PersistenceSubsystem.Services persistence = Mockito.mock( PersistenceSubsystem.Services.class );
        Mockito.when( persistence.pageOwners() ).thenReturn( expected );
        final CommentThreadResource plain = new CommentThreadResource() {
            @Override protected WikiSubsystems getSubsystems() {
                final WikiSubsystems subs = Mockito.mock( WikiSubsystems.class );
                Mockito.when( subs.persistence() ).thenReturn( persistence );
                return subs;
            }
        };

        assertSame( expected, plain.pageOwnerService() );
    }

    @Test
    void resolveCanonicalIdDelegatesToStructuralIndexService() {
        final PageGraphSubsystem.Services pageGraph = Mockito.mock( PageGraphSubsystem.Services.class );
        final com.wikantik.api.pagegraph.StructuralIndexService sis =
                Mockito.mock( com.wikantik.api.pagegraph.StructuralIndexService.class );
        Mockito.when( pageGraph.structuralIndexService() ).thenReturn( sis );
        Mockito.when( sis.resolveCanonicalIdFromSlug( "PageOne" ) ).thenReturn( Optional.of( "CID1" ) );
        final CommentThreadResource plain = new CommentThreadResource() {
            @Override protected WikiSubsystems getSubsystems() {
                final WikiSubsystems subs = Mockito.mock( WikiSubsystems.class );
                Mockito.when( subs.pageGraph() ).thenReturn( pageGraph );
                return subs;
            }
        };

        assertEquals( Optional.of( "CID1" ), plain.resolveCanonicalId( "PageOne" ) );
    }

    @Test
    void resolveSlugDelegatesToStructuralIndexService() {
        final PageGraphSubsystem.Services pageGraph = Mockito.mock( PageGraphSubsystem.Services.class );
        final com.wikantik.api.pagegraph.StructuralIndexService sis =
                Mockito.mock( com.wikantik.api.pagegraph.StructuralIndexService.class );
        Mockito.when( pageGraph.structuralIndexService() ).thenReturn( sis );
        Mockito.when( sis.resolveSlugFromCanonicalId( "CID1" ) ).thenReturn( Optional.of( "PageOne" ) );
        final CommentThreadResource plain = new CommentThreadResource() {
            @Override protected WikiSubsystems getSubsystems() {
                final WikiSubsystems subs = Mockito.mock( WikiSubsystems.class );
                Mockito.when( subs.pageGraph() ).thenReturn( pageGraph );
                return subs;
            }
        };

        assertEquals( Optional.of( "PageOne" ), plain.resolveSlug( "CID1" ) );
    }
}
