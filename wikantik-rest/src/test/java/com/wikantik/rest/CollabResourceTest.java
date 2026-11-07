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
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.exceptions.FrontmatterValidationException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.Severity;
import com.wikantik.api.exceptions.AccessRuleShapeException;
import com.wikantik.collab.CollabConfig;
import com.wikantik.collab.CollabPageWriter;
import com.wikantik.collab.CollabPermissions;
import com.wikantik.collab.CollabSession;
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishRequest;
import com.wikantik.collab.model.PullCursor;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.testing.FakeAsyncContext;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Writer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /api/collab/*} (docs/MultiEdit.MD section 6.2 as amended) over a real {@link CollabSessionRegistry} on the
 * Phase 0 test doubles, with Mockito requests/responses and {@link FakeAsyncContext} (F6). Every status the Phase 1
 * server produces is asserted against the shared protocol fixture
 * ({@code wikantik-frontend/src/collab/__fixtures__/collab-protocol.json}, ruling P2): same status, every fixture key
 * present, the same {@code reason}/{@code error}/flags. The fixture's remaining Phase 2 cases (eviction tombstones,
 * lifecycle events, no-create) are listed in {@link #DEFERRED_TO_PHASE_2}, and every fixture case must be either
 * covered here or deferred.
 */
class CollabResourceTest {

