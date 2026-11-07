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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.collab.CollabAudit;
import com.wikantik.collab.CollabConfig;
import com.wikantik.collab.CollabMetrics;
import com.wikantik.collab.CollabPermissions;
import com.wikantik.collab.CollabPorts;
import com.wikantik.collab.CollabSession;
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import com.wikantik.collab.text.ChangeSet;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /admin/collab/drafts} (MultiEdit Phase 3 Task 7) over a real {@link CollabSessionRegistry} on the Phase 0 test
 * doubles: the drafts list with logins and display names, and the purge, which passes the administrator's login as the
 * admin key.
 */
class AdminCollabResourceTest {

    private static final String PAGE = "PageName";
    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );

    private ScriptedPageWriter writer;
    private CollabSessionRegistry registry;
    private AdminCollabResource resource;
    private final List< String > purgedBy = new ArrayList<>();

    private final CollabPermissions allow = new CollabPermissions() {
        @Override
        public boolean canEdit( final Object participantSession, final String pageName ) {
            return true;
        }

        @Override
        public boolean canCreate( final Object participantSession, final String pageName ) {
            return true;
        }
    };

    @BeforeEach
    void setUp() {
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, "Hello world", Map.of() );
        final CollabAudit audit = new CollabAudit() {
            @Override
            public void purged( final UUID session, final String page, final String adminKey, final boolean expired ) {
                purgedBy.add( adminKey );
            }
        };
        registry = new CollabSessionRegistry( new CollabPorts( new InMemoryCollabStore(), writer,
            key -> key.substring( 0, 1 ).toUpperCase() + key.substring( 1 ), allow, new MutableClock( T0 ), audit,
            CollabMetrics.NONE ), new CollabConfig( true, 20, 65536, 25, 45, 30, 100, 500, 10, 0, 1000 ),
            CollabSessionRegistry.DEFAULT_MAX_PAGE_BYTES );
        resource = new AdminCollabResource( registry, login -> "Display " + login, request -> "root" );
    }

    /** A recorded response. */
    static final class Resp {
        final HttpServletResponse mock = mock( HttpServletResponse.class );
        final StringWriter out = new StringWriter();
        final Map< String, String > headers = new HashMap<>();
        int status = 200;

        Resp() throws Exception {
            when( mock.getWriter() ).thenReturn( new PrintWriter( out, true ) );
            doAnswer( i -> {
                status = i.getArgument( 0 );
                return null;
            } ).when( mock ).setStatus( anyInt() );
            doAnswer( i -> {
                headers.put( i.getArgument( 0 ), i.getArgument( 1 ) );
                return null;
            } ).when( mock ).setHeader( anyString(), anyString() );
        }

        JsonObject body() {
            final String s = out.toString();
            return s.isEmpty() ? null : JsonParser.parseString( s ).getAsJsonObject();
        }
    }

    private Resp call( final String method, final String pathInfo ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getMethod() ).thenReturn( method );
        when( req.getProtocol() ).thenReturn( "HTTP/1.1" );
        when( req.getPathInfo() ).thenReturn( pathInfo );
        when( req.getRequestURI() ).thenReturn( "/admin/collab" + pathInfo );
        final Resp r = new Resp();
        resource.service( req, r.mock );
        return r;
    }

    private CollabSessionRegistry.Joined join( final String user ) {
        return ( CollabSessionRegistry.Joined ) registry.join( PAGE, JoinRequest.of( user, user, null ) );
    }

    private static void type( final CollabSession s, final JoinResponse who, final String user ) {
        final int len = s.view().doc().length();
        assertTrue( s.push( who.participantId(), user, s.view().head(),
            List.of( new Update( who.clientId(), ChangeSet.replace( len, len, len, "!" ) ) ) ).ok() );
    }

    @Test
    void listsDraftsWithDisplayNamesAndLogins() throws Exception {
        final CollabSessionRegistry.Joined ada = join( "ada" );
        type( ada.session(), ada.response(), "ada" );

        final Resp r = call( "GET", "/drafts" );
        assertEquals( 200, r.status, () -> r.out.toString() );
        final JsonArray drafts = r.body().getAsJsonArray( "drafts" );
        assertEquals( 1, drafts.size(), () -> r.out.toString() );
        final JsonObject d = drafts.get( 0 ).getAsJsonObject();
        assertEquals( Set.of( "sessionId", "pageName", "status", "createdBy", "createdByName", "createdAt",
            "lastEditAt", "live", "participants", "publishPending" ), d.keySet() );
        assertEquals( ada.session().id().toString(), d.get( "sessionId" ).getAsString() );
        assertEquals( PAGE, d.get( "pageName" ).getAsString() );
        assertEquals( "active", d.get( "status" ).getAsString() );
        assertEquals( "ada", d.get( "createdBy" ).getAsString(), "the login, for the administrator" );
        assertEquals( "Display ada", d.get( "createdByName" ).getAsString() );
        Instant.parse( d.get( "createdAt" ).getAsString() );   // the store stamps it: an ISO-8601 instant
        Instant.parse( d.get( "lastEditAt" ).getAsString() );   // edited: an ISO-8601 instant, never null
        assertTrue( d.get( "live" ).getAsBoolean() );
        assertEquals( 1, d.get( "participants" ).getAsInt() );
        assertFalse( d.get( "publishPending" ).getAsBoolean() );
    }

    @Test
    void anEmptyListIsAnEmptyArray() throws Exception {
        final Resp r = call( "GET", "/drafts" );
        assertEquals( 200, r.status );
        assertEquals( 0, r.body().getAsJsonArray( "drafts" ).size() );
    }

    @Test
    void deletePurges() throws Exception {
        final CollabSessionRegistry.Joined ada = join( "ada" );
        type( ada.session(), ada.response(), "ada" );
        final UUID id = ada.session().id();

        final Resp r = call( "DELETE", "/drafts/" + id );
        assertEquals( 204, r.status, () -> r.out.toString() );
        assertEquals( "", r.out.toString(), "no body" );
        assertTrue( registry.live( id ).isEmpty(), "gone from memory" );
        assertEquals( List.of( "root" ), purgedBy, "the administrator's login is the admin key" );
        final PushResponse after = ada.session().push( ada.response().participantId(), "ada", 1, List.of() );
        assertEquals( 403, after.status() );
        assertEquals( CollabSessionRegistry.PURGED, after.detail() );
        assertEquals( 0, call( "GET", "/drafts" ).body().getAsJsonArray( "drafts" ).size() );
    }

    @Test
    void deleteDuringPublishIs423() throws Exception {
        final CollabSessionRegistry.Joined ada = join( "ada" );
        type( ada.session(), ada.response(), "ada" );
        final ScriptedPageWriter.PauseHandle pause = writer.pauseNextPublish();
        final CompletableFuture< Integer > publish = CompletableFuture.supplyAsync( () -> ada.session().publish(
            ada.response().participantId(), "ada", ada.session().view().head(), "note" ).status() );
        pause.awaitEntered( Duration.ofSeconds( 10 ) );

        final Resp r = call( "DELETE", "/drafts/" + ada.session().id() );
        assertEquals( 423, r.status, () -> r.out.toString() );
        assertEquals( "publish-in-progress", r.body().get( "reason" ).getAsString() );
        pause.release();
        assertEquals( 200, publish.get( 10, TimeUnit.SECONDS ) );
        assertTrue( purgedBy.isEmpty() );
    }

    @Test
    void deleteUnknownIs404() throws Exception {
        final Resp unknown = call( "DELETE", "/drafts/" + UUID.randomUUID() );
        assertEquals( 404, unknown.status );
        assertEquals( "unknown", unknown.body().get( "reason" ).getAsString() );
        assertEquals( 404, call( "DELETE", "/drafts/not-a-uuid" ).status );
        assertEquals( 404, call( "DELETE", "/drafts" ).status );
        assertEquals( 404, call( "GET", "/nope" ).status );
        assertTrue( purgedBy.isEmpty() );
    }

    @Test
    void withoutARegistryIs503() throws Exception {
        resource = new AdminCollabResource( null, login -> login, request -> "root" );
        final Resp r = call( "GET", "/drafts" );
        assertEquals( 503, r.status );
        assertEquals( "collab-disabled", r.body().get( "reason" ).getAsString() );
    }

    @Test
    void responsesAreNoStore() throws Exception {
        final CollabSessionRegistry.Joined ada = join( "ada" );
        final List< Resp > all = List.of( call( "GET", "/drafts" ), call( "DELETE", "/drafts/" + UUID.randomUUID() ),
            call( "DELETE", "/drafts/" + ada.session().id() ), call( "GET", "/nope" ) );
        for( final Resp r : all ) {
            assertEquals( "no-store", r.headers.get( "Cache-Control" ), () -> r.status + " " + r.out );
        }
    }

    /** {@code /admin/collab/*} sits under {@link AdminAuthFilter}'s {@code /admin/*}: a non-admin never reaches it. */
    @Test
    void nonAdminIs403() throws Exception {
        final String xml = Files.readString( Path.of( "..", "wikantik-war", "src", "main", "webapp", "WEB-INF",
            "web.xml" ), StandardCharsets.UTF_8 );
        assertTrue( xml.contains( "<servlet-class>com.wikantik.rest.AdminCollabResource</servlet-class>" ) );
        assertTrue( xml.replaceAll( "\\s+", "" ).contains( "<servlet-name>AdminCollabResource</servlet-name>"
            + "<url-pattern>/admin/collab/*</url-pattern>" ), "mapped under /admin/*" );

        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        try {
            final AdminAuthFilter filter = new AdminAuthFilter();
            final FilterConfig fc = Mockito.mock( FilterConfig.class );
            Mockito.doReturn( engine.getServletContext() ).when( fc ).getServletContext();
            filter.init( fc );
            for( final String method : List.of( "GET", "DELETE" ) ) {
                final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( "/admin/collab/drafts" );
                Mockito.doReturn( method ).when( request ).getMethod();
                final HttpServletResponse response = HttpMockFactory.createHttpResponse();
                Mockito.doReturn( new PrintWriter( new StringWriter() ) ).when( response ).getWriter();
                final FilterChain chain = Mockito.mock( FilterChain.class );
                filter.doFilter( request, response, chain );
                verify( chain, never() ).doFilter( any(), any() );
                verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
            }
        } finally {
            engine.stop();
        }
    }

    /** A signed-in user without {@code AllPermission} is refused too, not only an anonymous caller. */
    @Test
    void anAuthenticatedNonAdminIs403() throws Exception {
        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        try {
            final AdminAuthFilter filter = new AdminAuthFilter();
            final FilterConfig fc = Mockito.mock( FilterConfig.class );
            Mockito.doReturn( engine.getServletContext() ).when( fc ).getServletContext();
            filter.init( fc );
            final com.wikantik.api.core.Session janne = engine.janneSession();   // the shared mock HTTP session
            assertTrue( janne.isAuthenticated(), "signed in (janne holds no AllPermission)" );
            for( final String method : List.of( "GET", "DELETE" ) ) {
                final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/collab/drafts" );
                Mockito.doReturn( method ).when( request ).getMethod();
                final HttpServletResponse response = HttpMockFactory.createHttpResponse();
                Mockito.doReturn( new PrintWriter( new StringWriter() ) ).when( response ).getWriter();
                final FilterChain chain = Mockito.mock( FilterChain.class );
                filter.doFilter( request, response, chain );
                verify( chain, never() ).doFilter( any(), any() );
                verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
            }
        } finally {
            engine.stop();
        }
    }
}
