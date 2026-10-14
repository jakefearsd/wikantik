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
import com.wikantik.auth.ProfileNameRules.NameViolation;
import com.wikantik.auth.user.UserDatabase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.Optional;

/**
 * The display-name rules themselves. {@link ProfileNameRules} is the public face and delegates here; the split keeps both
 * classes under the PMD complexity gate.
 */
final class DisplayNameRules {

    private static final Logger LOG = LogManager.getLogger( DisplayNameRules.class );

    private DisplayNameRules() { }

    /** See {@link ProfileNameRules#displayNameViolation}. */
    static Optional< NameViolation > violation( final Engine engine, final UserDatabase db, final String login,
            final String candidate ) {
        final Optional< NameViolation > shape = shape( candidate );
        if ( shape.isPresent() ) {
            return shape;
        }
        final String name = candidate.trim();
        if ( ReservedProfileNames.isReserved( engine, name ) ) {
            return ProfileNameRules.invalid( "The name '" + name + "' is reserved for a role or group; choose a different name" );
        }
        if ( isLoginOfAnotherAccountIgnoreCase( db, login, name ) ) {
            return ProfileNameRules.taken( "The name '" + name + "' is another account's login name; choose a different name" );
        }
        if ( isInUse( db, login, name ) ) {
            return ProfileNameRules.taken( "The display name '" + name + "' is already in use; choose a different name" );
        }
        return Optional.empty();
    }

    /** See {@link ProfileNameRules#nameChangeViolation}. */
    static Optional< NameViolation > changeViolation( final Engine engine, final UserDatabase db, final String login,
            final String oldFull, final String oldWiki, final String newFull, final String newWiki ) {
        if ( newFull != null && !sameName( oldFull, newFull ) ) {
            final Optional< NameViolation > violation = violation( engine, db, login, newFull );
            if ( violation.isPresent() ) {
                return violation;
            }
        }
        if ( newWiki == null || newWiki.isBlank() || sameName( oldWiki, newWiki ) ) {
            return Optional.empty();
        }
        final String wiki = newWiki.trim();
        if ( ReservedProfileNames.isReserved( engine, wiki ) ) {
            return ProfileNameRules.invalid( "The name '" + wiki + "' is reserved for a role or group; choose a different name" );
        }
        if ( ProfileNameRules.isAnotherAccountsLogin( db, login, wiki ) ) {
            return ProfileNameRules.taken( "The name '" + wiki + "' is another account's login name; choose a different name" );
        }
        return Optional.empty();
    }

    /** True when both names are equal after trimming, treating null and blank as the same "no name". */
    private static boolean sameName( final String a, final String b ) {
        return Objects.equals( blankToNull( trim( a ) ), blankToNull( trim( b ) ) );
    }

    private static String blankToNull( final String s ) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String trim( final String s ) {
        return s == null ? null : s.trim();
    }

    private static Optional< NameViolation > shape( final String candidate ) {
        // The key is the source of truth for "blank": String.isBlank()/trim() ignore NBSP-class spaces that NFKC folds away.
        if ( candidate == null || ProfileNameRules.displayNameKey( candidate ).isEmpty() ) {
            return ProfileNameRules.invalid( "A display name is required" );
        }
        final String name = candidate.trim();
        // The minimum counts real characters (normalised, trimmed); the maximum bounds both the stored and normalised form.
        final int normalisedLength = ProfileNameRules.displayNameForm( candidate ).length();
        if ( normalisedLength < ProfileNameRules.DISPLAY_NAME_MIN
                || Math.max( name.length(), normalisedLength ) > ProfileNameRules.DISPLAY_NAME_MAX ) {
            return ProfileNameRules.invalid( "A display name must be " + ProfileNameRules.DISPLAY_NAME_MIN + " to "
                    + ProfileNameRules.DISPLAY_NAME_MAX + " characters long" );
        }
        if ( name.codePoints().anyMatch( Character::isISOControl ) ) {
            return ProfileNameRules.invalid( "A display name cannot contain control characters" );
        }
        if ( name.codePoints().anyMatch( DisplayNameRules::isInvisible ) ) {
            return ProfileNameRules.invalid( "A display name cannot contain invisible formatting characters" );
        }
        if ( name.indexOf( '@' ) >= 0 ) {
            return ProfileNameRules.invalid( "A display name cannot contain '@'; it would look like an email address" );
        }
        return Optional.empty();
    }

    /**
     * True for a code point that renders as nothing or as something other than itself: format characters (zero-width
     * space/joiner, bidirectional overrides, word joiner, byte-order mark), private-use and unassigned code points. Such
     * characters would let two visibly identical names differ, or reverse the text around them.
     */
    private static boolean isInvisible( final int codePoint ) {
        final int type = Character.getType( codePoint );
        return type == Character.FORMAT || type == Character.PRIVATE_USE || type == Character.UNASSIGNED;
    }

    /**
     * True if {@code name} (or its whitespace-stripped, wiki-name form) equals, ignoring case, the login name of an account
     * other than {@code login}. An exact lookup runs first (cheap; cached by the JDBC store), then the store's
     * case-insensitive probe.
     */
    private static boolean isLoginOfAnotherAccountIgnoreCase( final UserDatabase db, final String login, final String name ) {
        if ( db == null ) {
            return false;
        }
        final String stripped = ProfileNameRules.derivedWikiName( name );
        if ( ProfileNameRules.isAnotherAccountsLogin( db, login, name )
                || ProfileNameRules.isAnotherAccountsLogin( db, login, stripped ) ) {
            return true;
        }
        try {
            return db.isLoginNameTakenIgnoreCase( name, login ) || db.isLoginNameTakenIgnoreCase( stripped, login );
        } catch ( final WikiSecurityException e ) {
            LOG.warn( "Could not check display name '{}' against login names: {}", name, e.getMessage(), e );
            return false;
        }
    }

    /**
     * True if another account already holds {@code name} as its display name (case-insensitive, trimmed). A failing store
     * logs a WARN and answers {@code false}: the save that follows hits the same store and fails loudly.
     */
    private static boolean isInUse( final UserDatabase db, final String login, final String name ) {
        if ( db == null ) {
            return false;
        }
        try {
            return db.isFullNameTakenIgnoreCase( name, login );
        } catch ( final WikiSecurityException e ) {
            LOG.warn( "Could not check display name '{}' for uniqueness: {}", name, e.getMessage(), e );
            return false;
        }
    }
}
