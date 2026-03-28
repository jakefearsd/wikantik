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
package com.wikantik.auth;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiSecurityEvent;
import com.wikantik.util.comparators.PrincipalComparator;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.security.Principal;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 *  <p>Manages Sessions for different Engines.</p>
 *  <p>The Sessions are stored both in the remote user HttpSession and in the SessionMonitor for the Engine.
 *  This class must be configured as a session listener in the web.xml for the wiki web application.</p>
 */
public class SessionMonitor implements HttpSessionListener {

    private static final Logger LOG = LogManager.getLogger( SessionMonitor.class );

    /** Map with Engines as keys, and SessionMonitors as values. */
    private static final ConcurrentHashMap< Engine, SessionMonitor > monitors = new ConcurrentHashMap<>();

    /** Thread-safe map from HTTP session ID to WikiSession. Entries are removed
     *  explicitly via {@link #remove(HttpSession)} when the container destroys a session. */
    private final ConcurrentHashMap< String, Session > sessions = new ConcurrentHashMap<>();

    private Engine engine;

    private final PrincipalComparator comparator = new PrincipalComparator();

    /**
     * Returns the instance of the SessionMonitor for this wiki. Only one SessionMonitor exists per Engine.
     *
     * @param engine the wiki engine
     * @return the session monitor
     */
    public static SessionMonitor getInstance( final Engine engine ) {
        if( engine == null ) {
            throw new IllegalArgumentException( "Engine cannot be null." );
        }
        return monitors.computeIfAbsent( engine, SessionMonitor::new );
    }

    /** Construct the SessionListener */
    public SessionMonitor() {
    }

    private SessionMonitor( final Engine engine ) {
        this.engine = engine;
    }

    /**
     * Looks up the wiki session for an HTTP session without creating one.
     * Returns {@code null} if no session is found.
     */
    private Session findSession( final HttpSession session ) {
        final String sid = ( session == null ) ? "(null)" : session.getId();
        return sessions.get( sid );
    }

    /**
     * Looks up the wiki session associated with a user's HTTP session. If none exists,
     * atomically creates a new guest session via {@link ConcurrentHashMap#computeIfAbsent}.
     * This method is guaranteed to return a non-{@code null} WikiSession and is lock-free
     * for the common case (session already exists).
     *
     * @param session the HTTP session
     * @return the wiki session
     */
    public final Session find( final HttpSession session ) {
        final String sid = ( session == null ) ? "(null)" : session.getId();
        return sessions.computeIfAbsent( sid, this::createGuestSession );
    }

    /**
     * Looks up the wiki session by session ID string. If none exists,
     * atomically creates a new guest session.
     *
     * @param sessionId the HTTP session ID
     * @return the wiki session
     */
    public final Session find( final String sessionId ) {
        final String sid = ( sessionId == null ) ? "(null)" : sessionId;
        return sessions.computeIfAbsent( sid, this::createGuestSession );
    }

    /**
     * Creates a new guest session for the given session ID.
     * Called by {@link ConcurrentHashMap#computeIfAbsent} — guaranteed to run at most once per key.
     */
    private Session createGuestSession( final String sessionId ) {
        LOG.debug( "Session for session ID={}... not found. Creating guestSession()", sessionId );
        return Wiki.session().guest( engine );
    }

    /**
     * Removes the wiki session associated with the user's HttpRequest from the session cache.
     *
     * @param request the user's HTTP request
     */
    public final void remove( final HttpServletRequest request ) {
        if( request == null ) {
            throw new IllegalArgumentException( "Request cannot be null." );
        }
        remove( request.getSession() );
    }

    /**
     * Removes the wiki session associated with the user's HttpSession from the session cache.
     *
     * @param session the user's HTTP session
     */
    public final void remove( final HttpSession session ) {
        if( session == null ) {
            throw new IllegalArgumentException( "Session cannot be null." );
        }
        sessions.remove( session.getId() );
    }

    /**
     * Returns the current number of active wiki sessions.
     * @return the number of sessions
     */
    public final int sessions()
    {
        return userPrincipals().length;
    }

    /**
     * <p>Returns the current wiki users as a sorted array of Principal objects. The principals are those returned by
     * each WikiSession's {@link Session#getUserPrincipal()}'s method.</p>
     * <p>To obtain the list of current WikiSessions, we iterate through our session Map and obtain the list of values,
     * which are WikiSessions wrapped in {@link java.lang.ref.WeakReference} objects. Those <code>WeakReference</code>s
     * whose <code>get()</code> method returns non-<code>null</code> values are valid sessions.</p>
     *
     * @return the array of user principals
     */
    public final Principal[] userPrincipals() {
        final Principal[] p = sessions.values().stream()
                .map( Session::getUserPrincipal )
                .toArray( Principal[]::new );
        Arrays.sort( p, comparator );
        return p;
    }

    /**
     * Registers a WikiEventListener with this instance.
     *
     * @param listener the event listener
     * @since 2.4.75
     */
    public final synchronized void addWikiEventListener( final WikiEventListener listener ) {
        WikiEventManager.addWikiEventListener( this, listener );
    }

    /**
     * Un-registers a WikiEventListener with this instance.
     *
     * @param listener the event listener
     * @since 2.4.75
     */
    public final synchronized void removeWikiEventListener( final WikiEventListener listener ) {
        WikiEventManager.removeWikiEventListener( this, listener );
    }

    /**
     * Fires a WikiSecurityEvent to all registered listeners.
     *
     * @param type  the event type
     * @param principal the user principal associated with this session
     * @param session the wiki session
     * @since 2.4.75
     */
    protected final void fireEvent( final int type, final Principal principal, final Session session ) {
        if( WikiEventManager.isListening( this ) ) {
            WikiEventManager.fireEvent( this, new WikiSecurityEvent( this, type, principal, session ) );
        }
    }

    /**
     * Fires when the web container creates a new HTTP session.
     * 
     * @param se the HTTP session event
     */
    @Override
    public void sessionCreated( final HttpSessionEvent se ) {
        final HttpSession session = se.getSession();
        LOG.debug( "Created session: {}.", session.getId() );
    }

    /**
     * Removes the user's WikiSession from the internal session cache when the web
     * container destroys an HTTP session.
     * @param se the HTTP session event
     */
    @Override
    public void sessionDestroyed( final HttpSessionEvent se ) {
        final HttpSession session = se.getSession();
        for( final SessionMonitor monitor : monitors.values() ) {
            final Session storedSession = monitor.findSession( session );
            monitor.remove( session );
            LOG.debug( "Removed session {}.", session.getId() );
            if( storedSession != null ) {
                fireEvent( WikiSecurityEvent.SESSION_EXPIRED, storedSession.getLoginPrincipal(), storedSession );
            }
        }
    }

}
