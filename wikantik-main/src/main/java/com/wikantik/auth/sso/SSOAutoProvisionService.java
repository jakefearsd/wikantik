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
import com.wikantik.auth.ProfileChangeNotifier;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.ProfileNameRules;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;

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

    /** Profile attribute recording the IdP subject that owns this account. */
    public static final String ATTR_SSO_SUBJECT = "sso.subject";

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
     * already exist, stamping the IdP subject on the profile for later
     * verified-link collision checks. If auto-provisioning is disabled in the
     * SSO configuration, this method does nothing.
     *
     * @param loginName  the resolved login name for the user
     * @param subject    the IdP subject identifier (stored as {@link #ATTR_SSO_SUBJECT})
     * @param ssoProfile the pac4j user profile from the IdP
     */
    public void provisionIfNeeded( final String loginName, final String subject, final org.pac4j.core.profile.UserProfile ssoProfile ) {
        if( !ssoConfig.isAutoProvision() ) {
            return;
        }

        final UserDatabase userDb = AuthSubsystemBridge.fromLegacyEngine( engine ).users().getUserDatabase();
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
            LOG.debug( "No user profile for SSO login '{}'; provisioning one: {}", loginName, e.getMessage() );
            // Profile does not exist; proceed with provisioning
        }

        // Create and populate a new profile
        try {
            final UserProfile profile = userDb.newProfile();
            profile.setLoginName( loginName );

            final String claimed = resolveAttribute( ssoProfile, ssoConfig.getClaimFullName() );
            final String displayName = ProfileNameRules.availableDisplayName( engine, userDb, loginName, claimed );
            profile.setFullname( displayName );
            LOG.info( "SSO account {}: display name '{}' ({})", loginName, displayName, describeChoice( claimed, displayName ) );

            final String email = resolveAttribute( ssoProfile, ssoConfig.getClaimEmail() );
            if( email != null && !email.isBlank() ) {
                profile.setEmail( email );
            }

            if( subject != null && !subject.isBlank() ) {
                profile.getAttributes().put( ATTR_SSO_SUBJECT, subject );
            }

            // The wiki name is derived from the full name with whitespace stripped, so
            // two users sharing a display name — or a clash with a pre-existing account —
            // would violate the unique wiki_name constraint and abort provisioning. Pick
            // a collision-free wiki name before saving.
            profile.setWikiName( ProfileNameRules.availableWikiName( engine, userDb, loginName, profile.getWikiName(), null ) );

            userDb.save( profile );
            ProfileChangeNotifier.profileSaved( engine, loginName );
            LOG.info( "Auto-provisioned user profile for SSO user: {} (full name: {})", loginName, profile.getFullname() );
        } catch( final WikiSecurityException e ) {
            // A concurrent first-login may have created the row between our
            // existence check and save (e.g. the unique-constraint loser on a
            // DB-backed store). Re-check: if the profile now exists, the race
            // resolved correctly and this is not an error. Only a genuinely
            // failed provisioning is logged at ERROR.
            try {
                userDb.findByLoginName( loginName );
                LOG.debug( "SSO user {} was provisioned concurrently; treating as success.", loginName );
            } catch( final NoSuchPrincipalException stillMissing ) {
                LOG.error( "Failed to auto-provision user profile for SSO user: {}", loginName, e );
            }
        }
    }

    /**
     * Why {@code chosen} was picked, for the provisioning INFO line (names only — never a secret). Package-private for tests.
     *
     * @param claimed the raw IdP name claim, possibly {@code null}
     * @param chosen  the display name {@link ProfileNameRules#availableDisplayName} returned
     */
    static String describeChoice( final String claimed, final String chosen ) {
        final String base = claimed == null ? "" : claimed.trim();
        if ( base.isEmpty() ) {
            return "no name claim from the identity provider; placeholder";
        }
        if ( chosen.equals( base ) ) {
            return "name claim used as-is";
        }
        if ( chosen.startsWith( "Member " ) ) {
            return "name claim unusable (reserved, email-shaped or malformed); placeholder";
        }
        return "name claim was taken; numbered";
    }

    /**
     * Resolves an attribute value from the pac4j user profile.
     *
     * @param profile   the pac4j user profile
     * @param claimName the claim/attribute name to look up
     * @return the attribute value as a String, or {@code null} if not found
     */
    private String resolveAttribute( final org.pac4j.core.profile.UserProfile profile, final String claimName ) {
        return SSOLoginModule.firstScalar( profile.getAttribute( claimName ) );
    }
}
