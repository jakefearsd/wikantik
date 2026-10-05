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

import com.wikantik.api.core.Engine;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.Optional;

/**
 * Rules for a user's display names (full name and wiki name) when they are set or changed through
 * any write path: self-service profile, admin user editor, SCIM and SSO provisioning.
 *
 * <p>A <em>changed</em> display name must not be reserved (see {@link ReservedProfileNames}) and
 * must not equal another account's login name, full name or wiki name. A name that is unchanged is
 * never re-checked, so a name that became reserved or taken after its owner chose it cannot lock
 * the owner out of saving the rest of their profile.</p>
 */
public final class ProfileNameRules {

    private static final Logger LOG = LogManager.getLogger( ProfileNameRules.class );

    private ProfileNameRules() { }

    /**
     * Validates a change of display names for the account {@code login}.
     *
     * @return an error message for the first changed name that is reserved or taken, else empty
     */
    public static Optional< String > nameChangeError( final Engine engine, final UserDatabase db, final String login,
            final String oldFull, final String oldWiki, final String newFull, final String newWiki ) {
        for ( final String[] pair : new String[][] { { oldFull, newFull }, { oldWiki, newWiki } } ) {
            final String candidate = pair[ 1 ];
            if ( candidate == null || candidate.isBlank() || Objects.equals( trim( pair[ 0 ] ), trim( candidate ) ) ) {
                continue;
            }
            if ( ReservedProfileNames.isReserved( engine, candidate ) ) {
                return Optional.of( "The name '" + candidate.trim() + "' is reserved for a role or group; choose a different name" );
            }
            if ( usedByAnotherAccount( db, login, candidate.trim() ) ) {
                return Optional.of( "The name '" + candidate.trim() + "' is already used by another account; choose a different name" );
            }
        }
        return Optional.empty();
    }

    /**
     * Validates a self-service full-name change. The wiki name is derived from the full name with
     * whitespace removed, so it is checked too.
     */
    public static Optional< String > fullNameChangeError( final Engine engine, final UserDatabase db,
            final UserProfile current, final String newFull ) {
        final String derivedWiki = newFull == null ? null : newFull.replaceAll( "\\s", "" );
        return nameChangeError( engine, db, current.getLoginName(), current.getFullname(), current.getWikiName(),
                newFull, derivedWiki );
    }

    /** True if {@code name} is the login, full or wiki name of an account other than {@code login}. */
    public static boolean usedByAnotherAccount( final UserDatabase db, final String login, final String name ) {
        if ( db == null || name == null || name.isBlank() ) {
            return false;
        }
        return otherAccount( db, login, () -> db.findByLoginName( name ) )
                || otherAccount( db, login, () -> db.findByFullName( name ) )
                || otherAccount( db, login, () -> db.findByWikiName( name ) );
    }

    @FunctionalInterface
    private interface Lookup {
        UserProfile find() throws NoSuchPrincipalException;
    }

    private static boolean otherAccount( final UserDatabase db, final String login, final Lookup lookup ) {
        try {
            final UserProfile found = lookup.find();
            return found != null && !Objects.equals( found.getLoginName(), login );
        } catch ( final NoSuchPrincipalException e ) {
            // Expected: no account carries this name.
            LOG.debug( "No account matches the proposed name: {}", e.getMessage() );
            return false;
        }
    }

    private static String trim( final String s ) {
        return s == null ? null : s.trim();
    }
}
