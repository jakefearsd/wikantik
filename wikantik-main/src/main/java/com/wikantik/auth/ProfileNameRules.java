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

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Rules for a user's display name (stored as {@code users.full_name}) and wiki name when they are set or changed through
 * any write path: self-service profile, admin user editor, SCIM, SSO provisioning and the legacy {@code UserManager} path.
 *
 * <p>A display name must be present, {@value #DISPLAY_NAME_MIN}-{@value #DISPLAY_NAME_MAX} characters after trimming,
 * free of control characters, invisible formatting characters and {@code '@'} (so it can never pass for an email
 * address), not reserved (see {@link ReservedProfileNames}), not equal &mdash; ignoring case &mdash; to any
 * <em>other</em> account's login name, and unique among all other accounts' display names, ignoring case, surrounding
 * whitespace and runs of whitespace (see {@link #displayNameKey}). Uniqueness is enforced here, in the application,
 * not by a database index: migrations run at container start and an index would fail on existing duplicates.</p>
 *
 * <p>A name that is <em>unchanged</em> is never re-checked, so a name that became reserved or duplicated after its owner
 * chose it cannot lock the owner out of saving the rest of their profile.</p>
 *
 * <p>Wiki names stay unique (the {@code users.wiki_name} column is UNIQUE); a write path that derives one passes it through
 * {@link #availableWikiName} so two different display names that derive the same wiki name still save.</p>
 */
public final class ProfileNameRules {

    private static final Logger LOG = LogManager.getLogger( ProfileNameRules.class );

    /** Shortest accepted display name, in characters after trimming. */
    public static final int DISPLAY_NAME_MIN = 2;

    /** Longest accepted display name, in characters after trimming. */
    public static final int DISPLAY_NAME_MAX = 64;

    /** Upper bound on numeric suffixes tried for a wiki name before giving up and keeping the preferred one. */
    private static final int MAX_WIKI_NAME_SUFFIX = 1000;

    private ProfileNameRules() { }

    /**
     * How a name broke the rules. {@link #INVALID} is malformed or reserved input (REST 400, SCIM {@code invalidValue}, and an
     * unusable IdP claim). {@link #TAKEN} is a collision with another account's login name or display name (REST 409, SCIM
     * {@code uniqueness}, and a claim that is worth numbering).
     */
    public enum Kind { INVALID, TAKEN }

    /** The first display-name rule a candidate broke, with a user-facing message. */
    public record NameViolation( Kind kind, String message ) {
        /** True for a collision with another account rather than malformed input. */
        public boolean isConflict() {
            return kind == Kind.TAKEN;
        }
    }

    static Optional< NameViolation > invalid( final String message ) {
        return Optional.of( new NameViolation( Kind.INVALID, message ) );
    }

    static Optional< NameViolation > taken( final String message ) {
        return Optional.of( new NameViolation( Kind.TAKEN, message ) );
    }

    /** Comparison key for display-name uniqueness: NFKC, whitespace runs collapsed, trimmed, lower-cased. */
    public static String displayNameKey( final String name ) {
        return name == null ? null
                : Normalizer.normalize( name, Normalizer.Form.NFKC ).replaceAll( "\\s+", " " ).trim().toLowerCase( Locale.ROOT );
    }

    // ------------------------------------------------------------------ display-name rules

    /**
     * Checks {@code candidate} against every display-name rule for the account {@code login}.
     *
     * @param login     the account being saved ({@code null} for a new account); its own login and name never clash
     * @param candidate the proposed display name
     * @return the first rule broken, else empty
     */
    public static Optional< NameViolation > displayNameViolation( final Engine engine, final UserDatabase db,
            final String login, final String candidate ) {
        return DisplayNameRules.violation( engine, db, login, candidate );
    }

    /** {@link #displayNameViolation} as a bare user-facing message. */
    public static Optional< String > displayNameError( final Engine engine, final UserDatabase db, final String login,
            final String candidate ) {
        return displayNameViolation( engine, db, login, candidate ).map( NameViolation::message );
    }

    /**
     * A display name for the account {@code login} that satisfies every rule, for SSO and SCIM provisioning where a conflict
     * must never fail the sign-in. {@code preferred} (trimmed) when it passes; if it is merely <em>taken</em> (another
     * account's display name or login) the lowest {@code preferred + " " + n}, n &ge; 2, that passes; if it is
     * null/blank/email-shaped/reserved/malformed, the lowest {@code "Member " + n}, n &ge; 1, that passes. Never the login or
     * email.
     */
    public static String availableDisplayName( final Engine engine, final UserDatabase db, final String login,
            final String preferred ) {
        return DisplayNameAllocator.allocate( engine, db, login, preferred );
    }

    // ------------------------------------------------------------------ change validation

    /**
     * Validates a change of display names for the account {@code login}: a full name that changed (and was supplied) must
     * satisfy {@link #displayNameViolation}; a wiki name that changed must not be reserved or be another account's login.
     */
    public static Optional< NameViolation > nameChangeViolation( final Engine engine, final UserDatabase db,
            final String login, final String oldFull, final String oldWiki, final String newFull, final String newWiki ) {
        return DisplayNameRules.changeViolation( engine, db, login, oldFull, oldWiki, newFull, newWiki );
    }

    /** {@link #nameChangeViolation} as a bare user-facing message (signature kept for existing callers). */
    public static Optional< String > nameChangeError( final Engine engine, final UserDatabase db, final String login,
            final String oldFull, final String oldWiki, final String newFull, final String newWiki ) {
        return nameChangeViolation( engine, db, login, oldFull, oldWiki, newFull, newWiki ).map( NameViolation::message );
    }

    /**
     * Validates a full-name change through the self-service or admin profile editor. The wiki name is derived from the full
     * name with whitespace removed, so it is checked too.
     */
    public static Optional< NameViolation > fullNameChangeViolation( final Engine engine, final UserDatabase db,
            final UserProfile current, final String newFull ) {
        return nameChangeViolation( engine, db, current.getLoginName(), current.getFullname(), current.getWikiName(),
                newFull, derivedWikiName( newFull ) );
    }

    /** {@link #fullNameChangeViolation} as a bare user-facing message (signature kept for existing callers). */
    public static Optional< String > fullNameChangeError( final Engine engine, final UserDatabase db,
            final UserProfile current, final String newFull ) {
        return fullNameChangeViolation( engine, db, current, newFull ).map( NameViolation::message );
    }

    // ------------------------------------------------------------------ wiki names (unchanged behaviour)

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
     * Sets {@code fullName} (trimmed) on {@code profile} and gives it the wiki name derived from it, numbered
     * when another account already holds that wiki name (see {@link #availableWikiName}). Call it
     * after {@link #nameChangeError} has accepted the name.
     */
    public static void applyFullName( final Engine engine, final UserDatabase db, final UserProfile profile,
            final String fullName ) {
        final String previousWiki = profile.getWikiName();
        profile.setFullname( fullName == null ? null : fullName.trim() );
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
}
