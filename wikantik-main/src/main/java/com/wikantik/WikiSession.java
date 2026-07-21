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
package com.wikantik;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.apikeys.ApiKeyPrincipalRequest;
import com.wikantik.auth.GroupPrincipal;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.SessionMonitor;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.authorize.Role;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiSecurityEvent;
import com.wikantik.util.HttpUtil;

import javax.security.auth.Subject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


/**
 * <p>Default implementation for {@link Session}.</p>
 * <p>In addition to methods for examining individual <code>WikiSession</code> objects, this class also contains a number of static
 * methods for managing WikiSessions for an entire wiki. These methods allow callers to find, query and remove WikiSession objects, and
 * to obtain a list of the current wiki session users.</p>
 */
public final class WikiSession implements Session {

    private static final Logger LOG = LogManager.getLogger( WikiSession.class );

    private static final String ALL = "*";

    private static final ThreadLocal< Session > guestSession = new ThreadLocal<>();

    private final Subject subject = new Subject();

    private final Map< String, Set< String > > messages  = new ConcurrentHashMap<>();

    /** The Engine that created this session. */
    private Engine engine;

    // Managers stored at construction time to avoid repeated engine.getManager() lookups.
    private GroupManager groupManager;
    private UserManager userManager;
    @SuppressWarnings( "PMD.UnusedPrivateField" ) // Held for upcoming event-registration work; wiring lands in the next auth refactor.
    private AuthenticationManager authenticationManager;

    private String antiCsrfToken;
    private String status            = ANONYMOUS;

    private Principal userPrincipal  = WikiPrincipal.GUEST;

    private Principal loginPrincipal = WikiPrincipal.GUEST;

    private Locale cachedLocale      = Locale.getDefault();

    /**
     * Returns <code>true</code> if one of this WikiSession's user Principals can be shown to belong to a particular wiki group. If
     * the user is not authenticated, this method will always return <code>false</code>.
     *
     * @param group the group to test
     * @return the result
     */
    protected boolean isInGroup( final Group group ) {
        return Arrays.stream(getPrincipals()).anyMatch(principal -> isAuthenticated() && group.isMember(principal));
    }

    /**
     * Private constructor to prevent WikiSession from being instantiated directly.
     */
    private WikiSession() {
    }

