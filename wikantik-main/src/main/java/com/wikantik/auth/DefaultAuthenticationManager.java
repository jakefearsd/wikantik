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
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.authorize.WebAuthorizer;
import com.wikantik.auth.authorize.WebContainerAuthorizer;
import com.wikantik.auth.login.AnonymousLoginModule;
import com.wikantik.auth.login.CookieAssertionLoginModule;
import com.wikantik.auth.login.CookieAuthenticationLoginModule;
import com.wikantik.auth.login.UserDatabaseLoginModule;
import com.wikantik.auth.login.WebContainerCallbackHandler;
import com.wikantik.auth.login.WebContainerLoginModule;
import com.wikantik.auth.login.WikiCallbackHandler;
import com.wikantik.auth.sso.SSOConfig;
import com.wikantik.auth.sso.SSOConfigHolder;
import com.wikantik.auth.sso.SSOLoginModule;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiSecurityEvent;
import com.wikantik.util.ClassUtil;
import com.wikantik.util.TextUtil;
import com.wikantik.util.TimedCounterList;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;


/**
 * Default implementation for {@link AuthenticationManager}
 *
 * {@inheritDoc}
 * 
 * @since 2.3
 */
public class DefaultAuthenticationManager implements AuthenticationManager {

    /** How many milliseconds the logins are stored before they're cleaned away. */
    private static final long LASTLOGINS_CLEANUP_TIME = 10 * 60 * 1_000L; // Ten minutes

    private static final long MAX_LOGIN_DELAY = 20 * 1_000L; // 20 seconds

    private static final Logger LOG = LogManager.getLogger( DefaultAuthenticationManager.class );

    /** Empty Map passed to JAAS {@link #doJAASLogin(Class, CallbackHandler, Map)} method. */
    protected static final Map< String, String > EMPTY_MAP = Map.of();

    /** Class (of type LoginModule) to use for custom authentication. */
    protected Class< ? extends LoginModule > loginModuleClass = UserDatabaseLoginModule.class;

    /** Options passed to {@link LoginModule#initialize(Subject, CallbackHandler, Map, Map)};
     * initialized by {@link #initialize(Engine, Properties)}. */
    protected final Map< String, String > loginModuleOptions = new HashMap<>();

    /** The default {@link LoginModule} class name to use for custom authentication. */
    private static final String DEFAULT_LOGIN_MODULE = "com.wikantik.auth.login.UserDatabaseLoginModule";

    /** Empty principal set. */
    private static final Set<Principal> NO_PRINCIPALS = Set.of();

    /** Static Boolean for lazily-initializing the "allows assertions" flag */
    private boolean allowsCookieAssertions = true;

    private boolean throttleLogins = true;

    /** Static Boolean for lazily-initializing the "allows cookie authentication" flag */
    private boolean allowsCookieAuthentication;

    private Engine engine;

    /** If true, logs the IP address of the editor */
    private boolean storeIPAddress = true;

