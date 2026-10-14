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

/**
 * Presentation helpers for surfaces that show who wrote something: they turn the principal a page version,
 * verification stamp or frontmatter field carries into the name shown to readers, through
 * {@link PublicIdentity}. Pure functions; a {@code null} identity (unit-test wiring only) means "no account is
 * known", so values pass through unchanged.
 */
public final class IdentityDisplay {

    /** Frontmatter keys whose value names a principal rather than carrying prose. */
    public static final List< String > PRINCIPAL_KEYS = List.of( "author", "verified_by" );

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
     * The display form of a value an editor <em>typed</em> (frontmatter {@code author:} / {@code verified_by:}):
     * replaced by the account's display name only when the text is an existing login, otherwise shown exactly
     * as written.
     */
    public static String ofLoginOrText( final PublicIdentity identity, final String value ) {
        if ( identity == null || value == null || value.isBlank() ) {
            return value;
        }
        return identity.userIdForLogin( value ).isPresent() ? identity.displayName( value ) : value;
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