    /**
     * Package-private constructor for dependency injection. Accepts the three manager
     * instances used by this session so that no further {@code engine.getManager()} calls
     * are required in instance methods.
     *
     * @param groupManager          the GroupManager to use for group-principal injection
     * @param userManager           the UserManager to use for user-profile injection
     * @param authenticationManager the AuthenticationManager for event registration
     */
    WikiSession( final GroupManager groupManager, final UserManager userManager, final AuthenticationManager authenticationManager ) {
        this.groupManager = groupManager;
        this.userManager = userManager;
        this.authenticationManager = authenticationManager;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isAsserted() {
        return subject.getPrincipals().contains( Role.ASSERTED );
    }

    /** {@inheritDoc} */
    @Override
    public boolean isAuthenticated() {
        // If Role.AUTHENTICATED is in principals set, always return true.
        if ( subject.getPrincipals().contains( Role.AUTHENTICATED ) ) {
            return true;
        }

        // With non-JSPWiki LoginModules, the role may not be there, so we need to add it if the user really is authenticated.
        if ( !isAnonymous() && !isAsserted() ) {
            subject.getPrincipals().add( Role.AUTHENTICATED );
            return true;
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isAnonymous() {
        final Set< Principal > principals = subject.getPrincipals();
        return principals.contains( Role.ANONYMOUS ) ||
               principals.contains( WikiPrincipal.GUEST ) ||
               HttpUtil.isIPV4Address( getUserPrincipal().getName() );
    }

    /** {@inheritDoc} */
    @Override
    public Principal getLoginPrincipal() {
        return loginPrincipal;
    }

    /** {@inheritDoc} */
    @Override
    public Principal getUserPrincipal() {
        return userPrincipal;
    }

    /** {@inheritDoc} */
    @Override
    public String antiCsrfToken() {
        return antiCsrfToken;
    }

    /** {@inheritDoc} */
    @Override
    public Locale getLocale() {
        return cachedLocale;
    }

    /** {@inheritDoc} */
    @Override
    public void addMessage( final String message ) {
        addMessage( ALL, message );
    }

    /** {@inheritDoc} */
    @Override
    public void addMessage( final String topic, final String message ) {
        if ( topic == null ) {
            throw new IllegalArgumentException( "addMessage: topic cannot be null." );
        }
        final Set< String > topicMessages = messages.computeIfAbsent( topic, k -> new LinkedHashSet<>() );
        topicMessages.add( StringUtils.defaultString( message ) );
    }

    /** {@inheritDoc} */
    @Override
    public void clearMessages() {
        messages.clear();
    }

    /** {@inheritDoc} */
    @Override
    public void clearMessages( final String topic ) {
        final Set< String > topicMessages = messages.get( topic );
        if ( topicMessages != null ) {
            topicMessages.clear();
        }
    }

    /** {@inheritDoc} */
    @Override
    public String[] getMessages() {
        return getMessages( ALL );
    }

    /** {@inheritDoc} */
    @Override
    public String[] getMessages( final String topic ) {
        final Set< String > topicMessages = messages.get( topic );
        if( topicMessages == null || topicMessages.isEmpty()) {
            return new String[ 0 ];
        }
        return topicMessages.toArray( new String[0] );
    }

    /** {@inheritDoc} */
    @Override
    public Principal[] getPrincipals() {

        // Take the first non Role as the main Principal

        return subject.getPrincipals().stream().filter(AuthenticationManager::isUserPrincipal).toArray(Principal[]::new);
    }

    /** {@inheritDoc} */
    @Override
    public Principal[] getRoles() {
        final Set< Principal > roles = new HashSet<>();

        // Add all the Roles possessed by the Subject directly
        roles.addAll( subject.getPrincipals( Role.class ) );

        // Add all the GroupPrincipals possessed by the Subject directly
        roles.addAll( subject.getPrincipals( GroupPrincipal.class ) );

        // Return a defensive copy
        final Principal[] roleArray = roles.toArray( new Principal[0] );
        Arrays.sort( roleArray, WikiPrincipal.COMPARATOR );
        return roleArray;
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasPrincipal( final Principal principal ) {
        return subject.getPrincipals().contains( principal );
    }

    /**
     * Listens for WikiEvents generated by source objects such as the GroupManager, UserManager or AuthenticationManager. This method adds
     * Principals to the private Subject managed by the WikiSession.
     *
     * @see com.wikantik.event.WikiEventListener#actionPerformed(WikiEvent)
     */
    @Override
    public void actionPerformed( final WikiEvent event ) {
        if ( event instanceof WikiSecurityEvent e && e.getTarget() != null ) {
            switch( e.getType() ) {
                case WikiSecurityEvent.GROUP_ADD          -> handleGroupAdd( e );
                case WikiSecurityEvent.GROUP_REMOVE       -> handleGroupRemove( e );
                case WikiSecurityEvent.GROUP_CLEAR_GROUPS -> handleGroupClear();
                case WikiSecurityEvent.LOGIN_INITIATED    -> { /* no-op */ }
                case WikiSecurityEvent.PRINCIPAL_ADD      -> handlePrincipalAdd( e );
                case WikiSecurityEvent.LOGIN_ANONYMOUS    -> handleLoginAnonymous( e );
                case WikiSecurityEvent.LOGIN_ASSERTED     -> handleLoginAsserted( e );
                case WikiSecurityEvent.LOGIN_AUTHENTICATED -> handleLoginAuthenticated( e );
                case WikiSecurityEvent.PROFILE_SAVE       -> handleProfileSave( e );
                case WikiSecurityEvent.PROFILE_NAME_CHANGED -> handleProfileNameChanged( e );
                default -> { /* unrecognized event — no action */ }
            }
        }
    }

    private void handleGroupAdd( final WikiSecurityEvent e ) {
        final Group groupAdd = ( Group )e.getTarget();
        if( isInGroup( groupAdd ) ) {
            subject.getPrincipals().add( groupAdd.getPrincipal() );
        }
    }

    private void handleGroupRemove( final WikiSecurityEvent e ) {
        final Group group = ( Group )e.getTarget();
        subject.getPrincipals().remove( group.getPrincipal() );
    }

    private void handleGroupClear() {
        subject.getPrincipals().removeAll( subject.getPrincipals( GroupPrincipal.class ) );
    }

    private void handlePrincipalAdd( final WikiSecurityEvent e ) {
        final WikiSession targetPA = ( WikiSession )e.getTarget();
        if( this.equals( targetPA ) && AUTHENTICATED.equals( status ) ) {
            subject.getPrincipals().add( ( Principal )e.getPrincipal() );
        }
    }

    private void handleLoginAnonymous( final WikiSecurityEvent e ) {
        applyLogin( e, ANONYMOUS, Role.ANONYMOUS );
    }

    private void handleLoginAsserted( final WikiSecurityEvent e ) {
        applyLogin( e, ASSERTED, Role.ASSERTED );
    }

    private void handleLoginAuthenticated( final WikiSecurityEvent e ) {
        applyLogin( e, AUTHENTICATED, Role.AUTHENTICATED );
        if( this.equals( ( WikiSession )e.getTarget() ) ) {
            injectUserProfilePrincipals();  // Add principals for the user profile
            injectGroupPrincipals();        // Inject group principals
        }
    }

    /**
     * Shared helper for the three login transitions (anonymous, asserted, authenticated). Sets the session status, clears all
     * existing principals, then populates with the login principal plus {@link Role#ALL} and the given status role.
     *
     * @param e          the security event carrying the target session and login principal
     * @param newStatus  one of {@link Session#ANONYMOUS}, {@link Session#ASSERTED}, {@link Session#AUTHENTICATED}
     * @param statusRole the Role constant matching {@code newStatus}
     */
    private void applyLogin( final WikiSecurityEvent e, final String newStatus, final Role statusRole ) {
        final WikiSession target = ( WikiSession )e.getTarget();
        if( this.equals( target ) ) {
            status = newStatus;
            loginPrincipal = ( Principal )e.getPrincipal();
            userPrincipal = loginPrincipal;
            final Set< Principal > principals = subject.getPrincipals();
            principals.clear();
            principals.add( loginPrincipal );
            principals.add( Role.ALL );
            principals.add( statusRole );
        }
    }

    private void handleProfileSave( final WikiSecurityEvent e ) {
        final WikiSession sourcePS = e.getSrc();
        if( this.equals( sourcePS ) ) {
            injectUserProfilePrincipals();  // Add principals for the user profile
            injectGroupPrincipals();        // Inject group principals
        }
    }

    private void handleProfileNameChanged( final WikiSecurityEvent e ) {
        // Refresh user principals based on new user profile
        final WikiSession sourcePNC = e.getSrc();
        if( this.equals( sourcePNC ) && AUTHENTICATED.equals( status ) ) {
            // To prepare for refresh, set the new full name as the primary principal
            final UserProfile[] profiles = ( UserProfile[] )e.getTarget();
            final UserProfile newProfile = profiles[ 1 ];
            if( newProfile.getFullname() == null ) {
                throw new IllegalStateException( "User profile FullName cannot be null." );
            }

            loginPrincipal = new WikiPrincipal( newProfile.getLoginName() );
            final Set< Principal > principals = subject.getPrincipals();
            principals.clear();
            principals.add( loginPrincipal );
            principals.add( Role.ALL );
            principals.add( Role.AUTHENTICATED );

            // Add the user and group principals
            injectUserProfilePrincipals();  // Add principals for the user profile
            injectGroupPrincipals();        // Inject group principals
        }
    }

    /** {@inheritDoc} */
    @Override
    public void invalidate() {
        subject.getPrincipals().clear();
        subject.getPrincipals().add( WikiPrincipal.GUEST );
        subject.getPrincipals().add( Role.ANONYMOUS );
        subject.getPrincipals().add( Role.ALL );
        userPrincipal = WikiPrincipal.GUEST;
        loginPrincipal = WikiPrincipal.GUEST;
    }

    /**
     * Injects GroupPrincipal objects into the user's Principal set based on the groups the user belongs to. For Groups, the algorithm
     * first calls the {@link GroupManager#getRoles()} to obtain the array of GroupPrincipals the authorizer knows about. Then, the
     * method {@link GroupManager#isUserInRole(Session, Principal)} is called for each Principal. If the user is a member of the
     * group, an equivalent GroupPrincipal is injected into the user's principal set. Existing GroupPrincipals are flushed and replaced.
     * This method should generally be called after a user's {@link com.wikantik.auth.user.UserProfile} is saved. If the wiki session
     * is null, or there is no matching user profile, the method returns silently.
     */
    protected void injectGroupPrincipals() {
        // Flush the existing GroupPrincipals
        subject.getPrincipals().removeAll( subject.getPrincipals(GroupPrincipal.class) );

        // Get the GroupManager and test for each Group
        final GroupManager manager = groupManager != null ? groupManager : AuthSubsystemBridge.fromLegacyEngine( engine ).groups();
        for( final Principal group : manager.getRoles() ) {
            if ( manager.isUserInRole( this, group ) ) {
                subject.getPrincipals().add( group );
            }
        }
    }

    /**
     * Adds Principal objects to the Subject that correspond to the logged-in user's profile attributes for the wiki name, full name
     * and login name. These Principals will be WikiPrincipals, and they will replace all other WikiPrincipals in the Subject. <em>Note:
     * this method is never called during anonymous or asserted sessions.</em>
     */
    protected void injectUserProfilePrincipals() {
        // Search for the user profile
        final String searchId = loginPrincipal.getName();
        if ( searchId == null ) {
            // Oh dear, this wasn't an authenticated user after all
            LOG.info("Refresh principals failed because WikiSession had no user Principal; maybe not logged in?");
            return;
        }

        // Look up the user and go get the new Principals
        final UserManager um = userManager != null ? userManager : AuthSubsystemBridge.fromLegacyEngine( engine ).users();
        final UserDatabase database = um.getUserDatabase();
        if( database == null ) {
            throw new IllegalStateException( "User database cannot be null." );
        }
        try {
            final UserProfile profile = database.find( searchId );
            final Principal[] principals = database.getPrincipals( profile.getLoginName() );
            for( final Principal principal : principals ) {
                // Add the Principal to the Subject
                subject.getPrincipals().add( principal );

                // Set the user principal if needed; we prefer FullName, but the WikiName will also work
                final boolean isFullNamePrincipal = ( principal instanceof WikiPrincipal &&
                                                      WikiPrincipal.FULL_NAME.equals( ( ( WikiPrincipal )principal ).getType() ) );
                if ( isFullNamePrincipal ) {
                   userPrincipal = principal;
                } else if ( !( userPrincipal instanceof WikiPrincipal ) ) {
                    userPrincipal = principal;
                }
            }
        } catch ( final NoSuchPrincipalException e ) {
            // We will get here if the user has a principal but not a profile
            // For example, it's a container-managed user who hasn't set up a profile yet
            LOG.warn("User profile '{}' not found. This is normal for container-auth users who haven't set up a profile yet.", searchId);
        }
    }

    /** {@inheritDoc} */
    @Override
    public String getStatus() {
        return status;
    }

    /** {@inheritDoc} */
    @Override
    public Subject getSubject() {
        return subject;
    }

    /**
     * Removes the wiki session associated with the user's HTTP request from the cache of wiki sessions, typically as part of a
     * logout process.
     *
     * @param engine the wiki engine
     * @param request the user's HTTP request
     */
    public static void removeWikiSession( final Engine engine, final HttpServletRequest request ) {
        if ( engine == null || request == null ) {
            throw new IllegalArgumentException( "Request or engine cannot be null." );
        }
        final SessionMonitor monitor = SessionMonitor.getInstance( engine );
        monitor.remove( request.getSession() );
        guestSession.remove();
    }

    /**
     * Clears the thread-local guest session for the current thread. Called
     * during webapp shutdown from the servlet context listener to prevent
     * Tomcat from reporting a ThreadLocal leak for the container thread.
     */
    public static void removeCurrentGuestSession() {
        guestSession.remove();
    }

    /**
     * <p>Static factory method that returns the Session object associated with the current HTTP request. This method looks up
     * the associated HttpSession in an internal WeakHashMap and attempts to retrieve the WikiSession. If not found, one is created.
     * This method is guaranteed to always return a Session, although the authentication status is unpredictable until the user
     * attempts to log in. If the servlet request parameter is <code>null</code>, a synthetic {@link #guestSession(Engine)} is
     * returned.</p>
     * <p>When a session is created, this method attaches a WikiEventListener to the GroupManager, UserManager and AuthenticationManager,
     * so that changes to users, groups, logins, etc. are detected automatically.</p>
     *
     * @param engine the engine
     * @param request the servlet request object
     * @return the existing (or newly created) session
     */
    public static Session getWikiSession( final Engine engine, final HttpServletRequest request ) {
        if ( request == null ) {
            LOG.debug( "Looking up WikiSession for NULL HttpRequest: returning guestSession()" );
            return staticGuestSession( engine );
        }

        // Fast path: fully-anonymous requests (crawlers, API readers, unauthenticated page views)
        // carry no existing session, no principal, and none of the identity-bearing cookies.
        // Creating a 60-minute HttpSession + SessionMonitor entry for every such request is the
        // confirmed root cause of the ~560K-session accumulation seen under sustained load.
        // Return the per-thread transient guest instead — no HttpSession is created and nothing
        // is registered in SessionMonitor.
        if ( !needsPersistentSession( request ) ) {
            final WikiSession guest = ( WikiSession )staticGuestSession( engine );
            guest.cachedLocale = request.getLocale();
            return guest;
        }

        // Look for a WikiSession associated with the user's Http Session and create one if it isn't there yet.
        final HttpSession session = request.getSession();
        final SessionMonitor monitor = SessionMonitor.getInstance( engine );
        final WikiSession wikiSession = ( WikiSession )monitor.find( session );

        // Attach reference to wiki engine
        wikiSession.engine = engine;
        wikiSession.cachedLocale = request.getLocale();
        return wikiSession;
    }

    /**
     * Returns {@code true} if this request carries any signal that requires a persistent
     * (HttpSession-backed, SessionMonitor-registered) wiki session.  The signals are:
     * <ul>
     *   <li>An existing {@link HttpSession} is already associated with the request.</li>
     *   <li>The servlet container or an upstream filter has already bound a user principal.</li>
     *   <li>The asserted-name cookie is present ({@code WikantikAssertedName} or legacy
     *       {@code JSPWikiAssertedName}).</li>
     *   <li>The remember-me / cookie-auth UID is present ({@code WikantikUID} or legacy
     *       {@code JSPWikiUID}).</li>
     * </ul>
     * When uncertain, returns {@code true} so the old session-creating path is followed —
     * a false positive costs one extra session; a false negative would silently drop identity.
     *
     * @param request the incoming HTTP request
     * @return {@code true} if a persistent session is required; {@code false} for provably
     *         anonymous requests that can share the transient thread-local guest
     */
    private static boolean needsPersistentSession( final HttpServletRequest request ) {
        // 1. Already has an HttpSession — keep current behavior.
        if ( request.getSession( false ) != null ) {
            return true;
        }
        // 2. A bound principal requires a persistent session UNLESS it came from a
        //    stateless API-key/bearer token. Token auth (MCP, tool server) is
        //    re-verified per request and authorized at the access filter — the
        //    WikiSession is an incidental guest either way, so creating + retaining
        //    an HttpSession per call only leaks. Session-based schemes (container,
        //    SSO, form/basic login) still get a persistent session.
        if ( request.getUserPrincipal() != null
                && !ApiKeyPrincipalRequest.AUTH_TYPE.equals( request.getAuthType() ) ) {
            return true;
        }
        // 2b. A Basic-auth request authenticates this request (BasicAuthFilter does
        //     find()-then-login on the returned session), so it must resolve to a
        //     persistent session — never the shared transient guest. Bearer/API-key
        //     is handled above via the API_KEY auth-type and stays stateless.
        final String authz = request.getHeader( "Authorization" );
        if ( authz != null && authz.regionMatches( true, 0, "Basic ", 0, 6 ) ) {
            return true;
        }
        // 3. Scan cookies for any identity-bearing name.
        final jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if ( cookies != null ) {
            for ( final jakarta.servlet.http.Cookie cookie : cookies ) {
                final String name = cookie.getName();
                if ( "WikantikAssertedName".equals( name ) || "JSPWikiAssertedName".equals( name )
                        || "WikantikUID".equals( name ) || "JSPWikiUID".equals( name ) ) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Static factory method that creates a new "guest" session containing a single user Principal
     * {@link com.wikantik.auth.WikiPrincipal#GUEST}, plus the role principals {@link Role#ALL} and {@link Role#ANONYMOUS}. This
     * method also adds the session as a listener for GroupManager, AuthenticationManager and UserManager events.
     *
     * @param engine the wiki engine
     * @return the guest wiki session
     */
    public static Session guestSession( final Engine engine ) {
        // Resolve managers once here and store them on the session for later use.
        final GroupManager groupMgr = AuthSubsystemBridge.fromLegacyEngine( engine ).groups();
        final AuthenticationManager authMgr = AuthSubsystemBridge.fromLegacyEngine( engine ).authentication();
        final UserManager userMgr = AuthSubsystemBridge.fromLegacyEngine( engine ).users();

        final WikiSession session = new WikiSession( groupMgr, userMgr, authMgr );
        session.engine = engine;
        session.invalidate();
        session.antiCsrfToken = UUID.randomUUID().toString();

        // Register the session with the per-engine SessionEventDispatcher. The
        // dispatcher is registered as a listener on the three managers EXACTLY
        // ONCE per engine; this `register` call is a single weak-set add. Before
        // this change, every guest session installed three separate manager-side
        // listener entries — which (post WeakHashMap rewrite) was O(1) per call
        // but still grew a per-session listener list monotonically with
        // anonymous HTTP traffic. The dispatcher's fan-out preserves the
        // event-delivery contract (every alive session receives every event from
        // the three managers; each session's actionPerformed filters by target).
        com.wikantik.auth.SessionEventDispatcher
            .forEngine( engine, groupMgr, authMgr, userMgr )
            .register( session );

        return session;
    }

    /**
     *  Returns a static guest session, which is available for this thread only.  This guest session is used internally whenever
     *  there is no HttpServletRequest involved, but the request is done e.g. when embedding JSPWiki code.
     *
     *  @param engine Engine for this session
     *  @return A static WikiSession which is shared by all in this same Thread.
     */
    // FIXME: Should really use WeakReferences to clean away unused sessions.
    private static Session staticGuestSession( final Engine engine ) {
        Session session = guestSession.get();
        // The cached guest must belong to the CALLER's engine: a thread that has
        // served engine A must not hand A's guest (with A's manager references,
        // possibly from a stopped engine) to engine B. Cross-engine leakage made
        // permission filters silently drop rows and session lookups fail on the
        // wrong engine in multi-engine hosts and forked test JVMs.
        if( session == null || !( session instanceof WikiSession ws ) || ws.engine != engine ) {
            session = guestSession( engine );
            guestSession.set( session );
        }

        return session;
    }

}
