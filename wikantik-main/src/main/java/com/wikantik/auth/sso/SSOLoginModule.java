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
package com.wikantik.auth.sso;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Engine;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.login.AbstractLoginModule;
import com.wikantik.auth.login.HttpRequestCallback;
import com.wikantik.auth.login.WikiEngineCallback;
import org.pac4j.core.profile.UserProfile;
import org.pac4j.core.util.Pac4jConstants;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p>
 * JAAS LoginModule that authenticates a user by reading a pac4j
 * {@link UserProfile} from the HTTP session. This module is used after pac4j's
 * callback filter has processed a successful SSO authentication (OIDC or SAML)
 * and stored the resulting profile in the session.
 * </p>
 * <p>
 * This module must be used with a CallbackHandler that supports the
 * {@link HttpRequestCallback} type (e.g., the existing
 * {@link com.wikantik.auth.login.WebContainerCallbackHandler}).
 * </p>
 * <p>
 * After authentication, a {@link WikiPrincipal} based on the user's SSO login name
 * will be created and associated with the Subject.
 * </p>
 *
 * @since 3.1
 */
public class SSOLoginModule extends AbstractLoginModule {

    private static final Logger LOG = LogManager.getLogger( SSOLoginModule.class );

    /**
     * LoginModule option key for the IdP claim mapped to JSPWiki login name.
     * Set via {@code wikantik.loginModule.options.sso.claimLoginName}.
     */
    static final String OPTION_CLAIM_LOGIN_NAME = "sso.claimLoginName";

    /**
     * LoginModule option key for the IdP claim mapped to JSPWiki full name.
     */
    static final String OPTION_CLAIM_FULL_NAME = "sso.claimFullName";

    /**
     * LoginModule option key for the IdP claim mapped to JSPWiki email.
     */
    static final String OPTION_CLAIM_EMAIL = "sso.claimEmail";

    /** Default claim name for login name. */
    private static final String DEFAULT_CLAIM_LOGIN = "preferred_username";

    /** Default claim name for full name. */
    private static final String DEFAULT_CLAIM_FULL_NAME = "name";

    /** Default claim name for email. */
    private static final String DEFAULT_CLAIM_EMAIL = "email";

    /**
     * Attempts to log in by reading a pac4j UserProfile from the HTTP session.
     *
     * @return {@code true} if a valid pac4j UserProfile was found in the session
     * @throws LoginException if the login cannot be completed
     */
    @Override
    public boolean login() throws LoginException {
        final HttpRequestCallback rcb = new HttpRequestCallback();
        final WikiEngineCallback ecb = new WikiEngineCallback();
        final Callback[] callbacks = new Callback[] { rcb, ecb };

        try {
            handler.handle( callbacks );
            final HttpServletRequest request = rcb.getRequest();
            if( request == null ) {
                throw new FailedLoginException( "No HTTP request available." );
            }

            final HttpSession session = request.getSession( false );
            if( session == null ) {
                throw new FailedLoginException( "No HTTP session available." );
            }

            // Look for pac4j user profiles in the session
            final UserProfile profile = extractProfile( session );
            if( profile == null ) {
                throw new FailedLoginException( "No SSO user profile found in session." );
            }

            // Extract the login name from the profile using configured claim mappings
            final String loginName = resolveLoginName( profile );
            if( loginName == null || loginName.isBlank() ) {
                throw new FailedLoginException( "SSO profile has no login name." );
            }

            LOG.debug( "SSO login succeeded for user: {}", loginName );
            principals.add( new WikiPrincipal( loginName, WikiPrincipal.LOGIN_NAME ) );

            // Auto-provision a local user profile if needed
            final Engine engine = ecb.getEngine();
            if( engine != null ) {
                final SSOConfig ssoConfig = SSOConfigHolder.getConfig( engine );
                if( ssoConfig != null ) {
                    new SSOAutoProvisionService( engine, ssoConfig ).provisionIfNeeded( loginName, profile );
                }
            }

            return true;

        } catch( final IOException e ) {
            LOG.error( "IOException during SSO login: {}", e.getMessage() );
            return false;
        } catch( final UnsupportedCallbackException e ) {
            LOG.error( "UnsupportedCallbackException during SSO login: {}", e.getMessage() );
            return false;
        }
    }

    /**
     * Extracts the first pac4j UserProfile from the HTTP session.
     *
     * @param session the HTTP session
     * @return the user profile, or {@code null} if none found
     */
    @SuppressWarnings( "unchecked" )
    UserProfile extractProfile( final HttpSession session ) {
        // pac4j stores profiles as a LinkedHashMap<String, UserProfile> under Pac4jConstants.USER_PROFILES
        final Object profilesObj = session.getAttribute( Pac4jConstants.USER_PROFILES );
        if( profilesObj instanceof LinkedHashMap ) {
            final Map< String, UserProfile > profiles = ( LinkedHashMap< String, UserProfile > ) profilesObj;
            if( !profiles.isEmpty() ) {
                return profiles.values().iterator().next();
            }
        } else if( profilesObj instanceof Map ) {
            final Map< String, UserProfile > profiles = ( Map< String, UserProfile > ) profilesObj;
            if( !profiles.isEmpty() ) {
                return profiles.values().iterator().next();
            }
        }
        return null;
    }

    /**
     * Resolves the JSPWiki login name from the pac4j UserProfile using
     * configured claim mappings.
     *
     * @param profile the pac4j user profile
     * @return the login name, or {@code null} if not resolvable
     */
    String resolveLoginName( final UserProfile profile ) {
        final String claimName = getOption( OPTION_CLAIM_LOGIN_NAME, DEFAULT_CLAIM_LOGIN );

        // Try the configured claim first
        final Object claimValue = profile.getAttribute( claimName );
        if( claimValue != null && !claimValue.toString().isBlank() ) {
            return claimValue.toString();
        }

        // Fall back to the profile's username
        final String username = profile.getUsername();
        if( username != null && !username.isBlank() ) {
            return username;
        }

        // Fall back to the profile's ID
        return profile.getId();
    }

    /**
     * Reads an option from the LoginModule options map.
     */
    private String getOption( final String key, final String defaultValue ) {
        if( options != null && options.containsKey( key ) ) {
            return options.get( key ).toString();
        }
        return defaultValue;
    }
}
