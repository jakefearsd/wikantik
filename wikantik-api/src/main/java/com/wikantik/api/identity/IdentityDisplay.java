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
package com.wikantik.api.identity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Presentation helpers for surfaces that show who wrote something: they turn the principal a page version,
 * verification stamp, change note or frontmatter field carries into the name shown to readers, through
 * {@link PublicIdentity}. Pure functions; a {@code null} identity (unit-test wiring only) means "no account is
 * known", so values pass through unchanged.
 */
public final class IdentityDisplay {

    /** Frontmatter keys whose value names a principal rather than carrying prose. */
    public static final List< String > PRINCIPAL_KEYS = List.of( "author", "verified_by" );

    /** How the change note written by {@code mark_page_verified} starts; the verifier's login follows. */
    private static final String VERIFIED_BY = "verified by ";

    /** A verifier (a run of non-space characters) and whatever follows it. */
    private static final Pattern FIRST_WORD = Pattern.compile( "(\\S+)(.*)", Pattern.DOTALL );

    private IdentityDisplay() {
    }

    /**
     * The display form of a <em>stored</em> author (a version author, a recent-change author).
     * {@code null} stays {@code null} so an absent author stays absent from the JSON; every other value goes
     * through {@link PublicIdentity#displayName}: a login becomes that account's display name, a legacy full name
     * or agent label is unchanged, an email-shaped value that is no account becomes
     * {@link PublicIdentity#UNKNOWN_CONTRIBUTOR}.
     */
    public static String ofAuthor( final PublicIdentity identity, final String stored ) {
        if ( identity == null || stored == null ) {
            return stored;
        }
        return identity.displayName( stored );
    }

    /**
     * The display form of a value in a principal field an editor may type or a tool writes (frontmatter
     * {@code author:} / {@code verified_by:}): an existing login becomes the account's display name, whatever its
     * stored uid; an email-shaped value that is no account becomes {@link PublicIdentity#UNKNOWN_CONTRIBUTOR} (a
     * deleted SSO account's login must not reappear); any other text is shown exactly as written.
     *
     * <p>Login-ness is the account lookup behind {@link PublicIdentity#displayName} itself: the value is shown as
     * written exactly when that lookup leaves it unchanged. (When no account can be read at all the service answers
     * with its neutral fallback, so this fails closed.)</p>
     */
    public static String ofLoginOrText( final PublicIdentity identity, final String value ) {
        if ( identity == null || value == null || value.isBlank() ) {
            return value;
        }
        final String shown = identity.displayName( value );
        return shown.equals( value.trim() ) ? value : shown;
    }

    /**
     * The display form of a page version's change note. Only the note {@code mark_page_verified} writes,
     * {@code "verified by <login>"} (optionally followed by more text), is rewritten: its verifier goes through
     * {@link #ofLoginOrText}, so a login (an email address for an SSO administrator) becomes the display name and an
     * email-shaped value that is no account becomes {@link PublicIdentity#UNKNOWN_CONTRIBUTOR}. Every other note is
     * free text and is returned as written; {@code null} stays {@code null}.
     *
     * <p>The verifier is the whole rest of the note when that is a login (a login may hold spaces), else its first
     * word.</p>
     */
    public static String changeNote( final PublicIdentity identity, final String note ) {
        if ( identity == null || note == null || !note.startsWith( VERIFIED_BY ) ) {
            return note;
        }
        // Extra whitespace before the verifier must not let a login slip past (the tool did not trim its argument).
        final String rest = note.substring( VERIFIED_BY.length() ).stripLeading();
        if ( rest.indexOf( '@' ) < 0 ) {
            final String whole = ofLoginOrText( identity, rest );
            if ( !whole.equals( rest ) ) {
                return VERIFIED_BY + whole;
            }
        }
        final Matcher verifier = FIRST_WORD.matcher( rest );
        if ( !verifier.matches() ) {
            return note;
        }
        final String shown = ofLoginOrText( identity, verifier.group( 1 ) );
        return shown.equals( verifier.group( 1 ) ) ? note : VERIFIED_BY + shown + verifier.group( 2 );
    }

    /**
     * A copy of {@code metadata} whose {@link #PRINCIPAL_KEYS} string values went through
     * {@link #ofLoginOrText}; the same instance when nothing changed. Key order is preserved and the argument is
     * never mutated.
     */
    public static Map< String, Object > frontmatter( final PublicIdentity identity, final Map< String, Object > metadata ) {
        if ( identity == null || metadata == null || metadata.isEmpty() ) {
            return metadata;
        }
        Map< String, Object > shown = null;
        for ( final String key : PRINCIPAL_KEYS ) {
            if ( metadata.get( key ) instanceof String text ) {
                final String resolved = ofLoginOrText( identity, text );
                if ( !resolved.equals( text ) ) {
                    if ( shown == null ) {
                        shown = new LinkedHashMap<>( metadata );
                    }
                    shown.put( key, resolved );
                }
            }
        }
        return shown == null ? metadata : shown;
    }
}
