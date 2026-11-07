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

import com.google.gson.JsonObject;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.collab.CollabSession;
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.collab.adapter.CollabWiring;
import com.wikantik.collab.model.LeaveResponse;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.rest.CollabWire.Answer;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The collaborative-editing HTTP surface, {@code /api/collab/{page}/{join|updates|publish|discard|leave|draft}}
 * (docs/MultiEdit.MD section 6.2 with the section 0 amendments; the wire contract is the shared fixture
 * {@code wikantik-frontend/src/collab/__fixtures__/collab-protocol.json}).
 *
 * <ul>
 *   <li>Every call needs an authenticated session (401): presence and attribution need an identity (section 9).</li>
 *   <li>{@code join} ({@link CollabJoin}): {@code view} is checked before anything about the page is revealed, then
 *       the registry checks {@code edit}/{@code createPages}, derived pages and the participant limit.</li>
 *   <li>Every later call carries {@code sessionId} and {@code participantId} (ruling P5); the participant must belong to
 *       the caller's author key (A6, 403 {@code not-your-participant}). An unknown session or participant is 409
 *       {@code rejoin}, an evicted one 403 {@code evicted} with the reason as {@code detail} (the session's tombstone).
 *       The session is found by {@code sessionId}; the path's page name may be its current name or any name it held
 *       before a rename, and any other name is 404. Permission checks ({@code publish}, {@code discard}) are on the
 *       session's current name, never the path's.</li>
 *   <li>{@code POST updates} ({@link CollabPush}): a body over {@code maxUpdateBytes} is 413 before it is parsed; wire
 *       defects (text or meta) are 400 {@code rejected} before the session is called; the session then refuses in the
 *       order identity, rate/freeze, stale base (text only), text checks, meta checks.</li>
 *   <li>{@code GET updates} is the long-poll (section 7.5): answered at once when anything is ahead of the cursors,
 *       else parked with {@code startAsync} for {@code poll.timeoutSeconds} ({@link CollabPull}).</li>
 *   <li>{@code publish} ({@link CollabPublish}) runs an uncached {@code edit} check of the publisher first, plus
 *       {@code createPages} for a page that does not exist yet (A5).</li>
 *   <li>{@code GET draft} ({@link CollabDraft}) is the publish dialog's summary: the draft against the published page,
 *       its HTML diff rendered outside the session lock, rate-limited per participant.</li>
 * </ul>
 *
 * <p>Once a request's session is known its id is in the log context as {@value #MDC_SESSION}.</p>
 *
 * <p><b>Kill switch.</b> Turning collaboration off refuses new joins only. Live sessions keep working: their pushes,
 * pulls, publishes, discards and leaves are served, so edits in flight complete and no draft is lost (plan Review Focus
 * 5, the fake's behaviour); drafts stay stored. The editor falls back to single-author mode on its next join.</p>
 *
 * <p>Every response is {@code Cache-Control: no-store}, and every session answer is reported to
 * {@link CollabSessionRegistry#observe} (a run of 503 {@code store} drops a wedged session for a reload).</p>
 */
public class CollabResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( CollabResource.class );

    /** A8: how long a woken long-poll waits so a burst of updates reaches the others in one answer. */
    static final long COALESCE_MS = 50;
    /** publish, discard and leave carry ids, a version and a change note. */
    private static final long SMALL_MAX_BYTES = 64L * 1024;
    private static final String SESSION_ID = "sessionId";
    private static final String PARTICIPANT_ID = "participantId";
    private static final String UPDATES = "updates";
    private static final String REJOIN = "rejoin";
    private static final String DRAFT = "draft";
    /**
     * The log context key of the collab session a request works on (section 12.3: collab logs carry the session id
     * alongside {@code correlation_id}); set once the session is known, removed when the request thread is done. A parked
     * long-poll's waiter captures it with the rest of the request's context.
     */
    static final String MDC_SESSION = "collab_session";
    /** The actions under {@code POST}. */
    private static final Set< String > ACTIONS = Set.of( "join", UPDATES, "publish", "discard", "leave" );

    /** The caller of a request: its author key (A6) and its wiki session (A5, opaque here). */
    record Caller( String authorKey, Object wikiSession ) {
    }

    /** Who is calling and what they may do; production asks the wiki ({@link CollabWikiAccess}), tests stub it. */
    interface Access {
        /** The caller, or null when the request is not authenticated. */
        Caller caller( HttpServletRequest request );

        boolean mayView( Caller caller, String pageName );

        /** An uncached {@code edit} check, plus {@code createPages} when {@code create}. */
        boolean mayEdit( Caller caller, String pageName, boolean create );
    }

    /** The resolved target of a post-join call. */
    private record Target( CollabSession session, String participantId ) {
    }

    private final transient CollabSessionRegistry fixedRegistry;
    /** True when the registry was handed in (tests); otherwise it is read from the engine per request. */
    private final boolean registryFixed;
    private transient Access access;
    private transient CollabPull.Delayer delayer;
    private transient CollabPull.Dispatch dispatch;
    private transient ScheduledExecutorService coalescer;
    private transient CollabDraft draft;
    private final long coalesceMs;

    public CollabResource() {
        this.fixedRegistry = null;
        this.registryFixed = false;
        this.coalesceMs = COALESCE_MS;
    }

    /** For tests: a fixed registry (null: none wired), the caller's access, a delayer and the coalescing window. */
    CollabResource( final CollabSessionRegistry registry, final Access access, final CollabPull.Delayer delayer,
                    final long coalesceMs ) {
        this( registry, access, delayer, ( ac, task ) -> task.run(), coalesceMs );
    }

    /** For tests: as above, with how long-poll answers are handed off for writing. */
    CollabResource( final CollabSessionRegistry registry, final Access access, final CollabPull.Delayer delayer,
                    final CollabPull.Dispatch dispatch, final long coalesceMs ) {
        this.fixedRegistry = registry;
        this.registryFixed = true;
        this.access = access;
        this.delayer = delayer;
        this.dispatch = dispatch;
        this.coalesceMs = coalesceMs;
    }

    /** For tests: the draft summary's differ and its rate limiter's clock (production: the wiki's diff, nanoTime). */
    void draftSupport( final CollabDraft.Differ differ, final LongSupplier nanos ) {
        this.draft = new CollabDraft( differ, nanos );
    }

    @Override
    public void init( final ServletConfig config ) throws ServletException {
        super.init( config );
        if( access == null ) {
            access = new CollabWikiAccess( this::getEngine );
        }
        if( delayer == null ) {
            coalescer = Executors.newSingleThreadScheduledExecutor( r -> {
                final Thread t = new Thread( r, "collab-coalesce" );
                t.setDaemon( true );
                return t;
            } );
            final ScheduledExecutorService s = coalescer;
            // the coalescer only schedules: the write itself is handed to the container's pool (dispatch)
            delayer = ( ms, task ) -> s.schedule( task, ms, TimeUnit.MILLISECONDS );
        }
        if( dispatch == null ) {
            dispatch = AsyncContext::start;
        }
        if( draft == null ) {
            draft = new CollabDraft( this::wikiDiff, System::nanoTime );
        }
    }

    /** The wiki's own HTML diff ({@code DiffResource}'s {@code ?format=html}), for a page that may not exist yet. */
    private String wikiDiff( final String pageName, final String from, final String to ) {
        final Engine engine = getEngine();
        final Page page = Wiki.contents().page( engine, pageName );
        final Context context = Wiki.context().create( engine, page );
        return getSubsystems().rendering().differenceManager().makeDiff( context, from, to );
    }

    @Override
    public void destroy() {
        if( coalescer != null ) {
            coalescer.shutdownNow();
        }
        super.destroy();
    }

    /** Collaboration is same-origin only: its calls are credentialed and state-changing. */
    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    private Optional< CollabSessionRegistry > registry() {
        return registryFixed ? Optional.ofNullable( fixedRegistry ) : CollabWiring.registryFor( getEngine() );
    }

    // ---- routing ---------------------------------------------------------------------------------------------------

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        try {
            post( request, response );
        } finally {
            ThreadContext.remove( MDC_SESSION );
        }
    }

    private void post( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        CollabWire.noStore( response );
        final String[] route = CollabRequests.route( request );
        final String action = route == null ? "" : route[ 1 ];
        if( !ACTIONS.contains( action ) ) {
            sendNotFound( response, "Unknown collaboration call" );
            return;
        }
        final Caller caller = admitted( request, response, route[ 0 ] );
        if( caller == null ) {
            return;
        }
        final String page = route[ 0 ];
        if( "join".equals( action ) ) {
            final Answer joined = CollabJoin.run( registry(), access, request, caller, page );
            if( joined.status() == 200 ) {
                ThreadContext.put( MDC_SESSION, ( ( JsonObject ) joined.body() ).get( SESSION_ID ).getAsString() );
            }
            CollabWire.write( response, joined );
            return;
        }
        final long max = UPDATES.equals( action )
            ? registry().map( r -> ( long ) r.config().maxUpdateBytes() ).orElse( SMALL_MAX_BYTES ) : SMALL_MAX_BYTES;
        final Object parsed = CollabRequests.readJson( request, max );
        if( parsed instanceof Answer refused ) {
            CollabWire.write( response, refused );
            return;
        }
        final JsonObject body = ( JsonObject ) parsed;
        final Object target = target( page, RestJson.getJsonString( body, SESSION_ID ),
            RestJson.getJsonString( body, PARTICIPANT_ID ) );
        if( target instanceof Answer refused ) {
            // a leave of a participant or session that is already gone is done (a repeated leave or beacon)
            CollabWire.write( response, "leave".equals( action ) && refused.status() == 409 ? CollabWire.noContent()
                : refused );
            return;
        }
        final Target t = ( Target ) target;
        CollabWire.write( response, switch( action ) {
            case UPDATES -> push( t, caller, body );
            case "publish" -> publish( t, caller, t.session().pageName(), body );   // checks and saves the current name
            case "discard" -> discard( t, caller, t.session().pageName() );
            default -> leave( t, caller );
        } );
    }

    /** {@code GET /updates} (the long-poll) and {@code GET /draft} (the draft summary, {@link CollabDraft}). */
    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        try {
            get( request, response );
        } finally {
            ThreadContext.remove( MDC_SESSION );   // a parked pull's waiter has captured it already
        }
    }

    private void get( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        CollabWire.noStore( response );
        final String[] route = CollabRequests.route( request );
        if( route == null || !UPDATES.equals( route[ 1 ] ) && !DRAFT.equals( route[ 1 ] ) ) {
            sendNotFound( response, "Unknown collaboration call" );
            return;
        }
        final Caller caller = admitted( request, response, route[ 0 ] );
        if( caller == null ) {
            return;
        }
        final Object target = target( route[ 0 ], request.getParameter( SESSION_ID ),
            request.getParameter( PARTICIPANT_ID ) );
        if( target instanceof Answer refused ) {
            CollabWire.write( response, refused );
            return;
        }
        final Target t = ( Target ) target;
        if( DRAFT.equals( route[ 1 ] ) ) {
            // rendered outside any session lock: the session's snapshot is the only locked step
            CollabWire.write( response, draft.summarise( t.session(), t.participantId(), caller.authorKey(),
                ( status, reason ) -> observe( t.session(), status, reason ) ) );
            return;
        }
        pull( request, response, t, caller );
    }

    /**
     * The caller, or null once an answer has been sent: 400 {@code rejected} for a page name the wiki refuses (D5/D21,
     * checked before any permission or session lookup), else 401 without an authenticated session.
     */
    private Caller admitted( final HttpServletRequest request, final HttpServletResponse response, final String page )
            throws IOException {
        if( PageResource.isInvalidPageName( page ) ) {
            CollabWire.write( response, CollabWire.rejected( "Page name is invalid: must be 1-"
                + PageResource.MAX_PAGE_NAME_LENGTH + " characters and must not contain ':', '/', '\\', or control chars." ) );
            return null;
        }
        final Caller caller = access.caller( request );
        if( caller == null ) {
            sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required" );
        }
        return caller;
    }

    /** The session and participant a post-join call names, or the refusal: 400, 409 {@code rejoin}, 404. */
    private Object target( final String page, final String sessionId, final String participantId ) {
        if( participantId == null || participantId.isBlank() ) {
            return CollabWire.rejected( "participantId required" );
        }
        final UUID id;
        try {
            id = UUID.fromString( sessionId == null ? "" : sessionId );
        } catch( final IllegalArgumentException e ) {
            LOG.debug( "collab: unparseable session id '{}': {}", sessionId, e.getMessage() );
            return CollabWire.refusal( 409, REJOIN, 0 );
        }
        final Optional< CollabSession > s = registry().flatMap( r -> r.live( id ) );
        if( s.isEmpty() ) {
            return CollabWire.refusal( 409, REJOIN, 0 );
        }
        if( !s.get().answersTo( page ) ) {   // its current name or one it held before a rename
            final JsonObject o = new JsonObject();
            o.addProperty( "error", "not_found" );
            return new Answer( 404, o );
        }
        ThreadContext.put( MDC_SESSION, id.toString() );
        return new Target( s.get(), participantId );
    }

    private void observe( final CollabSession s, final int status, final String reason ) {
        registry().ifPresent( r -> r.observe( s.id(), status, reason ) );
    }

    // ---- push and pull ---------------------------------------------------------------------------------------------

    private Answer push( final Target t, final Caller caller, final JsonObject body ) {
        final int rate = registry().map( reg -> reg.config().pushesPerSecond() ).orElse( 1 );
        return CollabPush.run( t.session(), t.participantId(), caller.authorKey(), body, rate,
            ( status, reason ) -> observe( t.session(), status, reason ) );
    }

    /** The long-poll; a removed participant's parked pull is answered by the session (its eviction tombstone). */
    private void pull( final HttpServletRequest request, final HttpServletResponse response, final Target t,
                       final Caller caller ) throws IOException {
        final CollabSession s = t.session();
        final long timeoutMillis = registry().map( r -> r.config().pollTimeoutSeconds() ).orElse( 25 ) * 1000L;
        CollabPull.serve( request, response, cursor -> new CollabPull( s, t.participantId(), cursor, response,
            new CollabPull.Handoff( delayer, dispatch, coalesceMs ), r -> observe( s, r.status(), r.reason() ) ),
            caller.authorKey(), timeoutMillis );
    }

    // ---- publish, discard, leave -----------------------------------------------------------------------------------

    private Answer publish( final Target t, final Caller caller, final String page, final JsonObject body ) {
        final Integer version = CollabPush.integer( body.get( "version" ) );
        if( version == null ) {
            return CollabWire.rejected( "version must be an integer" );
        }
        final String note = RestJson.getJsonString( body, "changeNote" );
        final boolean create = t.session().view().basePageVersion() == 0;
        if( !access.mayEdit( caller, page, create ) ) {
            LOG.info( "collab session {}: publish by {} refused: no {} on {}", t.session().id(), t.participantId(),
                create ? "edit/createPages" : "edit", page );
            return CollabWire.forbidden( page );
        }
        return CollabPublish.run( t.session(), t.participantId(), caller.authorKey(), version, note == null ? "" : note,
            RestJson.getJsonString( body, "markupSyntax" ),
            page, ( status, reason ) -> observe( t.session(), status, reason ) );
    }

    /** An uncached {@code edit} check first (A5): a discard rewrites the shared draft. */
    private Answer discard( final Target t, final Caller caller, final String page ) {
        if( !access.mayEdit( caller, page, false ) ) {
            return CollabWire.forbidden( page );
        }
        final PushResponse r = t.session().discard( t.participantId(), caller.authorKey() );
        observe( t.session(), r.status(), r.reason() );
        return r.ok() ? CollabWire.noContent() : CollabWire.refusal( r.status(), r.reason(), r.detail(), r.version() );
    }

    /** A participant that is already gone (409 rejoin) is answered 204: a repeated leave or beacon is harmless. */
    private Answer leave( final Target t, final Caller caller ) {
        final LeaveResponse r = t.session().leave( t.participantId(), caller.authorKey() );
        observe( t.session(), r.status(), r.reason() );
        return r.status() == 200 || r.status() == 409 ? CollabWire.noContent()
            : CollabWire.refusal( r.status(), r.reason(), r.detail(), 0 );
    }
}
