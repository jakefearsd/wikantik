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
import com.wikantik.api.comments.MentionFeedItem;
import com.wikantik.comments.mentions.MentionFeedDao;
import com.wikantik.comments.mentions.MentionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MyMentionsResourceTest {

    private final Gson gson = new Gson();
    private MentionFeedDao feed;
    private MentionService svc;
    private MyMentionsResource servlet;

    @BeforeEach
    void setUp() {
        feed = Mockito.mock( MentionFeedDao.class );
        svc = Mockito.mock( MentionService.class );

        servlet = Mockito.spy( new MyMentionsResource() );
        Mockito.doReturn( feed ).when( servlet ).mentionFeed();
        Mockito.doReturn( svc ).when( servlet ).mentionService();
        Mockito.doReturn( true ).when( servlet ).isAuthenticated( Mockito.any() );
        Mockito.doReturn( "alice" ).when( servlet ).currentUser( Mockito.any() );
        Mockito.doReturn( Optional.of( "FooPage" ) ).when( servlet ).resolveSlug( Mockito.anyString() );
    }

    // ---- 1, 2: auth gating ----

    @Test
    void unauthenticated_GET_returns_401() throws Exception {
        Mockito.doReturn( false ).when( servlet ).isAuthenticated( Mockito.any() );
        final JsonObject res = invokeGet( null, null );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 401, res.get( "status" ).getAsInt() );
    }

    @Test
    void unauthenticated_POST_returns_401() throws Exception {
        Mockito.doReturn( false ).when( servlet ).isAuthenticated( Mockito.any() );
        final JsonObject res = invokePost( "/mark-all-read" );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 401, res.get( "status" ).getAsInt() );
    }

    // ---- 3: list endpoint shape ----

    @Test
    void list_returns_mentions_for_currentUser() throws Exception {
        final UUID id = UUID.randomUUID();
        final UUID threadId = UUID.randomUUID();
        final UUID commentId = UUID.randomUUID();
        final Instant when = Instant.parse( "2026-05-01T12:00:00Z" );
        final MentionFeedItem item = new MentionFeedItem(
                id, threadId, commentId, "CID-123", null,
                "hello @alice", "bob", false, when, null );
        Mockito.when( feed.list( Mockito.eq( "alice" ), Mockito.eq( MentionFeedDao.Status.ALL ),
                Mockito.anyInt(), Mockito.any() ) ).thenReturn( List.of( item ) );

        final JsonObject res = invokeGet( null, null );
        final JsonArray mentions = res.getAsJsonArray( "mentions" );
        assertEquals( 1, mentions.size() );
        final JsonObject m = mentions.get( 0 ).getAsJsonObject();
        assertEquals( id.toString(), m.get( "id" ).getAsString() );
        assertEquals( threadId.toString(), m.get( "threadId" ).getAsString() );
        assertEquals( commentId.toString(), m.get( "commentId" ).getAsString() );
        assertEquals( "CID-123", m.get( "canonicalId" ).getAsString() );
        assertEquals( "FooPage", m.get( "pageName" ).getAsString() );
        assertEquals( "hello @alice", m.get( "snippet" ).getAsString() );
        assertEquals( "bob", m.get( "mentionedBy" ).getAsString() );
        assertFalse( m.get( "isOwnerMention" ).getAsBoolean() );
        assertEquals( "2026-05-01T12:00:00Z", m.get( "mentionedAt" ).getAsString() );
        // Gson omits nulls by default; readAt is null in our fixture so either
        // absent or JsonNull is acceptable.
        assertTrue( !m.has( "readAt" ) || m.get( "readAt" ).isJsonNull() );
    }

    // ---- 4: status=unread maps to UNREAD ----

    @Test
    void list_with_status_unread_passes_UNREAD_to_dao() throws Exception {
        Mockito.when( feed.list( Mockito.anyString(), Mockito.any(), Mockito.anyInt(), Mockito.any() ) )
                .thenReturn( List.of() );
        invokeGet( "unread", null );
        final ArgumentCaptor< MentionFeedDao.Status > status =
                ArgumentCaptor.forClass( MentionFeedDao.Status.class );
        Mockito.verify( feed ).list( Mockito.eq( "alice" ), status.capture(), Mockito.anyInt(), Mockito.any() );
        assertEquals( MentionFeedDao.Status.UNREAD, status.getValue() );
    }

    // ---- 5: unread-count ----

    @Test
    void unread_count_returns_count() throws Exception {
        Mockito.when( svc.unreadCount( "alice" ) ).thenReturn( 7 );
        final JsonObject res = invokeGetPath( "/unread-count" );
        assertEquals( 7, res.get( "count" ).getAsInt() );
    }

    // ---- 6: mark-one ----

    @Test
    void mark_one_read_ok_when_service_returns_true() throws Exception {
        final UUID id = UUID.randomUUID();
        Mockito.when( svc.markRead( id, "alice" ) ).thenReturn( true );
        final JsonObject res = invokePost( "/" + id + "/read" );
        assertTrue( res.get( "ok" ).getAsBoolean() );
    }

    @Test
    void mark_one_read_403_when_service_returns_false() throws Exception {
        final UUID id = UUID.randomUUID();
        Mockito.when( svc.markRead( id, "alice" ) ).thenReturn( false );
        final JsonObject res = invokePost( "/" + id + "/read" );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 403, res.get( "status" ).getAsInt() );
    }

    // ---- 7: mark-all ----

    @Test
    void mark_all_read_returns_updated_count() throws Exception {
        Mockito.when( svc.markAllRead( "alice" ) ).thenReturn( 3 );
        final JsonObject res = invokePost( "/mark-all-read" );
        assertEquals( 3, res.get( "updated" ).getAsInt() );
    }

    // ---- 8: invalid UUID ----

    @Test
    void mark_one_read_invalid_uuid_returns_400() throws Exception {
        final JsonObject res = invokePost( "/not-a-uuid/read" );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 400, res.get( "status" ).getAsInt() );
    }

    // ---- helpers ----

    private JsonObject invokeGet( final String status, final String before ) throws Exception {
        return invokeGet( status, before, null, null );
    }

    private JsonObject invokeGet( final String status, final String before,
                                  final String limit, final String pathInfo ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/me/mentions" );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        Mockito.doReturn( status ).when( req ).getParameter( "status" );
        Mockito.doReturn( before ).when( req ).getParameter( "before" );
        Mockito.doReturn( limit ).when( req ).getParameter( "limit" );
        Mockito.doReturn( "GET" ).when( req ).getMethod();
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        servlet.service( req, resp );
        final String body = sw.toString();
        assertNotNull( body );
        return gson.fromJson( body.isBlank() ? "{}" : body, JsonObject.class );
    }

    private JsonObject invokeGetPath( final String pathInfo ) throws Exception {
        return invokeGet( null, null, null, pathInfo );
    }

    private JsonObject invokePost( final String pathInfo ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/me/mentions" );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        Mockito.doReturn( "POST" ).when( req ).getMethod();
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        servlet.service( req, resp );
        final String body = sw.toString();
        assertNotNull( body );
        return gson.fromJson( body.isBlank() ? "{}" : body, JsonObject.class );
    }
}
