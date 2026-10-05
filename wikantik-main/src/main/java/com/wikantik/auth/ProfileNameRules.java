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
 * must not equal another account's login name. Two accounts may share a full name: access is
 * decided by login name only, so a shared display name grants nothing. A name that is unchanged is
 * never re-checked, so a name that became reserved or taken after its owner chose it cannot lock
 * the owner out of saving the rest of their profile.</p>
 *
 * <p>Wiki names stay unique (the {@code users.wiki_name} column is UNIQUE); a write path that
 * derives one passes it through {@link #availableWikiName} so a shared full name still saves.</p>
 */
public final class ProfileNameRules {

    private static final Logger LOG = LogManager.getLogger( ProfileNameRules.class );

    private ProfileNameRules() { }

    /**
     * Validates a change of display names for the account {@code login}.
     *
     * @return an error message for the first changed name that is reserved or is another account's
     *         login name, else empty
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
            if ( isAnotherAccountsLogin( db, login, candidate.trim() ) ) {
                return Optional.of( "The name '" + candidate.trim() + "' is another account's login name; choose a different name" );
            }
        }
        return Optional.empty();
    }

    /** Upper bound on numeric suffixes tried for a wiki name before giving up and keeping the preferred one. */
    private static final int MAX_WIKI_NAME_SUFFIX = 1000;

    /**
     * A wiki name for the account {@code login} that no other account holds as its wiki or login
     * name and that is not reserved. An unchanged wiki name ({@code preferred} equals
     * {@code current}) is never re-checked. Returns {@code preferred} when it is free; otherwise keeps
     * {@code current} when it is a numbered form of {@code preferred} ("JohnSmith2"), so an
     * identity provider that re-sends the same name on every sync does not change it; otherwise
     * appends the lowest free number from 2.
     *
     * @param preferred the wiki name the write would set (derived from the full name, or given)
     * @param current   the account's stored wiki name, or {@code null} for a new account
     * @return a free wiki name, or {@code preferred} unchanged when it is {@code null} or blank
     */
    public static String availableWikiName( final Engine engine, final UserDatabase db, final String login,
            final String preferred, final String current ) {
        if ( db == null || preferred == null || preferred.isBlank() || preferred.equals( current )
                || wikiNameFree( engine, db, login, preferred ) ) {
            return preferred;
        }
        if ( current != null && current.length() > preferred.length() && current.startsWith( preferred )
                && current.substring( preferred.length() ).chars().allMatch( Character::isDigit )
                && wikiNameFree( engine, db, login, current ) ) {
            return current;
        }
        for ( int suffix = 2; suffix <= MAX_WIKI_NAME_SUFFIX; suffix++ ) {
            final String candidate = preferred + suffix;
            if ( wikiNameFree( engine, db, login, candidate ) ) {
                return candidate;
            }
        }
        LOG.warn( "No free wiki name found for account '{}' after {} numbered forms of '{}'; keeping it as given",
                login, MAX_WIKI_NAME_SUFFIX, preferred );
        return preferred;
    }

    /**
     * Sets {@code fullName} on {@code profile} and gives it the wiki name derived from it, numbered
     * when another account already holds that wiki name (see {@link #availableWikiName}). Call it
     * after {@link #nameChangeError} has accepted the name.
     */
    public static void applyFullName( final Engine engine, final UserDatabase db, final UserProfile profile,
            final String fullName ) {
        final String previousWiki = profile.getWikiName();
        profile.setFullname( fullName );
        final String derived = derivedWikiName( fullName );
        final String wiki = availableWikiName( engine, db, profile.getLoginName(), derived, previousWiki );
        if ( !Objects.equals( wiki, derived ) ) {
            profile.setWikiName( wiki );
        }
    }

    /** The wiki name a profile derives from {@code fullName}: the full name with whitespace removed. */
    public static String derivedWikiName( final String fullName ) {
        return fullName == null ? null : fullName.replaceAll( "\\s", "" );
    }

    private static boolean wikiNameFree( final Engine engine, final UserDatabase db, final String login, final String name ) {
        return !ReservedProfileNames.isReserved( engine, name )
                && !otherAccount( db, login, () -> db.findByWikiName( name ) )
                && !isAnotherAccountsLogin( db, login, name );
    }

    /** True if {@code name} is the login name of an account other than {@code login}. */
    public static boolean isAnotherAccountsLogin( final UserDatabase db, final String login, final String name ) {
        if ( db == null || name == null || name.isBlank() ) {
            return false;
        }
        return otherAccount( db, login, () -> db.findByLoginName( name ) );
    }

    /**
     * Validates a full-name change through the self-service or admin profile editor. The wiki name
     * is derived from the full name with whitespace removed, so it is checked too.
     */
    public static Optional< String > fullNameChangeError( final Engine engine, final UserDatabase db,
            final UserProfile current, final String newFull ) {
        return nameChangeError( engine, db, current.getLoginName(), current.getFullname(), current.getWikiName(),
                newFull, derivedWikiName( newFull ) );
    }

    /**
     * True if {@code name} is the login, full or wiki name of an account other than {@code login}.
     * Used for group names, which must not be confused with any account; profile names use the
     * narrower {@link #nameChangeError}.
     */
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