    private static final String PAGE = "PageName";
    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );
    private static final Path FIXTURE = Path.of( "..", "wikantik-frontend", "src", "collab", "__fixtures__",
        "collab-protocol.json" );

    /** The fixture cases the Phase 1 surface produces; each test asserting one names it. */
    private static final Set< String > COVERED = Set.of(
        "join.200", "join.403", "join.409.derived-page", "join.429.session-full", "join.503.collab-disabled",
        "updates.post.200", "updates.post.409.stale-base", "updates.post.409.rejoin",
        "updates.post.403.not-your-participant", "updates.post.409.frozen", "updates.post.429.rate-limited",
        "updates.post.400.rejected", "updates.post.413", "updates.post.400.bad-meta-op",
        "updates.post.200.meta-set-ops", "updates.post.200.meta-only", "updates.get.200.meta-by",
        "updates.get.200", "updates.get.200.empty", "updates.get.410", "updates.get.400.bad-since",
        "updates.get.409.rejoin",
        "publish.200", "publish.409.publish-merged-retry", "publish.409.not-synced", "publish.422", "publish.423",
        "discard.204", "leave.204", "updates.post.403.evicted", "updates.get.403.evicted", "publish.403.no-create" );

    /** Fixture cases whose server half is Phase 2 (docs/superpowers/plans/...phase2..., Task 7). */
    private static final Set< String > DEFERRED_TO_PHASE_2 = Set.of(
        "updates.get.200.lifecycle-events" );

    private InMemoryCollabStore store;
    private ScriptedPageWriter writer;
    private MutableClock clock;
    private final Set< String > noEdit = new HashSet<>();
    private final Set< String > noCreate = new HashSet<>();
    private final Set< String > noView = new HashSet<>();
    private final List< Runnable > delayed = new ArrayList<>();
    private CollabSessionRegistry registry;
    /** When set, the next call's response writes through it (then it is cleared). */
    private Writer nextWriter;
    private CollabResource resource;

    /** The wiki session of a participant is its login here. */
    private final CollabPermissions permissions = new CollabPermissions() {
        @Override
        public boolean canEdit( final Object participantSession, final String pageName ) {
            return !noEdit.contains( ( String ) participantSession );
        }

        @Override
        public boolean canCreate( final Object participantSession, final String pageName ) {
            return !noCreate.contains( ( String ) participantSession );
        }
    };

    private final CollabResource.Access access = new CollabResource.Access() {
        @Override
        public CollabResource.Caller caller( final HttpServletRequest request ) {
            final String user = request.getRemoteUser();
            return user == null ? null : new CollabResource.Caller( user, user );
        }

        @Override
        public boolean mayView( final CollabResource.Caller caller, final String pageName ) {
            return !noView.contains( caller.authorKey() );
        }

        @Override
        public boolean mayEdit( final CollabResource.Caller caller, final String pageName, final boolean create ) {
            return permissions.canEdit( caller.wikiSession(), pageName )
                && ( !create || permissions.canCreate( caller.wikiSession(), pageName ) );
        }
    };

    static CollabConfig config( final int maxParticipants, final int pushesPerSecond, final int ring,
                                final int snapshotEvery ) {
        return new CollabConfig( true, maxParticipants, 4096, 25, 45, 30, ring, snapshotEvery, 10, 0, pushesPerSecond );
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryCollabStore();
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, "Hello world", Map.of( "tags", List.of( "a" ) ) );
        clock = new MutableClock( T0 );
        use( config( 20, 1000, 100, 500 ), CollabSessionRegistry.DEFAULT_MAX_PAGE_BYTES, 0 );
    }

    private void use( final CollabConfig c, final long maxPageBytes, final long coalesceMs ) {
        use( c, maxPageBytes, coalesceMs, writer, ( ac, task ) -> task.run() );
    }

    private void use( final CollabConfig c, final long maxPageBytes, final long coalesceMs,
                      final CollabPageWriter pageWriter, final CollabPull.Dispatch dispatch ) {
        registry = new CollabSessionRegistry( store, pageWriter, permissions, key -> key.substring( 0, 1 ).toUpperCase()
            + key.substring( 1 ), clock, c, maxPageBytes );
        resource = new CollabResource( registry, access, ( ms, task ) -> delayed.add( task ), dispatch, coalesceMs );
    }

    // ---- request/response doubles -------------------------------------------------------------------------------

    /** A recorded response; the body is read when asked, so an async answer written later is seen. */
    static final class Resp {
        final HttpServletResponse mock = mock( HttpServletResponse.class );
        final StringWriter out = new StringWriter();
        final Map< String, String > headers = new HashMap<>();
        int status = 200;
        FakeAsyncContext async;

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

        String reason() {
            return body().get( "reason" ).getAsString();
        }
    }

    private Resp call( final String method, final String user, final String path, final String body,
                       final Map< String, String > query ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final Resp resp = new Resp();
        if( nextWriter != null ) {
            when( resp.mock.getWriter() ).thenReturn( new PrintWriter( nextWriter, true ) );
            nextWriter = null;
        }
        when( req.getMethod() ).thenReturn( method );
        when( req.getPathInfo() ).thenReturn( path );
        when( req.getRequestURI() ).thenReturn( "/api/collab" + path );
        when( req.getRemoteUser() ).thenReturn( user );
        when( req.getParameter( anyString() ) ).thenAnswer( i -> query.get( i.< String >getArgument( 0 ) ) );
        final byte[] bytes = body == null ? new byte[ 0 ] : body.getBytes( StandardCharsets.UTF_8 );
        when( req.getContentLengthLong() ).thenReturn( ( long ) bytes.length );
        when( req.getInputStream() ).thenReturn( stream( bytes ) );
        when( req.startAsync() ).thenAnswer( i -> {
            resp.async = new FakeAsyncContext( req, resp.mock );
            return resp.async;
        } );
        when( req.startAsync( any(), any() ) ).thenAnswer( i -> {
            resp.async = new FakeAsyncContext( req, resp.mock );
            return resp.async;
        } );
        if( "GET".equals( method ) ) {
            resource.doGet( req, resp.mock );
        } else {
            resource.doPost( req, resp.mock );
        }
        return resp;
    }

    private static ServletInputStream stream( final byte[] bytes ) {
        final ByteArrayInputStream in = new ByteArrayInputStream( bytes );
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener( final ReadListener l ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read() {
                return in.read();
            }

            @Override
            public int read( final byte[] b, final int off, final int len ) {
                return in.read( b, off, len );
            }
        };
    }

    private Resp post( final String user, final String action, final JsonObject body ) throws Exception {
        return call( "POST", user, "/" + PAGE + "/" + action, body.toString(), Map.of() );
    }

    private Resp pull( final String user, final Joined j, final long since, final long metaSince,
                       final long presenceSince, final long eventsSince ) throws Exception {
        final Map< String, String > q = new HashMap<>();
        q.put( "sessionId", j.sessionId );
        q.put( "participantId", j.participantId );
        q.put( "since", Long.toString( since ) );
        q.put( "metaSince", Long.toString( metaSince ) );
        q.put( "presenceSince", Long.toString( presenceSince ) );
        q.put( "eventsSince", Long.toString( eventsSince ) );
        return call( "GET", user, "/" + PAGE + "/updates", null, q );
    }

    private static JsonObject obj( final Object... kv ) {
        final JsonObject o = new JsonObject();
        for( int i = 0; i < kv.length; i += 2 ) {
            final Object v = kv[ i + 1 ];
            if( v == null ) {
                o.add( ( String ) kv[ i ], JsonNull.INSTANCE );
            } else if( v instanceof JsonElement e ) {
                o.add( ( String ) kv[ i ], e );
            } else if( v instanceof Number n ) {
                o.addProperty( ( String ) kv[ i ], n );
            } else if( v instanceof Boolean b ) {
                o.addProperty( ( String ) kv[ i ], b );
            } else {
                o.addProperty( ( String ) kv[ i ], v.toString() );
            }
        }
        return o;
    }

    private static JsonArray arr( final Object... items ) {
        final JsonArray a = new JsonArray();
        for( final Object i : items ) {
            if( i instanceof JsonElement e ) {
                a.add( e );
            } else if( i instanceof Number n ) {
                a.add( n );
            } else {
                a.add( i.toString() );
            }
        }
        return a;
    }

    /** A joined participant as the client holds it. */
    record Joined( String sessionId, String participantId, String clientId, JsonObject body ) {
    }

    private Joined join( final String user ) throws Exception {
        return join( user, PAGE );
    }

    private Joined join( final String user, final String page ) throws Exception {
        final Resp r = call( "POST", user, "/" + page + "/join",
            obj( "priorClientId", null, "initialText", null, "initialMeta", null ).toString(), Map.of() );
        assertEquals( 200, r.status, () -> "join: " + r.out );
        final JsonObject b = r.body();
        return new Joined( b.get( "sessionId" ).getAsString(), b.get( "participantId" ).getAsString(),
            b.get( "clientID" ).getAsString(), b );
    }

    private JsonObject pushBody( final Joined j, final int version, final JsonArray updates ) {
        return obj( "sessionId", j.sessionId, "participantId", j.participantId, "version", version, "updates", updates );
    }

    private JsonObject update( final Joined j, final Object... changes ) {
        return obj( "clientID", j.clientId, "changes", arr( changes ) );
    }

    private Resp push( final String user, final Joined j, final int version, final JsonObject... updates )
            throws Exception {
        return post( user, "updates", pushBody( j, version, arr( ( Object[] ) updates ) ) );
    }

    private JsonObject ids( final Joined j ) {
        return obj( "sessionId", j.sessionId, "participantId", j.participantId );
    }

    private CollabSession session( final Joined j ) {
        return registry.live( UUID.fromString( j.sessionId ) ).orElseThrow();
    }

    // ---- the fixture contract -------------------------------------------------------------------------------------

    private static Map< String, JsonObject > fixtureCases() throws Exception {
        final JsonObject root = JsonParser.parseString( Files.readString( FIXTURE ) ).getAsJsonObject();
        final Map< String, JsonObject > out = new LinkedHashMap<>();
        for( final JsonElement e : root.getAsJsonArray( "cases" ) ) {
            out.put( e.getAsJsonObject().get( "id" ).getAsString(), e.getAsJsonObject() );
        }
        return out;
    }

    /**
     * {@code r} matches fixture case {@code id}: the status, every key of the fixture body (plus at most
     * {@code extraKeys}), and the same reason, error, detail and flags. Pull bodies also match item shapes, tolerating
     * an optional {@code by} on meta ops and {@code recreated} on {@code external-change} (Phase 2).
     */
    private static void assertFixture( final String id, final Resp r, final String... extraKeys ) throws Exception {
        assertTrue( COVERED.contains( id ), id + " must be listed in COVERED" );
        final JsonObject c = fixtureCases().get( id );
        assertNotNull( c, "no fixture case " + id );
        final JsonObject expected = c.getAsJsonObject( "response" );
        assertEquals( expected.get( "status" ).getAsInt(), r.status, () -> id + " status; body " + r.out );
        if( expected.get( "body" ).isJsonNull() ) {
            assertNull( r.body(), id + " has no body" );
            return;
        }
        final JsonObject want = expected.getAsJsonObject( "body" );
        final JsonObject got = r.body();
        assertNotNull( got, id + " body" );
        final Set< String > missing = new TreeSet<>( want.keySet() );
        missing.removeAll( got.keySet() );
        assertTrue( missing.isEmpty(), id + " lacks " + missing + ": " + got );
        final Set< String > extra = new TreeSet<>( got.keySet() );
        extra.removeAll( want.keySet() );
        extra.removeAll( List.of( extraKeys ) );
        assertTrue( extra.isEmpty(), id + " has unexpected " + extra + ": " + got );
        for( final String k : List.of( "reason", "error", "detail", "rejoin", "resync", "accepted" ) ) {
            if( want.has( k ) ) {
                assertEquals( want.get( k ), got.get( k ), id + " " + k );
            }
        }
        if( got.has( "updates" ) && got.get( "updates" ).isJsonArray() ) {
            for( final JsonElement u : got.getAsJsonArray( "updates" ) ) {
                assertEquals( Set.of( "clientID", "changes" ), u.getAsJsonObject().keySet(), id + " update" );
            }
            for( final Map.Entry< String, JsonElement > e : got.getAsJsonObject( "presence" ).entrySet() ) {
                assertEquals( Set.of( "displayName", "color", "anchor", "head", "version", "unconfirmed" ),
                    e.getValue().getAsJsonObject().keySet(), id + " presence entry (explicit nulls)" );
            }
            for( final JsonElement m : got.getAsJsonArray( "metaOps" ) ) {
                final Set< String > keys = new TreeSet<>( m.getAsJsonObject().keySet() );
                keys.remove( "by" );
                assertEquals( Set.of( "field", "value" ), keys, id + " meta op" );
            }
            for( final JsonElement ev : got.getAsJsonArray( "events" ) ) {
                assertTrue( ev.getAsJsonObject().has( "seq" ) && ev.getAsJsonObject().has( "type" ), id + " event" );
            }
        }
    }

    @Test
    void everyFixtureCaseIsCoveredOrDeferredToPhase2() throws Exception {
        final Set< String > unclassified = new TreeSet<>( fixtureCases().keySet() );
        unclassified.removeAll( COVERED );
        unclassified.removeAll( DEFERRED_TO_PHASE_2 );
        assertTrue( unclassified.isEmpty(), "fixture cases neither covered nor deferred: " + unclassified );
        final Set< String > stale = new TreeSet<>( COVERED );
        stale.addAll( DEFERRED_TO_PHASE_2 );
        stale.removeAll( fixtureCases().keySet() );
        assertTrue( stale.isEmpty(), "listed cases missing from the fixture: " + stale );
    }

    // ---- join ------------------------------------------------------------------------------------------------------

    @Test
    void join200() throws Exception {
        final Resp r = call( "POST", "ada", "/" + PAGE + "/join",
            obj( "priorClientId", null, "initialText", null, "initialMeta", null ).toString(), Map.of() );
        assertFixture( "join.200", r, "eventSeq" );
        final JsonObject b = r.body();
        assertEquals( "Hello world\n", b.get( "doc" ).getAsString() );
        assertEquals( 0, b.get( "version" ).getAsInt() );
        assertEquals( 1, b.get( "basePageVersion" ).getAsInt() );
        assertEquals( "Ada", b.getAsJsonArray( "participants" ).get( 0 ).getAsJsonObject().get( "displayName" )
            .getAsString() );
        assertTrue( b.getAsJsonObject( "draft" ).get( "lastEditAt" ).isJsonNull(), "explicit null" );
        assertEquals( 1, b.get( "eventSeq" ).getAsLong(), "after its own participant-joined" );
    }

    @Test
    void joinCarriesInitialTextForANewPage() throws Exception {
        final Resp r = call( "POST", "ada", "/NewPage/join",
            obj( "initialText", "From a template", "initialMeta", obj( "status", "draft" ) ).toString(), Map.of() );
        assertEquals( 200, r.status, r.out::toString );
        assertEquals( "From a template", r.body().get( "doc" ).getAsString() );
        assertEquals( "draft", r.body().getAsJsonObject( "metadata" ).get( "status" ).getAsString() );
        assertEquals( 0, r.body().get( "basePageVersion" ).getAsInt() );
    }

    @Test
    void join403WithoutEdit() throws Exception {
        noEdit.add( "ada" );
        assertFixture( "join.403", post( "ada", "join", new JsonObject() ) );
    }

    @Test
    void join409OnADerivedPage() throws Exception {
        writer.seed( PAGE, "x", Map.of( "derived_from", "source.pdf" ) );
        assertFixture( "join.409.derived-page", post( "ada", "join", new JsonObject() ) );
    }

    @Test
    void aCallerWithoutViewLearnsNothingAboutADerivedPage() throws Exception {
        writer.seed( PAGE, "x", Map.of( "derived_from", "source.pdf" ) );
        noView.add( "ada" );
        final Resp r = post( "ada", "join", new JsonObject() );
        assertEquals( 403, r.status );
        assertEquals( "forbidden", r.body().get( "error" ).getAsString() );
        assertEquals( "No view permission on " + PAGE, r.body().get( "message" ).getAsString() );
    }

    @Test
    void join429WhenTheSessionIsFull() throws Exception {
        use( config( 1, 1000, 100, 500 ), CollabSessionRegistry.DEFAULT_MAX_PAGE_BYTES, 0 );
        join( "ada" );
        assertFixture( "join.429.session-full", post( "raj", "join", new JsonObject() ) );
    }

    @Test
    void join503WhenCollaborationIsDisabled() throws Exception {
        registry.setEnabled( false );
        assertFixture( "join.503.collab-disabled", post( "ada", "join", new JsonObject() ) );
    }

    @Test
    void join503WithoutARegistry() throws Exception {
        resource = new CollabResource( null, access, ( ms, task ) -> delayed.add( task ), 0 );
        final Resp r = post( "ada", "join", new JsonObject() );
        assertEquals( 503, r.status );
        assertEquals( "collab-disabled", r.reason() );
    }

    @Test
    void everyEndpointRequiresAuthentication() throws Exception {
        for( final String action : List.of( "join", "updates", "publish", "discard", "leave" ) ) {
            final Resp r = post( null, action, new JsonObject() );
            assertEquals( 401, r.status, action );
        }
        assertEquals( 401, call( "GET", null, "/" + PAGE + "/updates", null, Map.of() ).status );
    }

    @Test
    void anUnknownActionIs404() throws Exception {
        assertEquals( 404, post( "ada", "presence", new JsonObject() ).status );
        assertEquals( 404, call( "POST", "ada", "/", "{}", Map.of() ).status );
        assertEquals( 404, call( "GET", "ada", "/" + PAGE + "/join", null, Map.of() ).status );
    }

    @Test
    void disablingCollabRefusesJoinsButKeepsDrafts() throws Exception {
        final Joined a = join( "ada" );
        assertEquals( 200, push( "ada", a, 0, update( a, 11, arr( 0, "!" ), 1 ) ).status );
        registry.setEnabled( false );

        assertEquals( "collab-disabled", post( "raj", "join", new JsonObject() ).reason() );
        // the live session keeps working: in-flight edits complete and nothing is lost
        assertEquals( 200, push( "ada", a, 1, update( a, 12, arr( 0, "?" ), 1 ) ).status );
        final Resp p = pull( "ada", a, 0, 0, 0, 0 );
        assertEquals( 200, p.status );
        assertEquals( 2, p.body().getAsJsonArray( "updates" ).size() );
        assertTrue( store.sessionForPage( PAGE ).isPresent(), "the draft is kept in the store" );

        registry.setEnabled( true );
        assertEquals( "Hello world!?\n", join( "raj" ).body().get( "doc" ).getAsString() );
    }

    // ---- push ------------------------------------------------------------------------------------------------------

    @Test
    void push200() throws Exception {
        final Joined a = join( "ada" );
        final JsonObject body = pushBody( a, 0, arr( update( a, 11, arr( 0, "!" ), 1 ) ) );
        body.add( "selection", obj( "anchor", 12, "head", 12, "version", 0, "unconfirmed", 1 ) );
        final Resp r = post( "ada", "updates", body );
        assertFixture( "updates.post.200", r );
        assertEquals( 1, r.body().get( "version" ).getAsInt() );
        assertEquals( "Hello world!\n", session( a ).view().doc() );
    }

    @Test
    void push409StaleBase() throws Exception {
        final Joined a = join( "ada" );
        push( "ada", a, 0, update( a, 11, arr( 0, "!" ), 1 ) );
        final Resp r = push( "ada", a, 0, update( a, 11, arr( 0, "?" ), 1 ) );
        assertFixture( "updates.post.409.stale-base", r );
        assertEquals( 1, r.body().get( "version" ).getAsInt() );
    }

    @Test
    void aPresenceOnlyPushSkipsTheVersionCheck() throws Exception {
        final Joined a = join( "ada" );
        push( "ada", a, 0, update( a, 11, arr( 0, "!" ), 1 ) );
        final JsonObject body = pushBody( a, 0, new JsonArray() );
        body.add( "selection", obj( "anchor", 1, "head", 2, "version", 0, "unconfirmed", 0 ) );
        assertEquals( 200, post( "ada", "updates", body ).status );
    }

    @Test
    void push409RejoinForAnUnknownParticipant() throws Exception {
        final Joined a = join( "ada" );
        final Joined ghost = new Joined( a.sessionId, "pt-unknown", a.clientId, a.body );
        assertFixture( "updates.post.409.rejoin", push( "ada", ghost, 0 ) );
    }

    @Test
    void push409RejoinForAnUnknownSession() throws Exception {
        final Joined a = join( "ada" );
        final Joined lost = new Joined( UUID.randomUUID().toString(), a.participantId, a.clientId, a.body );
        assertEquals( "rejoin", push( "ada", lost, 0 ).reason() );
        final Joined garbled = new Joined( "not-a-uuid", a.participantId, a.clientId, a.body );
        assertEquals( "rejoin", push( "ada", garbled, 0 ).reason() );
    }

    @Test
    void aMissingParticipantIdIs400() throws Exception {
        final Joined a = join( "ada" );
        final Resp r = post( "ada", "updates", obj( "sessionId", a.sessionId, "version", 0, "updates", arr() ) );
        assertEquals( 400, r.status );
        assertEquals( "rejected", r.reason() );
    }

    @Test
    void push403ForAnotherUsersParticipant() throws Exception {
        final Joined a = join( "ada" );
        join( "raj" );
        assertFixture( "updates.post.403.not-your-participant", push( "raj", a, 0 ) );
    }

    @Test
    void push409FrozenOnADeletedPage() throws Exception {
        final Joined a = join( "ada" );
        registry.freeze( PAGE );
        assertFixture( "updates.post.409.frozen", push( "ada", a, 0 ) );
    }

    @Test
    void push429WhenRateLimited() throws Exception {
        use( config( 20, 1, 100, 500 ), CollabSessionRegistry.DEFAULT_MAX_PAGE_BYTES, 0 );
        final Joined a = join( "ada" );
        assertEquals( 200, push( "ada", a, 0 ).status );
        final Resp r = push( "ada", a, 0 );
        assertFixture( "updates.post.429.rate-limited", r );
        assertEquals( 1000, r.body().get( "retryAfterMs" ).getAsInt() );
    }

    @Test
    void push400ForALengthMismatch() throws Exception {
        final Joined a = join( "ada" );
        assertFixture( "updates.post.400.rejected", push( "ada", a, 0, update( a, 3, arr( 0, "x" ) ) ) );
    }

    @Test
    void hostileChangeSetsAre400AndApplyNothing() throws Exception {
        final Joined a = join( "ada" );
        final List< JsonElement > hostile = List.of(
            arr( -1 ), arr( 11.5 ), arr( "11" ), arr( arr( 0, "a\nb" ) ), arr( 11, arr() ), arr( 11, arr( 0, 7 ) ),
            arr( obj( "x", 1 ) ), arr( 1e12 ), arr( 5, 6 ), arr( 0 ), obj( "changes", 1 ), JsonParser.parseString( "\"x\"" ),
            JsonNull.INSTANCE );
        for( final JsonElement changes : hostile ) {
            final JsonObject u = obj( "clientID", a.clientId, "changes", changes );
            final Resp r = push( "ada", a, 0, u );
            assertEquals( 400, r.status, () -> "changes " + changes + ": " + r.out );
            assertEquals( "rejected", r.reason() );
        }
        for( final JsonElement badUpdate : List.<JsonElement>of( arr(), JsonParser.parseString( "3" ),
                obj( "changes", arr( 11 ) ), obj( "clientID", 5, "changes", arr( 11 ) ) ) ) {
            final Resp r = post( "ada", "updates", pushBody( a, 0, arr( badUpdate ) ) );
            assertEquals( 400, r.status, () -> "update " + badUpdate + ": " + r.out );
        }
        for( final String raw : List.of( "{", "[]", "\"s\"", "{\"sessionId\":" ) ) {
            final Resp r = call( "POST", "ada", "/" + PAGE + "/updates", raw, Map.of() );
            assertEquals( 400, r.status, raw );
            assertEquals( "rejected", r.reason() );
        }
        final JsonObject badVersion = pushBody( a, 0, arr() );
        badVersion.addProperty( "version", "zero" );
        assertEquals( 400, post( "ada", "updates", badVersion ).status );
        final JsonObject badSelection = pushBody( a, 0, arr() );
        badSelection.add( "selection", obj( "anchor", -1, "head", 0, "version", 0, "unconfirmed", 0 ) );
        assertEquals( 400, post( "ada", "updates", badSelection ).status );
        assertEquals( "Hello world\n", session( a ).view().doc() );
        assertEquals( 0, session( a ).view().head() );
    }

    @Test
    void push413WhenTheDocumentWouldExceedMaxPageBytes() throws Exception {
        use( config( 20, 1000, 100, 500 ), 16, 0 );
        final Joined a = join( "ada" );
        assertFixture( "updates.post.413", push( "ada", a, 0, update( a, 11, arr( 0, "a very large insertion" ), 1 ) ) );
    }

    @Test
    void aBodyOverMaxUpdateBytesIs413BeforeParsing() throws Exception {
        final Joined a = join( "ada" );
        final String big = "x".repeat( 5000 );
        final Resp r = push( "ada", a, 0, update( a, 11, arr( 0, big ), 1 ) );
        assertEquals( 413, r.status );
        assertEquals( "oversize", r.reason() );
        assertEquals( 0, session( a ).view().head() );
        // a body that is not JSON at all but too long is refused for its size, never parsed
        final Resp junk = call( "POST", "ada", "/" + PAGE + "/updates", "{" + "[".repeat( 5000 ), Map.of() );
        assertEquals( 413, junk.status );
    }

    private JsonObject metaPush( final Joined j, final int version, final JsonArray updates, final JsonObject... ops ) {
        final JsonObject body = pushBody( j, version, updates );
        body.add( "metaOps", arr( ( Object[] ) ops ) );
        return body;
    }

    @Test
    void pushWithTextAndAMetaSet() throws Exception {
        final Joined a = join( "ada" );
        final JsonObject body = metaPush( a, 0, arr( update( a, 11, arr( 0, "!" ), 1 ) ),
            obj( "field", "tags", "value", arr( "a", "b" ) ) );
        body.add( "selection", obj( "anchor", 12, "head", 12, "version", 0, "unconfirmed", 1 ) );
        final Resp r = post( "ada", "updates", body );
        assertFixture( "updates.post.200", r );
        assertEquals( 1, r.body().get( "metaSeq" ).getAsLong() );
        assertEquals( List.of( "a", "b" ), session( a ).view().meta().get( "tags" ) );
    }

    @Test
    void metaSetOpsResolveSetSemantics() throws Exception {
        final Joined a = join( "ada" );
        final Resp r = post( "ada", "updates", metaPush( a, 0, arr(), obj( "field", "tags", "add", "c" ),
            obj( "field", "related", "remove", "OldPage" ), obj( "field", "status", "value", "draft" ),
            obj( "field", "summary", "value", null ) ) );
        assertFixture( "updates.post.200.meta-set-ops", r );
        assertEquals( List.of( "a", "c" ), session( a ).view().meta().get( "tags" ) );
        assertEquals( "draft", session( a ).view().meta().get( "status" ) );
        assertEquals( session( a ).metaSeq(), r.body().get( "metaSeq" ).getAsLong() );
    }

    @Test
    void aMetaOnlyPushSkipsTheVersionCheck() throws Exception {
        final Joined a = join( "ada" );
        push( "ada", a, 0, update( a, 11, arr( 0, "!" ), 1 ) );
        final Resp r = post( "ada", "updates", metaPush( a, 0, arr(), obj( "field", "status", "value", "draft" ) ) );
        assertFixture( "updates.post.200.meta-only", r );
        assertEquals( 1, r.body().get( "version" ).getAsInt() );
    }

    @Test
    void aMetaEditOnANonSetFieldIs400() throws Exception {
        final Joined a = join( "ada" );
        assertFixture( "updates.post.400.bad-meta-op",
            post( "ada", "updates", metaPush( a, 1, arr(), obj( "field", "status", "add", "x" ) ) ) );
    }

    @Test
    void malformedWireMetaOpsAre400AndApplyNothing() throws Exception {
        final Joined a = join( "ada" );
        final JsonArray text = arr( update( a, 11, arr( 0, "!" ), 1 ) );
        final List< JsonElement > bad = List.of(
            obj( "field", "tags", "add", "x", "remove", "y" ), obj( "field", "tags", "value", arr(), "add", "x" ),
            obj( "field", "tags" ), obj( "field", "tags", "add", 5 ), obj( "field", "tags", "remove", arr( "x" ) ),
            obj( "field", "tags", "add", "  " ), obj( "field", 5, "value", "x" ), obj( "value", "x" ),
            JsonNull.INSTANCE, arr(), JsonParser.parseString( "\"tags\"" ) );
        for( final JsonElement op : bad ) {
            final JsonObject body = pushBody( a, 0, text );
            body.add( "metaOps", arr( obj( "field", "status", "value", "ok" ), op ) );
            final Resp r = post( "ada", "updates", body );
            assertEquals( 400, r.status, () -> "meta op " + op + ": " + r.out );
            assertEquals( "rejected", r.reason() );
        }
        final JsonObject notAList = pushBody( a, 0, text );
        notAList.add( "metaOps", obj( "field", "status", "value", "ok" ) );
        assertEquals( 400, post( "ada", "updates", notAList ).status );
        assertEquals( "Hello world\n", session( a ).view().doc(), "a refused push applies nothing" );
        assertFalse( session( a ).view().meta().containsKey( "status" ) );
        final JsonObject empty = pushBody( a, 0, arr() );
        empty.add( "metaOps", arr() );
        assertEquals( 200, post( "ada", "updates", empty ).status, "an empty metaOps list is fine" );
    }

    @Test
    void identityAndStaleBaseComeBeforeMetaValidation() throws Exception {
        final Joined a = join( "ada" );
        join( "raj" );
        final JsonObject invalid = obj( "field", "status", "add", "x" );
        assertEquals( "not-your-participant", post( "raj", "updates", metaPush( a, 0, arr(), invalid ) ).reason() );
        push( "ada", a, 0, update( a, 11, arr( 0, "!" ), 1 ) );
        assertEquals( "stale-base", post( "ada", "updates",
            metaPush( a, 0, arr( update( a, 11, arr( 0, "?" ), 1 ) ), invalid ) ).reason() );
        assertEquals( "rejected", post( "ada", "updates", metaPush( a, 0, arr(), invalid ) ).reason(),
            "without text there is no version check, so the meta check answers" );
    }

    @Test
    void pulledMetaOpsNameTheAuthorByDisplayName() throws Exception {
        final Joined a = join( "ada" );
        final Joined r = join( "raj" );
        assertEquals( 200, post( "raj", "updates", metaPush( r, 0, arr(),
            obj( "field", "status", "value", "draft" ) ) ).status );
        final Resp p = pull( "ada", a, 0, 0, 0, 0 );
        assertFixture( "updates.get.200.meta-by", p );
        final JsonObject op = p.body().getAsJsonArray( "metaOps" ).get( 0 ).getAsJsonObject();
        assertEquals( "status", op.get( "field" ).getAsString() );
        assertEquals( "draft", op.get( "value" ).getAsString() );
        assertEquals( "Raj", op.get( "by" ).getAsString() );
    }

    @Test
    void aRepeatedStoreFailureDropsTheSessionForAReload() throws Exception {
        final Joined a = join( "ada" );
        for( int i = 0; i < CollabSessionRegistry.STORE_FAILURE_LIMIT; i++ ) {
            store.failOn( InMemoryCollabStore.Op.APPEND_UPDATES, 1 );
            final Resp r = push( "ada", a, 0, update( a, 11, arr( 0, "!" ), 1 ) );
            assertEquals( 503, r.status );
            assertEquals( "store", r.reason() );
        }
        assertTrue( registry.live( UUID.fromString( a.sessionId ) ).isEmpty(), "the registry saw every 503 store" );
    }

    // ---- pull ------------------------------------------------------------------------------------------------------

    @Test
    void pull200AnswersAtOnceWithUpdatesAndPresence() throws Exception {
        final Joined a = join( "ada" );
        final Joined r = join( "raj" );
        final JsonObject body = pushBody( r, 0, arr( update( r, 11, arr( 0, "!" ), 1 ) ) );
        body.add( "selection", obj( "anchor", 3, "head", 5, "version", 0, "unconfirmed", 1 ) );
        assertEquals( 200, post( "raj", "updates", body ).status );

        final Resp p = pull( "ada", a, 0, 0, 0, 0 );

        assertFixture( "updates.get.200", p );
        assertNull( p.async, "an immediate answer does not go async" );
        final JsonObject b = p.body();
        assertEquals( arr( update( r, 11, arr( 0, "!" ), 1 ) ), b.getAsJsonArray( "updates" ) );
        final JsonObject raj = b.getAsJsonObject( "presence" ).getAsJsonObject( r.participantId );
        assertEquals( "Raj", raj.get( "displayName" ).getAsString() );
        assertFalse( b.getAsJsonObject( "presence" ).has( a.participantId ), "never the requester" );
    }

    @Test
    void presenceEntriesWithoutASelectionCarryExplicitNulls() throws Exception {
        final Joined a = join( "ada" );
        final Joined r = join( "raj" );
        final JsonObject raj = pull( "ada", a, 0, 0, 0, 0 ).body().getAsJsonObject( "presence" )
            .getAsJsonObject( r.participantId );
        assertTrue( raj.get( "anchor" ).isJsonNull() && raj.get( "head" ).isJsonNull()
            && raj.get( "version" ).isJsonNull() && raj.get( "unconfirmed" ).isJsonNull(), raj::toString );
    }

    /** Parks ada at the head with every cursor current; returns the pull's response. */
    private Resp park( final Joined a ) throws Exception {
        return park( a, "ada" );
    }

    private Resp park( final Joined a, final String user ) throws Exception {
        final CollabSession s = session( a );
        final Resp p = pull( user, a, s.view().head(), s.metaSeq(), s.view().presenceSeq(), Long.MAX_VALUE );
        assertNotNull( p.async, "parked: async started" );
        assertFalse( p.async.isCompleted(), "parked: not answered" );
        assertEquals( 25_000, p.async.getTimeout() );
        return p;
    }

    @Test
    void pull200EmptyOnTheContainerTimeout() throws Exception {
        final Joined a = join( "ada" );
        final CollabSession s = session( a );
        final long eventSeq = a.body().get( "eventSeq" ).getAsLong();
        final Resp p = pull( "ada", a, 0, s.metaSeq(), s.view().presenceSeq(), eventSeq );
        assertNotNull( p.async );
        p.async.fireTimeout();
        p.async.assertCompletedOnce();
        assertFixture( "updates.get.200.empty", p );
        assertEquals( s.view().presenceSeq(), p.body().get( "presenceSeq" ).getAsLong(), "cursors echoed" );
    }

    @Test
    void pull200EmptyOnTheSessionTimeout() throws Exception {
        final Joined a = join( "ada" );
        final Resp p = park( a );
        clock.advance( Duration.ofSeconds( 26 ) );
        registry.tick();
        p.async.assertCompletedOnce();
        assertEquals( 200, p.status );
        assertEquals( 0, p.body().getAsJsonArray( "updates" ).size() );
    }

    @Test
    void aWaiterCompletesExactlyOnceOnWakeAndOnTimeout() throws Exception {
        // wake first, then a late container timeout and the session's own timeout
        final Joined a = join( "ada" );
        final Joined r = join( "raj" );
        final Resp woken = park( a );
        assertEquals( 200, push( "raj", r, 0, update( r, 11, arr( 0, "!" ), 1 ) ).status );
        woken.async.assertCompletedOnce();
        assertEquals( 1, woken.body().getAsJsonArray( "updates" ).size() );
        woken.async.listeners().get( 0 ).onTimeout( new AsyncEvent( woken.async ) );
        clock.advance( Duration.ofSeconds( 26 ) );
        registry.tick();
        woken.async.assertCompletedOnce();

        // container timeout first, then a wake of the still-parked waiter
        final Resp timedOut = park( a );
        timedOut.async.fireTimeout();
        assertEquals( 200, push( "raj", r, 1, update( r, 12, arr( 0, "?" ), 1 ) ).status );
        timedOut.async.assertCompletedOnce();
        assertEquals( 0, timedOut.body().getAsJsonArray( "updates" ).size() );
    }

    @Test
    void wakesAreCoalescedAndATimeoutDuringTheDelayWritesTheDelayedAnswer() throws Exception {
        use( config( 20, 1000, 100, 500 ), CollabSessionRegistry.DEFAULT_MAX_PAGE_BYTES, 50 );
        final Joined a = join( "ada" );
        final Joined r = join( "raj" );
        final Resp p = park( a );
        push( "raj", r, 0, update( r, 11, arr( 0, "!" ), 1 ) );
        assertFalse( p.async.isCompleted(), "the wake is held for the coalescing window" );
        assertEquals( 1, delayed.size() );
        delayed.get( 0 ).run();
        p.async.assertCompletedOnce();
        assertEquals( 1, p.body().getAsJsonArray( "updates" ).size() );

        delayed.clear();
        final Resp q = park( a );
        push( "raj", r, 1, update( r, 12, arr( 0, "?" ), 1 ) );
        q.async.fireTimeout();
        q.async.assertCompletedOnce();
        assertEquals( 1, q.body().getAsJsonArray( "updates" ).size(), "the held answer, not an empty one" );
        delayed.get( 0 ).run();
        q.async.assertCompletedOnce();
    }

    @Test
    void anAnswerBeforeAsyncStartsIsWrittenByStart() throws Exception {
        final Joined a = join( "ada" );
        final CollabSession s = session( a );
        final Resp resp = new Resp();
        final CollabPull w = new CollabPull( s, a.participantId, new PullCursor( 0, 0, 0, 0 ), resp.mock,
            new CollabPull.Handoff( ( ms, task ) -> fail( "not delayed" ), ( c, task ) -> fail( "not dispatched" ),
                50 ), x -> { } );
        w.complete( new PullResponse( 200, List.of(), 0, null, Map.of(), 0 ) );
        assertEquals( "", resp.out.toString(), "nothing written before async mode" );
        final FakeAsyncContext ac = new FakeAsyncContext();
        w.start( ac, 1000 );
        ac.assertCompletedOnce();
        assertEquals( 200, resp.status );
        w.complete( new PullResponse( 200, List.of(), 0, null, Map.of(), 0 ) );
        ac.assertCompletedOnce();
    }

    @Test
    void theParkedPullOfAParticipantWhoLostEditIs403Evicted() throws Exception {
        final Joined a = join( "ada" );
        final Joined r = join( "raj" );
        push( "raj", r, 0, update( r, 11, arr( 0, "!" ), 1 ) );
        pull( "ada", a, 0, 0, 0, Long.MAX_VALUE );
        final Resp p = park( a );
        noEdit.add( "ada" );

        assertEquals( 200, post( "raj", "publish", obj( "sessionId", r.sessionId, "participantId", r.participantId,
            "version", 1, "changeNote", "" ) ).status );

        p.async.assertCompletedOnce();
        assertEquals( 403, p.status );
        assertEquals( "evicted", p.reason() );
        assertEquals( "lost-edit", p.body().get( "detail" ).getAsString() );
    }

    @Test
    void theParkedPullOfADeletedAccountIs403EvictedAccountDeleted() throws Exception {
        final Joined a = join( "ada" );
        final Resp p = park( a );
        registry.evictAuthor( "ada" );   // the session's tombstone answers, whatever ada may still edit
        p.async.assertCompletedOnce();
        assertEquals( 403, p.status );
        assertEquals( "evicted", p.reason() );
        assertEquals( "account-deleted", p.body().get( "detail" ).getAsString() );
    }

    @Test
    void everyCallOfAnEvictedParticipantIs403EvictedWithTheReason() throws Exception {
        final Joined a = join( "ada" );
        session( a ).evict( a.participantId, "lost-edit" );
        assertFixture( "updates.post.403.evicted", push( "ada", a, 1 ) );
        assertFixture( "updates.get.403.evicted", pull( "ada", a, 0, 0, 0, 0 ) );
        final Resp pub = post( "ada", "publish", publishBody( a, 0 ) );
        assertEquals( 403, pub.status );
        assertEquals( "lost-edit", pub.body().get( "detail" ).getAsString() );
        final Resp discard = post( "ada", "discard", ids( a ) );
        assertEquals( 403, discard.status );
        assertEquals( "evicted", discard.reason() );
        final Resp leave = post( "ada", "leave", ids( a ) );
        assertEquals( 403, leave.status );
        assertEquals( "lost-edit", leave.body().get( "detail" ).getAsString() );
    }

    @Test
    void aFrozenDraftIsPublishedOnlyWithCreatePages() throws Exception {
        final Joined a = join( "ada" );
        writer.delete( PAGE );
        registry.freeze( PAGE );
        noCreate.add( "ada" );
        assertFixture( "publish.403.no-create", post( "ada", "publish", publishBody( a, 0 ) ) );
        assertTrue( writer.latest( PAGE ).isEmpty() );
        noCreate.clear();
        assertEquals( 200, post( "ada", "publish", publishBody( a, 0 ) ).status, "recreated" );
        assertTrue( writer.latest( PAGE ).isPresent() );
    }

    @Test
    void pull410WhenTheLogBelowTheCursorIsGone() throws Exception {
        use( config( 20, 1000, 1, 1 ), CollabSessionRegistry.DEFAULT_MAX_PAGE_BYTES, 0 );
        final Joined a = join( "ada" );
        push( "ada", a, 0, update( a, 11, arr( 0, "1" ), 1 ) );
        push( "ada", a, 1, update( a, 12, arr( 0, "2" ), 1 ) );
        push( "ada", a, 2, update( a, 13, arr( 0, "3" ), 1 ) );
        assertEquals( 200, pull( "ada", a, 3, 0, 0, 0 ).status );
        session( a ).truncateLog();
        assertFixture( "updates.get.410", pull( "ada", a, 0, 0, 0, 0 ) );
    }

    @Test
    void pull400ForABadSince() throws Exception {
        final Joined a = join( "ada" );
        assertFixture( "updates.get.400.bad-since", pull( "ada", a, 99, 0, 0, 0 ) );
        final Map< String, String > q = new HashMap<>( Map.of( "sessionId", a.sessionId, "participantId",
            a.participantId, "since", "zero" ) );
        assertEquals( "bad-since", call( "GET", "ada", "/" + PAGE + "/updates", null, q ).reason() );
        q.remove( "since" );
        assertEquals( "bad-since", call( "GET", "ada", "/" + PAGE + "/updates", null, q ).reason() );
    }

    @Test
    void pull409RejoinForAnUnknownParticipant() throws Exception {
        final Joined a = join( "ada" );
        assertFixture( "updates.get.409.rejoin",
            pull( "ada", new Joined( a.sessionId, "pt-unknown", a.clientId, a.body ), 0, 0, 0, 0 ) );
    }

    @Test
    void aPageNameThatIsNotTheSessionsIs404() throws Exception {
        final Joined a = join( "ada" );
        final Resp r = call( "POST", "ada", "/OtherPage/updates", pushBody( a, 0, arr() ).toString(), Map.of() );
        assertEquals( 404, r.status );
    }

    @Test
    void requestsUnderTheOldNameReachARenamedSession() throws Exception {
        final Joined a = join( "ada" );
        registry.rekey( PAGE, "NewName" );
        assertEquals( 200, call( "POST", "ada", "/" + PAGE + "/updates", pushBody( a, 0, arr() ).toString(),
            Map.of() ).status, "the old name still reaches it" );
        assertEquals( 200, call( "POST", "ada", "/NewName/updates", pushBody( a, 0, arr() ).toString(),
            Map.of() ).status, "and so does the new one" );
        assertEquals( 404, call( "POST", "ada", "/OtherPage/updates", pushBody( a, 0, arr() ).toString(),
            Map.of() ).status );
    }

    // ---- publish ---------------------------------------------------------------------------------------------------

    private JsonObject publishBody( final Joined j, final int version ) {
        return obj( "sessionId", j.sessionId, "participantId", j.participantId, "version", version, "changeNote",
            "Fix greeting" );
    }

    @Test
    void publish200() throws Exception {
        final Joined a = join( "ada" );
        push( "ada", a, 0, update( a, 11, arr( 0, "!" ), 1 ) );
        final Resp r = post( "ada", "publish", publishBody( a, 1 ) );
        assertFixture( "publish.200", r );
        assertEquals( 2, r.body().get( "pageVersion" ).getAsInt() );
        assertEquals( "Hello world!", writer.latest( PAGE ).orElseThrow().body().strip() );
    }

    @Test
    void publish409MergedRetryAfterAnUnseenOutsideSave() throws Exception {
        final Joined a = join( "ada" );
        push( "ada", a, 0, update( a, 11, arr( 0, "!" ), 1 ) );
        writer.externalSave( PAGE, "Hello there world", Map.of( "tags", List.of( "a" ) ), "agent" );
        assertFixture( "publish.409.publish-merged-retry", post( "ada", "publish", publishBody( a, 1 ) ) );
    }

    @Test
    void publish409NotSynced() throws Exception {
        final Joined a = join( "ada" );
        assertFixture( "publish.409.not-synced", post( "ada", "publish", publishBody( a, 9 ) ) );
    }

    @Test
    void publish422CarriesPageResourcesBody() throws Exception {
        final Joined a = join( "ada" );
        push( "ada", a, 0, update( a, 11, arr( 0, "!" ), 1 ) );
        writer.failNextPublish( new FrontmatterValidationException( List.of(
            FieldViolation.of( "tags", Severity.ERROR, "type", "tags must be a list" ) ) ) );
        final Resp r = post( "ada", "publish", publishBody( a, 1 ) );
        assertFixture( "publish.422", r );
        assertEquals( "tags must be a list", r.body().getAsJsonArray( "violations" ).get( 0 ).getAsJsonObject()
            .get( "message" ).getAsString() );
    }

    @Test
    void publish423WhileAnotherPublishIsSaving() throws Exception {
        final Joined a = join( "ada" );
        final Joined r = join( "raj" );
        push( "ada", a, 0, update( a, 11, arr( 0, "!" ), 1 ) );
        final ScriptedPageWriter.PauseHandle pause = writer.pauseNextPublish();
        final CompletableFuture< Resp > first = CompletableFuture.supplyAsync( () -> {
            try {
                return post( "ada", "publish", publishBody( a, 1 ) );
            } catch( final Exception e ) {
                throw new IllegalStateException( e );
            }
        } );
        pause.awaitEntered( Duration.ofSeconds( 10 ) );
        assertFixture( "publish.423", post( "raj", "publish", publishBody( r, 1 ) ) );
        pause.release();
        assertEquals( 200, first.get( 10, TimeUnit.SECONDS ).status );
    }

    @Test
    void publishRechecksEditUncached() throws Exception {
        final Joined a = join( "ada" );
        push( "ada", a, 0, update( a, 11, arr( 0, "!" ), 1 ) );
        noEdit.add( "ada" );
        final Resp r = post( "ada", "publish", publishBody( a, 1 ) );
        assertEquals( 403, r.status );
        assertEquals( "forbidden", r.body().get( "error" ).getAsString() );
        assertEquals( "Hello world", writer.latest( PAGE ).orElseThrow().body().strip(), "nothing saved" );
    }

    @Test
    void publishingANewPageNeedsCreatePages() throws Exception {
        final Joined a = join( "ada", "NewPage" );
        final Resp typed = call( "POST", "ada", "/NewPage/updates",
            pushBody( a, 0, arr( update( a, arr( 0, "x" ) ) ) ).toString(), Map.of() );
        assertEquals( 200, typed.status, typed.out::toString );
        noCreate.add( "ada" );
        final Resp r = call( "POST", "ada", "/NewPage/publish", publishBody( a, 1 ).toString(), Map.of() );
        assertEquals( 403, r.status );
        assertTrue( writer.latest( "NewPage" ).isEmpty() );
    }

    // ---- discard and leave -----------------------------------------------------------------------------------------

    @Test
    void discard204ResetsTheDraft() throws Exception {
        final Joined a = join( "ada" );
        push( "ada", a, 0, update( a, 11, arr( 0, "!" ), 1 ) );
        assertFixture( "discard.204", post( "ada", "discard", ids( a ) ) );
        assertEquals( "Hello world\n", session( a ).view().doc() );
    }

    @Test
    void leave204AndAgain() throws Exception {
        final Joined a = join( "ada" );
        assertFixture( "leave.204", post( "ada", "leave", ids( a ) ) );
        assertFalse( session( a ).hasParticipant( a.participantId ) );
        assertEquals( 204, post( "ada", "leave", ids( a ) ).status, "a repeated leave (or a beacon) is harmless" );
    }

    @Test
    void anotherUsersParticipantIdIs403() throws Exception {
        final Joined a = join( "ada" );
        join( "raj" );
        assertEquals( "not-your-participant", push( "raj", a, 0 ).reason() );
        assertEquals( "not-your-participant", pull( "raj", a, 0, 0, 0, 0 ).reason() );
        assertEquals( "not-your-participant", post( "raj", "publish", publishBody( a, 0 ) ).reason() );
        assertEquals( "not-your-participant", post( "raj", "discard", ids( a ) ).reason() );
        final Resp leave = post( "raj", "leave", ids( a ) );
        assertEquals( 403, leave.status );
        assertEquals( "not-your-participant", leave.reason() );
        assertTrue( session( a ).hasParticipant( a.participantId ), "ada is still here" );
    }

    // ---- fix round 1 -----------------------------------------------------------------------------------------------

    @Test
    void invalidPageNamesAre400BeforeAnythingElse() throws Exception {
        final Joined r = join( "raj" );
        noView.add( "ada" );   // a view check would answer 403: the name check must come first
        for( final String name : List.of( "Foo/bar.png", "Foo:bar", "Foo\u0001bar", "x".repeat( 201 ) ) ) {
            for( final String action : List.of( "join", "updates", "publish", "discard", "leave" ) ) {
                final Resp resp = call( "POST", "ada", "/" + name + "/" + action, ids( r ).toString(), Map.of() );
                assertEquals( 400, resp.status, () -> name + " " + action + ": " + resp.out );
                assertEquals( "rejected", resp.reason() );
            }
            final Resp get = call( "GET", "ada", "/" + name + "/updates", null, Map.of( "sessionId", r.sessionId,
                "participantId", r.participantId, "since", "0" ) );
            assertEquals( 400, get.status, name );
            assertTrue( store.sessionForPage( name ).isEmpty(), "no session for " + name );
        }
    }

    @Test
    void aBlockedWriterDoesNotDelayAnotherWaitersAnswer() throws Exception {
        final ExecutorService pool = Executors.newCachedThreadPool();
        try {
            use( config( 20, 1000, 100, 500 ), CollabSessionRegistry.DEFAULT_MAX_PAGE_BYTES, 0, writer,
                ( ac, task ) -> pool.execute( task ) );
            final Joined a = join( "ada" );
            final Joined r = join( "raj" );
            final Joined b = join( "bob" );
            final CountDownLatch release = new CountDownLatch( 1 );
            final CountDownLatch entered = new CountDownLatch( 1 );
            nextWriter = new Writer() {
                @Override
                public void write( final char[] buf, final int off, final int len ) throws IOException {
                    entered.countDown();
                    try {
                        release.await();
                    } catch( final InterruptedException e ) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException( "interrupted" );
                    }
                }

                @Override
                public void flush() {
                    // nothing buffered
                }

                @Override
                public void close() {
                    // nothing held
                }
            };
            final Resp stuck = park( a );   // parked first, so it is woken first
            final Resp other = park( r, "raj" );
            final CompletableFuture< Resp > pushed = CompletableFuture.supplyAsync( () -> {
                try {
                    return push( "bob", b, 0, update( b, 11, arr( 0, "!" ), 1 ) );
                } catch( final Exception e ) {
                    throw new IllegalStateException( e );
                }
            } );
            assertEquals( 200, pushed.get( 5, TimeUnit.SECONDS ).status, "the pusher is never held by a write" );
            awaitCompleted( other );
            assertEquals( 1, other.body().getAsJsonArray( "updates" ).size() );
            assertTrue( entered.await( 5, TimeUnit.SECONDS ) );
            assertFalse( stuck.async.isCompleted(), "the stuck write is still in flight" );

            // a container timeout while that write is in flight waits for it instead of returning (no error dispatch)
            final Thread timeout = new Thread( stuck.async::fireTimeout );
            timeout.start();
            timeout.join( 300 );
            assertTrue( timeout.isAlive(), "onTimeout waits for the in-flight write" );
            release.countDown();
            timeout.join( 5_000 );
            assertFalse( timeout.isAlive() );
            stuck.async.assertCompletedOnce();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitCompleted( final Resp r ) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( 5 );
        while( !r.async.isCompleted() && System.nanoTime() < deadline ) {
            Thread.sleep( 5 );
        }
        r.async.assertCompletedOnce();
    }

    @Test
    void discardChecksEditUncached() throws Exception {
        final Joined a = join( "ada" );
        push( "ada", a, 0, update( a, 11, arr( 0, "!" ), 1 ) );
        noEdit.add( "ada" );
        final Resp r = post( "ada", "discard", ids( a ) );
        assertEquals( 403, r.status );
        assertEquals( "forbidden", r.body().get( "error" ).getAsString() );
        assertEquals( "Hello world!\n", session( a ).view().doc(), "nothing discarded" );
    }

    @Test
    void publishPassesTheMarkupSyntaxToTheSave() throws Exception {
        final List< PublishRequest > seen = new ArrayList<>();
        final CollabPageWriter capturing = new CollabPageWriter() {
            @Override
            public Optional< PageState > latest( final String pageName ) {
                return writer.latest( pageName );
            }

            @Override
            public Optional< PageState > version( final String pageName, final int version ) {
                return writer.version( pageName, version );
            }

            @Override
            public PublishOutcome publish( final PublishRequest request ) throws WikiException {
                seen.add( request );
                return writer.publish( request );
            }
        };
        use( config( 20, 1000, 100, 500 ), CollabSessionRegistry.DEFAULT_MAX_PAGE_BYTES, 0, capturing,
            ( ac, task ) -> task.run() );
        final Joined a = join( "ada" );
        push( "ada", a, 0, update( a, 11, arr( 0, "!" ), 1 ) );
        final JsonObject body = publishBody( a, 1 );
        body.addProperty( "markupSyntax", "markdown" );
        assertEquals( 200, post( "ada", "publish", body ).status );
        assertEquals( "markdown", seen.get( 0 ).markupSyntax() );

        final int head = session( a ).view().head();
        push( "ada", a, head, update( a, session( a ).view().doc().length() - 1, arr( 0, "?" ), 1 ) );
        assertEquals( 200, post( "ada", "publish", publishBody( a, session( a ).view().head() ) ).status );
        assertNull( seen.get( 1 ).markupSyntax(), "absent stays absent" );
    }

    @Test
    void publish422AccessRuleShapeIsPageResourcesBodyWithoutNulls() throws Exception {
        final Joined a = join( "ada" );
        push( "ada", a, 0, update( a, 11, arr( 0, "!" ), 1 ) );
        final AccessRuleShapeException e = new AccessRuleShapeException( List.of( new AccessRuleShapeException.Violation(
            AccessRuleShapeException.CODE, "unclosed", 3, null, "[{ALLOW edit", "close the rule" ) ) );
        writer.failNextPublish( e );
        final Resp r = post( "ada", "publish", publishBody( a, 1 ) );
        assertEquals( 422, r.status );
        assertEquals( JsonParser.parseString( RestServletBase.GSON.toJson( PageResource.accessRuleShapeErrorBody( e ) ) ),
            r.body(), "exactly PageResource's body" );
        assertFalse( r.body().getAsJsonArray( "violations" ).get( 0 ).getAsJsonObject().has( "field" ),
            "no null fields" );
    }

    @Test
    void aMetaCarryingDiscardWithAStoreFailureChangesNothing() throws Exception {
        final Joined a = join( "ada" );
        final JsonObject body = pushBody( a, 0, arr( update( a, 11, arr( 0, "!" ), 1 ) ) );
        body.add( "metaOps", arr( obj( "field", "status", "value", "draft" ) ) );
        assertEquals( 200, post( "ada", "updates", body ).status );
        final long metaSeq = session( a ).metaSeq();
        store.failOn( InMemoryCollabStore.Op.APPEND_BATCH, 1 );
        store.failOn( InMemoryCollabStore.Op.APPEND_META, 1 );
        store.failOn( InMemoryCollabStore.Op.APPEND_UPDATES, 1 );
        store.failOn( InMemoryCollabStore.Op.COMMIT_BASE_CHANGE, 1 );

        final Resp r = post( "ada", "discard", ids( a ) );

        assertEquals( 503, r.status );
        assertEquals( "Hello world!\n", session( a ).view().doc() );
        assertEquals( "draft", session( a ).view().meta().get( "status" ), "the meta is not half-reset" );
        assertEquals( metaSeq, session( a ).metaSeq() );
        for( final InMemoryCollabStore.Op op : InMemoryCollabStore.Op.values() ) {
            store.failOn( op, 1_000_000 );   // disarm whatever the failed discard did not reach
        }
        assertEquals( 204, post( "ada", "discard", ids( a ) ).status, "a retry discards" );
        assertEquals( "Hello world\n", session( a ).view().doc() );
        assertFalse( session( a ).view().meta().containsKey( "status" ) );
    }

    @Test
    void theJoinBodyCapFollowsMaxPageBytes() throws Exception {
        use( config( 20, 1000, 100, 500 ), 16, 0 );
        final String big = "x".repeat( 200_000 );
        final Resp r = call( "POST", "ada", "/NewPage/join", obj( "initialText", big ).toString(), Map.of() );
        assertEquals( 413, r.status );
        assertEquals( "oversize", r.reason() );
        assertTrue( store.sessionForPage( "NewPage" ).isEmpty() );
    }

    @Test
    void oneUsersTwoJoinsAreTwoParticipants() throws Exception {
        final Joined first = join( "ada" );
        final Joined second = join( "ada" );
        assertFalse( first.participantId.equals( second.participantId ), "two tabs, two participants" );
        assertFalse( first.clientId.equals( second.clientId ) );
        assertEquals( first.sessionId, second.sessionId );
        assertTrue( session( first ).hasParticipant( first.participantId ) );
        assertTrue( session( first ).hasParticipant( second.participantId ) );
    }

    // ---- headers ---------------------------------------------------------------------------------------------------

    @Test
    void responsesAreNoStore() throws Exception {
        final List< Resp > all = new ArrayList<>();
        final Joined a = join( "ada" );
        all.add( post( "ada", "join", new JsonObject() ) );
        all.add( post( null, "join", new JsonObject() ) );
        all.add( post( "ada", "nope", new JsonObject() ) );
        all.add( push( "ada", a, 0, update( a, 3, arr( 0, "x" ) ) ) );
        all.add( push( "ada", a, 0 ) );
        all.add( pull( "ada", a, 0, 0, 0, 0 ) );
        all.add( post( "ada", "discard", ids( a ) ) );
        final Resp parked = park( a );
        parked.async.fireTimeout();
        all.add( parked );
        all.add( post( "ada", "leave", ids( a ) ) );
        for( final Resp r : all ) {
            assertEquals( "no-store", r.headers.get( "Cache-Control" ), () -> r.status + " " + r.out );
        }
    }
}
