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
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.login.AbstractLoginModule;
import com.wikantik.auth.login.HttpRequestCallback;
import com.wikantik.auth.login.WikiEngineCallback;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.auth.user.UserDatabase;
import org.pac4j.core.profile.UserProfile;
import org.pac4j.core.util.Pac4jConstants;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Collection;
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
    public static final String OPTION_CLAIM_LOGIN_NAME = "sso.claimLoginName";

    /**
     * LoginModule option key for the immutable identity claim used to verify
     * account ownership. Defaults to {@code "sub"}; must be a claim that is
     * stable across sessions (never a mutable username/email).
     */
    public static final String OPTION_IDENTITY_CLAIM = "sso.identityClaim";

    /**
     * LoginModule option key for the IdP claim mapped to JSPWiki full name.
     */
    public static final String OPTION_CLAIM_FULL_NAME = "sso.claimFullName";

    /**
     * LoginModule option key for the IdP claim mapped to JSPWiki email.
     */
    public static final String OPTION_CLAIM_EMAIL = "sso.claimEmail";

    /** Default claim name for login name. */
    private static final String DEFAULT_CLAIM_LOGIN = "preferred_username";

    /** Default claim name for full name. */

    /** Default claim name for email. */

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
        final Callback[] callbacks = { rcb, ecb };

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
            if( !isSafeLoginName( loginName ) ) {
                throw new FailedLoginException( "SSO profile login name is missing or unsafe." );
            }

            // Identity is keyed on the immutable subject claim, not the (mutable)
            // login name. A login may only bind to a name that is unused or already
            // SSO-linked to this same subject — never silently adopt a local account.
            final Engine engine = ecb.getEngine();
            final String subject = resolveSubject( profile );
            if( !isLinkAllowed( engine, loginName, subject ) ) {
                throw new FailedLoginException(
                    "SSO identity '" + loginName + "' collides with an existing non-SSO account." );
            }

            LOG.debug( "SSO login succeeded for user: {}", loginName );
            principals.add( new WikiPrincipal( loginName, WikiPrincipal.LOGIN_NAME ) );

            // Auto-provision a local user profile if needed
            if( engine != null ) {
                final SSOConfig ssoConfig = SSOConfigHolder.getConfig( engine );
                if( ssoConfig != null ) {
                    new SSOAutoProvisionService( engine, ssoConfig )
                        .provisionIfNeeded( loginName, subject, profile );
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

    /** Resolves the immutable subject claim used to verify account ownership. */
    private String resolveSubject( final UserProfile profile ) {
        final String subject = firstScalar( profile.getAttribute( getOption( OPTION_IDENTITY_CLAIM, "sub" ) ) );
        // No subject claim present: fall back to the login name. The collision
        // guard still holds because an SSO-linked account stores its real subject
        // in ATTR_SSO_SUBJECT, which a subject-less assertion cannot match.
        return subject != null ? subject : resolveLoginName( profile );
    }

    /**
     * A login may bind to {@code loginName} only when no local profile exists
     * yet, or the existing profile is SSO-linked to the same {@code subject}.
     * This stops a hostile IdP from asserting a name that matches a local
     * account and inheriting its identity.
     */
    private boolean isLinkAllowed( final Engine engine, final String loginName, final String subject ) {
        if( engine == null ) {
            return true; // unit contexts with no engine: nothing to collide with
        }
        final UserDatabase userDb = AuthSubsystemBridge.fromLegacyEngine( engine ).users().getUserDatabase();
        if( userDb == null ) {
            LOG.warn( "SSO collision check skipped: UserDatabase unavailable for login '{}'.", loginName );
            return true;
        }
        try {
            final com.wikantik.auth.user.UserProfile existing = userDb.findByLoginName( loginName );
            final Object linked = existing.getAttributes().get( SSOAutoProvisionService.ATTR_SSO_SUBJECT );
            return subject != null && subject.equals( linked );
        } catch( final NoSuchPrincipalException e ) {
            return true; // no collision — fresh SSO identity
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

        final String claimValue = firstScalar( profile.getAttribute( claimName ) );
        if( claimValue != null ) {
            // Intentionally return even if blank — isSafeLoginName will reject it, skipping the username/id fallback.
            return claimValue;
        }

        final String username = profile.getUsername();
        if( username != null && !username.isBlank() ) {
            return username;
        }

        return profile.getId();
    }

    /** Maximum accepted login-name length; matches the user store's column width. */
    private static final int MAX_LOGIN_NAME_LENGTH = 100;

    /**
     * A resolved login name is safe only if it is non-blank, within the store's
     * length bound, and free of whitespace and ISO control characters. Rejecting
     * here stops a hostile IdP claim from minting a malformed {@link WikiPrincipal}.
     */
    static boolean isSafeLoginName( final String loginName ) {
        if( loginName == null || loginName.isBlank() || loginName.length() > MAX_LOGIN_NAME_LENGTH ) {
            return false;
        }
        return loginName.chars().noneMatch( c -> Character.isWhitespace( c ) || Character.isISOControl( c ) );
    }

    /**
     * Normalises a pac4j attribute to a single scalar string. SAML (and some
     * OIDC) attributes arrive as a {@link Collection}; taking {@code toString()}
     * of the collection yields "[value]" and corrupts the identity. Returns the
     * first element's string form, or the value's string form when scalar.
     */
    static String firstScalar( final Object value ) {
        if( value == null ) {
            return null;
        }
        if( value instanceof Collection< ? > c ) {
            return c.isEmpty() ? null : String.valueOf( c.iterator().next() );
        }
        return value.toString();
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
