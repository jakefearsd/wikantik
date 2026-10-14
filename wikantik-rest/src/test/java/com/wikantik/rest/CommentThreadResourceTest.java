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
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.auth.subsystem.AuthSubsystem;
import com.wikantik.comments.CommentStore;
import com.wikantik.comments.PageOwnerService;
import com.wikantik.comments.mentions.MentionService;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystem;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@RequiresPostgres
class CommentThreadResourceTest {

    private final Gson gson = new Gson();
    private CommentStore store;
    MentionService mentionService;
    private PageOwnerService pageOwnerSvc;
    private PublicIdentity identity;
    private CommentThreadResource servlet;

    @BeforeEach
    void setUp() throws Exception {
        final DataSource ds = PostgresTestDb.createDataSource();
        // FK order (comment_mentions -> comments -> comment_threads) doesn't matter to
        // TRUNCATE ... CASCADE, but is listed leaf-first for readability.
        PostgresTestDb.truncate( "comment_mentions", "comments", "comment_threads" );
        this.store = new CommentStore( ds );
        final Set< String > users = new HashSet<>( List.of( "alice", "bob", "carol", "admin" ) );
        this.mentionService = new MentionService( ds, users::contains );
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

        // Identity defaults: a stored principal is shown as itself and nobody has a uid. Individual tests
        // override the logins they care about (a later stubbing wins).
        this.identity = Mockito.mock( PublicIdentity.class );
        Mockito.when( identity.displayName( Mockito.any() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        Mockito.when( identity.userIdForLogin( Mockito.any() ) ).thenReturn( Optional.empty() );
        Mockito.when( identity.loginForUserId( Mockito.any() ) ).thenReturn( Optional.empty() );
        Mockito.doReturn( identity ).when( servlet ).publicIdentity();
    }

    // ---- display names on the wire (logins stay in storage) ----

    /** alice's login is "alice"; everyone else sees "Alice A" and the opaque uid "uid-0001". */
    private void aliceIsShownAsAliceA() {
        Mockito.when( identity.displayName( "alice" ) ).thenReturn( "Alice A" );
        Mockito.when( identity.userIdForLogin( "alice" ) ).thenReturn( Optional.of( "uid-0001" ) );
    }

    @Test
    void get_returns_display_names_and_uids_never_the_login() throws Exception {
        aliceIsShownAsAliceA();
        final String threadId = createThread();
        post( null, "/" + threadId + "/comments", textBody( "a reply" ) );
        post( null, "/" + threadId + "/resolve", new JsonObject() );

        final JsonObject list = get( "?page=PageOne&status=all" );
        final JsonObject thread = list.getAsJsonArray( "threads" ).get( 0 ).getAsJsonObject();
        assertEquals( "Alice A", thread.get( "createdBy" ).getAsString() );
        assertEquals( "uid-0001", thread.get( "createdById" ).getAsString() );
        assertEquals( "Alice A", thread.get( "resolvedBy" ).getAsString() );
        assertEquals( "uid-0001", thread.get( "resolvedById" ).getAsString() );
        final JsonArray comments = thread.getAsJsonArray( "comments" );
        assertEquals( 2, comments.size() );
        for ( int i = 0; i < comments.size(); i++ ) {
            final JsonObject c = comments.get( i ).getAsJsonObject();
            assertEquals( "Alice A", c.get( "author" ).getAsString() );
            assertEquals( "uid-0001", c.get( "authorId" ).getAsString() );
        }
        assertFalse( list.toString().contains( "\"alice\"" ), "the login must not appear anywhere: " + list );
    }

    @Test
    void an_author_who_is_not_a_member_has_no_authorId_and_keeps_the_stored_label() throws Exception {
        // A legacy comment whose author was a plain name rather than a login.
        store.createThread( "CID1", new com.wikantik.api.comments.TextQuoteSelector( "hello", null, null ),
                "Legacy Name", "old comment", mentionService, Optional.empty() );

        final JsonObject c = get( "?page=PageOne&status=all" ).getAsJsonArray( "threads" ).get( 0 )
                .getAsJsonObject().getAsJsonArray( "comments" ).get( 0 ).getAsJsonObject();
        assertEquals( "Legacy Name", c.get( "author" ).getAsString() );
        assertFalse( c.has( "authorId" ), "no uid for a non-member: " + c );
    }

    @Test
    void an_unresolved_thread_has_no_resolvedBy_fields() throws Exception {
        aliceIsShownAsAliceA();
        createThread();
        final JsonObject thread = get( "?page=PageOne&status=all" ).getAsJsonArray( "threads" ).get( 0 )
                .getAsJsonObject();
        assertFalse( thread.has( "resolvedBy" ), thread.toString() );
        assertFalse( thread.has( "resolvedById" ), thread.toString() );
    }

    @Test
    void mutation_responses_use_display_names_too() throws Exception {
        aliceIsShownAsAliceA();
        final JsonObject created = post( "?page=PageOne", null, createBody( "first" ) );
        assertEquals( "Alice A", created.get( "createdBy" ).getAsString() );
        assertEquals( "uid-0001", created.get( "createdById" ).getAsString() );
        assertEquals( "Alice A", created.getAsJsonArray( "comments" ).get( 0 ).getAsJsonObject()
                .get( "author" ).getAsString() );

        final JsonObject reply = post( null, "/" + created.get( "id" ).getAsString() + "/comments",
                textBody( "a reply" ) );
        assertEquals( "Alice A", reply.get( "author" ).getAsString() );
        assertEquals( "uid-0001", reply.get( "authorId" ).getAsString() );
    }

    @Test
    void get_carries_a_mentions_map_covering_every_token_in_the_returned_bodies() throws Exception {
        Mockito.when( identity.loginForUserId( "uid-bob" ) ).thenReturn( Optional.of( "bob" ) );
        Mockito.when( identity.displayName( "bob" ) ).thenReturn( "Bob B" );
        Mockito.when( identity.loginForUserId( "uid-carol" ) ).thenReturn( Optional.of( "carol" ) );
        Mockito.when( identity.displayName( "carol" ) ).thenReturn( "Carol C" );

        final String threadId = post( "?page=PageOne", null,
                createBody( "ping @[Bobby](user:uid-bob) and @[Ghost](user:uid-gone)" ) ).get( "id" ).getAsString();
        assertFalse( post( null, "/" + threadId + "/comments", textBody( "cc @[Carol](user:uid-carol)" ) )
                .has( "error" ) );

        final JsonObject mentions = get( "?page=PageOne&status=all" ).getAsJsonObject( "mentions" );
        assertEquals( "Bob B", mentions.get( "uid-bob" ).getAsString(), "current name, not the 'Bobby' snapshot" );
        assertEquals( "Carol C", mentions.get( "uid-carol" ).getAsString() );
        assertFalse( mentions.has( "uid-gone" ), "an unknown uid is left out; the client falls back to the snapshot" );
        assertEquals( 2, mentions.size() );
    }

    @Test
    void mentions_map_is_present_and_empty_when_no_body_has_a_token() throws Exception {
        createThread();
        final JsonObject res = get( "?page=PageOne&status=all" );
        assertTrue( res.has( "mentions" ), res.toString() );
        assertEquals( 0, res.getAsJsonObject( "mentions" ).size() );
    }

    @Test
    void mentions_map_ignores_legacy_at_logins_and_malformed_tokens() throws Exception {
        Mockito.when( identity.loginForUserId( Mockito.any() ) ).thenReturn( Optional.of( "bob" ) );
        post( "?page=PageOne", null, createBody( "legacy @bob and malformed @[Bob](user:) and @[](user:u1)" ) );
        assertEquals( 0, get( "?page=PageOne&status=all" ).getAsJsonObject( "mentions" ).size() );
        Mockito.verify( identity, Mockito.never() ).loginForUserId( Mockito.any() );
    }

    @Test
    void edit_and_delete_authorisation_still_compare_logins_not_display_names() throws Exception {
        // mallory and alice share the display name "Alice A" (legacy duplicates exist). Authorship is
        // decided on the stored login, so mallory must be refused although the names match.
        Mockito.when( identity.displayName( "alice" ) ).thenReturn( "Alice A" );
        Mockito.when( identity.displayName( "mallory" ) ).thenReturn( "Alice A" );
        final String threadId = createThread();
        final String commentId = store.findThread( java.util.UUID.fromString( threadId ) )
                .orElseThrow().comments().get( 0 ).id().toString();
        Mockito.doReturn( "mallory" ).when( servlet ).currentUser( Mockito.any() );
        Mockito.doReturn( false ).when( servlet ).hasPagePermission(
                Mockito.any(), Mockito.anyString(), Mockito.anyString() );

        final JsonObject edit = patch( "/" + threadId + "/comments/" + commentId, textBody( "hijack" ) );
        assertEquals( 403, edit.get( "status" ).getAsInt(), edit.toString() );
        final JsonObject delete = del( "/" + threadId + "/comments/" + commentId );
        assertEquals( 403, delete.get( "status" ).getAsInt(), delete.toString() );
        assertTrue( store.findComment( java.util.UUID.fromString( commentId ) ).isPresent() );

        // The real author, still identified by login, may edit.
        Mockito.doReturn( "alice" ).when( servlet ).currentUser( Mockito.any() );
        final JsonObject ok = patch( "/" + threadId + "/comments/" + commentId, textBody( "corrected" ) );
        assertFalse( ok.has( "error" ), ok.toString() );
        assertEquals( "alice", store.findComment( java.util.UUID.fromString( commentId ) ).orElseThrow().author(),
                "storage keeps the login" );
    }

    @Test
    void new_comments_and_resolutions_are_stored_under_the_login() throws Exception {
        aliceIsShownAsAliceA();
        final String threadId = createThread();
        post( null, "/" + threadId + "/resolve", new JsonObject() );
        final var stored = store.findThread( java.util.UUID.fromString( threadId ) ).orElseThrow();
        assertEquals( "alice", stored.createdBy() );
        assertEquals( "alice", stored.resolvedBy() );
        assertEquals( "alice", stored.comments().get( 0 ).author() );
    }

    private static JsonObject textBody( final String text ) {
        final JsonObject body = new JsonObject();
        body.addProperty( "text", text );
        return body;
    }

    private static JsonObject createBody( final String text ) {
        final JsonObject body = new JsonObject();
        body.addProperty( "exact", "hello" );
        body.addProperty( "text", text );
        return body;
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
    void publicIdentityDelegatesToAuthSubsystem() {
        final PublicIdentity expected = Mockito.mock( PublicIdentity.class );
        final AuthSubsystem.Services auth = Mockito.mock( AuthSubsystem.Services.class );
        Mockito.when( auth.publicIdentity() ).thenReturn( expected );
        final CommentThreadResource plain = new CommentThreadResource() {
            @Override protected WikiSubsystems getSubsystems() {
                final WikiSubsystems subs = Mockito.mock( WikiSubsystems.class );
                Mockito.when( subs.auth() ).thenReturn( auth );
                return subs;
            }
        };

        assertSame( expected, plain.publicIdentity() );
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