    /** Keeps a list of the usernames who have attempted a login recently. */
    private final TimedCounterList< String > lastLoginAttempts = new TimedCounterList<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize( final Engine engine, final Properties props ) throws WikiException {
        this.engine = engine;
        storeIPAddress = TextUtil.getBooleanProperty( props, PROP_STOREIPADDRESS, storeIPAddress );

        // Should we allow cookies for assertions? (default: yes)
        allowsCookieAssertions = TextUtil.getBooleanProperty( props, PROP_ALLOW_COOKIE_ASSERTIONS,true );

        // Should we allow cookies for authentication? (default: no)
        allowsCookieAuthentication = TextUtil.getBooleanProperty( props, PROP_ALLOW_COOKIE_AUTH, false );

        // Should we throttle logins? (default: yes)
        throttleLogins = TextUtil.getBooleanProperty( props, PROP_LOGIN_THROTTLING, true );

        // Look up the LoginModule class
        final String loginModuleClassName = TextUtil.getStringProperty( props, PROP_LOGIN_MODULE, DEFAULT_LOGIN_MODULE );
        try {
            loginModuleClass = ClassUtil.findClass( "", loginModuleClassName );
        } catch( final ClassNotFoundException e ) {
            LOG.error( e.getMessage(), e );
            throw new WikiException( "Could not instantiate LoginModule class.", e );
        }

        // Initialize the LoginModule options
        initLoginModuleOptions( props );

        // Initialize SSO configuration if enabled
        initSSOConfig( engine, props );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isContainerAuthenticated() {
        try {
            final Authorizer authorizer = engine.getManager( AuthorizationManager.class ).getAuthorizer();
            if ( authorizer instanceof WebContainerAuthorizer wca ) {
                 return wca.isContainerAuthorized();
            }
        } catch ( final WikiException e ) {
            // It's probably ok to fail silently...
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean login( final HttpServletRequest request ) throws WikiSecurityException {
        final HttpSession httpSession = request.getSession();
        final Session session = SessionMonitor.getInstance( engine ).find( httpSession );
        final AuthenticationManager authenticationMgr = engine.getManager( AuthenticationManager.class );
        final AuthorizationManager authorizationMgr = engine.getManager( AuthorizationManager.class );
        CallbackHandler handler = null;
        final Map< String, String > options = EMPTY_MAP;

        // If user not authenticated, check if SSO, container, or cookie authenticated them
        if ( !session.isAuthenticated() ) {
            // Create a callback handler
            handler = new WebContainerCallbackHandler( engine, request );

            // Try SSO login first if SSO is enabled
            final SSOConfig ssoConfig = SSOConfigHolder.getConfig( engine );
            Set< Principal > principals = NO_PRINCIPALS;
            if( ssoConfig != null && ssoConfig.isEnabled() ) {
                principals = authenticationMgr.doJAASLogin( SSOLoginModule.class, handler, loginModuleOptions );
            }

            // Execute the container login module, then (if that fails) the cookie auth module
            if( principals.isEmpty() ) {
                principals = authenticationMgr.doJAASLogin( WebContainerLoginModule.class, handler, options );
            }
            if (principals.isEmpty() && authenticationMgr.allowsCookieAuthentication() ) {
                principals = authenticationMgr.doJAASLogin( CookieAuthenticationLoginModule.class, handler, options );
            }

            // If the container logged the user in successfully, tell the Session (and add all the Principals)
            if (!principals.isEmpty()) {
                fireEvent( WikiSecurityEvent.LOGIN_AUTHENTICATED, getLoginPrincipal( principals ), session );
                for( final Principal principal : principals ) {
                    fireEvent( WikiSecurityEvent.PRINCIPAL_ADD, principal, session );
                }

                // Add all appropriate Authorizer roles
                injectAuthorizerRoles( session, authorizationMgr.getAuthorizer(), request );
            }
        }

        // If user still not authenticated, check if assertion cookie was supplied
        if ( !session.isAuthenticated() && authenticationMgr.allowsCookieAssertions() ) {
            // Execute the cookie assertion login module
            final Set< Principal > principals = authenticationMgr.doJAASLogin( CookieAssertionLoginModule.class, handler, options );
            if (!principals.isEmpty()) {
                fireEvent( WikiSecurityEvent.LOGIN_ASSERTED, getLoginPrincipal( principals ), session);
            }
        }

        // If user still anonymous, use the remote address
        if( session.isAnonymous() ) {
            final Set< Principal > principals = authenticationMgr.doJAASLogin( AnonymousLoginModule.class, handler, options );
            if(!principals.isEmpty()) {
                fireEvent( WikiSecurityEvent.LOGIN_ANONYMOUS, getLoginPrincipal( principals ), session );
                return true;
            }
        }

        // If by some unusual turn of events the Anonymous login module doesn't work, login failed!
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean login( final Session session, final HttpServletRequest request, final String username, final String password ) throws WikiSecurityException {
        if ( session == null ) {
            LOG.error( "No wiki session provided, cannot log in." );
            return false;
        }

        // Protect against brute-force password guessing if configured to do so
        if ( throttleLogins ) {
            delayLogin( username );
        }

        final CallbackHandler handler = new WikiCallbackHandler( engine, null, username, password );

        // Execute the user's specified login module
        final Set< Principal > principals = doJAASLogin( loginModuleClass, handler, loginModuleOptions );
        if(!principals.isEmpty()) {
            fireEvent(WikiSecurityEvent.LOGIN_AUTHENTICATED, getLoginPrincipal( principals ), session );
            for ( final Principal principal : principals ) {
                fireEvent( WikiSecurityEvent.PRINCIPAL_ADD, principal, session );
            }

            // Add all appropriate Authorizer roles
            injectAuthorizerRoles( session, engine.getManager( AuthorizationManager.class ).getAuthorizer(), null );

            return true;
        }
        return false;
    }

    /**
     *  This method builds a database of login names that are being attempted, and will try to delay if there are too many requests coming
     *  in for the same username.
     *  <p>
     *  The current algorithm uses 2^loginattempts as the delay in milliseconds, i.e. at 10 login attempts it'll add 1.024 seconds to the login.
     *
     *  @param username The username that is being logged in
     */
    private void delayLogin( final String username ) {
        try {
            lastLoginAttempts.cleanup( LASTLOGINS_CLEANUP_TIME );
            final int count = lastLoginAttempts.count( username );

            final long delay = Math.min( 1L << count, MAX_LOGIN_DELAY );
            LOG.debug( "Sleeping for {} ms to allow login.", delay );
            Thread.sleep( delay );

            lastLoginAttempts.add( username );
        } catch( final InterruptedException e ) {
            // FALLTHROUGH is fine
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void logout( final HttpServletRequest request ) {
        if( request == null ) {
            LOG.error( "No HTTP reqest provided; cannot log out." );
            return;
        }

        final HttpSession session = request.getSession();
        final String sid = ( session == null ) ? "(null)" : session.getId();
        LOG.debug( "Invalidating Session for session ID= {}", sid );
        // Retrieve the associated Session and clear the Principal set
        final Session wikiSession = Wiki.session().find( engine, request );
        final Principal originalPrincipal = wikiSession.getLoginPrincipal();
        wikiSession.invalidate();

        // Remove the wikiSession from the WikiSession cache
        Wiki.session().remove( engine, request );

        // We need to flush the HTTP session too
        if( session != null ) {
            session.invalidate();
        }

        // Log the event
        fireEvent( WikiSecurityEvent.LOGOUT, originalPrincipal, null );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean allowsCookieAssertions() {
        return allowsCookieAssertions;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean allowsCookieAuthentication() {
        return allowsCookieAuthentication;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set< Principal > doJAASLogin( final Class< ? extends LoginModule > clazz,
                                         final CallbackHandler handler,
                                         final Map< String, String > options ) throws WikiSecurityException {
        // Instantiate the login module
        final LoginModule loginModule;
        try {
            loginModule = ClassUtil.buildInstance( clazz );
        } catch( final ReflectiveOperationException e ) {
            throw new WikiSecurityException( e.getMessage(), e );
        }

        // Initialize the LoginModule
        final Subject subject = new Subject();
        loginModule.initialize( subject, handler, EMPTY_MAP, options );

        // Try to log in:
        boolean loginSucceeded = false;
        boolean commitSucceeded = false;
        try {
            loginSucceeded = loginModule.login();
            if( loginSucceeded ) {
                commitSucceeded = loginModule.commit();
            }
        } catch( final LoginException e ) {
            // Login or commit failed! No principal for you!
        }

        // If we successfully logged in & committed, return all the principals
        if( loginSucceeded && commitSucceeded ) {
            return subject.getPrincipals();
        }
        return NO_PRINCIPALS;
    }

    // events processing .......................................................

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void addWikiEventListener( final WikiEventListener listener ) {
        WikiEventManager.addWikiEventListener( this, listener );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void removeWikiEventListener( final WikiEventListener listener ) {
        WikiEventManager.removeWikiEventListener( this, listener );
    }

    /**
     * Initializes the options Map supplied to the configured LoginModule every time it is invoked. The properties and values extracted from
     * <code>wikantik.properties</code> are of the form <code>wikantik.loginModule.options.<var>param</var> = <var>value</var>, where
     * <var>param</var> is the key name, and <var>value</var> is the value.
     *
     * @param props the properties used to initialize JSPWiki
     * @throws IllegalArgumentException if any of the keys are duplicated
     */
    private void initLoginModuleOptions( final Properties props ) {
        for( final Object key : props.keySet() ) {
            final String propName = key.toString();
            if( propName.startsWith( PREFIX_LOGIN_MODULE_OPTIONS ) ) {
                // Extract the option name and value
                final String optionKey = propName.substring( PREFIX_LOGIN_MODULE_OPTIONS.length() ).trim();
                if( !optionKey.isEmpty() ) {
                    final String optionValue = props.getProperty( propName );

                    // Make sure the key is unique before stashing the key/value pair
                    if ( loginModuleOptions.containsKey( optionKey ) ) {
                        throw new IllegalArgumentException( "JAAS LoginModule key " + propName + " cannot be specified twice!" );
                    }
                    loginModuleOptions.put( optionKey, optionValue );
                }
            }
        }
    }

    /**
     * Initializes the SSO configuration from properties, if SSO is enabled.
     *
     * @param engine the wiki engine
     * @param props the properties used to initialize JSPWiki
     */
    private void initSSOConfig( final Engine engine, final Properties props ) {
        final boolean ssoEnabled = TextUtil.getBooleanProperty( props, SSOConfig.PROP_SSO_ENABLED, false );
        if( ssoEnabled ) {
            // Read wikantik.baseURL from properties (full URL needed for SSO callbacks).
            // engine.getBaseURL() only returns the context path, not the full URL.
            String baseUrl = TextUtil.getStringProperty( props, "wikantik.baseURL", "" );
            if( baseUrl.isEmpty() ) {
                baseUrl = engine.getBaseURL();
                LOG.warn( "wikantik.baseURL not set. SSO callback URLs will be relative, which may cause OIDC redirect_uri mismatches. "
                        + "Set wikantik.baseURL in wikantik-custom.properties for proper SSO operation." );
            }
            // Remove trailing slash to avoid double slashes in callback URL
            if( baseUrl.endsWith( "/" ) ) {
                baseUrl = baseUrl.substring( 0, baseUrl.length() - 1 );
            }
            final String callbackUrl = baseUrl + SSOConfig.CALLBACK_PATH;
            final SSOConfig ssoConfig = new SSOConfig( props, callbackUrl );
            SSOConfigHolder.setConfig( engine, ssoConfig );
            LOG.info( "SSO configuration initialized with callback URL: {}", callbackUrl );
        }
    }

    /**
     * After successful login, this method is called to inject authorized role Principals into the Session. To determine which roles
     * should be injected, the configured Authorizer is queried for the roles it knows about by calling  {@link Authorizer#getRoles()}.
     * Then, each role returned by the authorizer is tested by calling {@link Authorizer#isUserInRole(Session, Principal)}. If this
     * check fails, and the Authorizer is of type WebAuthorizer, the role is checked again by calling
     * {@link WebAuthorizer#isUserInRole(HttpServletRequest, Principal)}). Any roles that pass the test are injected into the Subject by
     * firing appropriate authentication events.
     *
     * @param session the user's current Session
     * @param authorizer the Engine's configured Authorizer
     * @param request the user's HTTP session, which may be <code>null</code>
     */
    private void injectAuthorizerRoles( final Session session, final Authorizer authorizer, final HttpServletRequest request ) {
        // Test each role the authorizer knows about
        for( final Principal role : authorizer.getRoles() ) {
            // Test the Authorizer
            if( authorizer.isUserInRole( session, role ) ) {
                fireEvent( WikiSecurityEvent.PRINCIPAL_ADD, role, session );
                LOG.debug( "Added authorizer role {}.", role.getName() );
            // If web authorizer, test the request.isInRole() method also
            } else if ( request != null && authorizer instanceof WebAuthorizer wa ) {
                if ( wa.isUserInRole( request, role ) ) {
                    fireEvent( WikiSecurityEvent.PRINCIPAL_ADD, role, session );
                    LOG.debug( "Added container role {}.",role.getName() );
                }
            }
        }
    }

}
