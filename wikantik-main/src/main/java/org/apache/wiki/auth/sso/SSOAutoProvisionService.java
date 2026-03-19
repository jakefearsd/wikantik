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
package org.apache.wiki.auth.sso;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.auth.NoSuchPrincipalException;
import org.apache.wiki.auth.UserManager;
import org.apache.wiki.auth.WikiSecurityException;
import org.apache.wiki.auth.user.UserDatabase;
import org.apache.wiki.auth.user.UserProfile;

/**
 * Service that auto-provisions a JSPWiki {@link UserProfile} from an
 * SSO-authenticated user's IdP claims. When a user authenticates via SSO
 * for the first time and has no existing local profile, this service creates
 * one using the claim mappings configured in {@link SSOConfig}.
 *
 * @since 3.1
 */
public class SSOAutoProvisionService {

    private static final Logger LOG = LogManager.getLogger( SSOAutoProvisionService.class );

    private final Engine engine;
    private final SSOConfig ssoConfig;

    /**
     * Constructs a new SSOAutoProvisionService.
     *
     * @param engine    the wiki engine
     * @param ssoConfig the SSO configuration containing claim mappings
     */
    public SSOAutoProvisionService( final Engine engine, final SSOConfig ssoConfig ) {
        this.engine = engine;
        this.ssoConfig = ssoConfig;
    }

    /**
     * Provisions a local user profile from an SSO user profile if one does not
     * already exist. If auto-provisioning is disabled in the SSO configuration,
     * this method does nothing.
     *
     * @param loginName  the resolved login name for the user
     * @param ssoProfile the pac4j user profile from the IdP
     */
    public void provisionIfNeeded( final String loginName, final org.pac4j.core.profile.UserProfile ssoProfile ) {
        if( !ssoConfig.isAutoProvision() ) {
            return;
        }

        final UserDatabase userDb = engine.getManager( UserManager.class ).getUserDatabase();
        if( userDb == null ) {
            LOG.warn( "UserDatabase is not available; cannot auto-provision SSO user: {}", loginName );
            return;
        }

        // Check if a profile already exists
        try {
            userDb.findByLoginName( loginName );
            LOG.debug( "User profile already exists for SSO user: {}", loginName );
            return;
        } catch( final NoSuchPrincipalException e ) {
            // Profile does not exist; proceed with provisioning
        }

        // Create and populate a new profile
        try {
            final UserProfile profile = userDb.newProfile();
            profile.setLoginName( loginName );

            final String fullName = resolveAttribute( ssoProfile, ssoConfig.getClaimFullName() );
            if( fullName != null && !fullName.isBlank() ) {
                profile.setFullname( fullName );
            } else {
                // Fall back to login name as full name
                profile.setFullname( loginName );
            }

            final String email = resolveAttribute( ssoProfile, ssoConfig.getClaimEmail() );
            if( email != null && !email.isBlank() ) {
                profile.setEmail( email );
            }

            userDb.save( profile );
            LOG.info( "Auto-provisioned user profile for SSO user: {} (full name: {})", loginName, profile.getFullname() );
        } catch( final WikiSecurityException e ) {
            LOG.error( "Failed to auto-provision user profile for SSO user: {}", loginName, e );
        }
    }

    /**
     * Resolves an attribute value from the pac4j user profile.
     *
     * @param profile   the pac4j user profile
     * @param claimName the claim/attribute name to look up
     * @return the attribute value as a String, or {@code null} if not found
     */
    private String resolveAttribute( final org.pac4j.core.profile.UserProfile profile, final String claimName ) {
        final Object value = profile.getAttribute( claimName );
        return value != null ? value.toString() : null;
    }
}
