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

package com.wikantik.comments.mentions;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses {@code @<login>} tokens out of a comment body and resolves them
 *  against the user database. The regex requires a non-word-character before
 *  the {@code @} (so {@code alice@example.com} doesn't mention {@code alice}). */
public final class MentionExtractor {

    // (?<=^|\W)  — preceded by start-of-string or a non-word character
    // @          — literal at-sign
    // ([A-Za-z0-9._-]+) — captured login (allows letters, digits, '.', '_', '-')
    private static final Pattern PATTERN =
            Pattern.compile( "(?<=^|\\W)@([A-Za-z0-9._-]+)" );

    private MentionExtractor() {}

    /** Distinct candidate logins parsed from {@code body}. */
    public static Set< String > parse( final String body ) {
        if ( body == null || body.isEmpty() ) return Set.of();
        final Set< String > out = new HashSet<>();
        final Matcher m = PATTERN.matcher( body );
        while ( m.find() ) {
            String login = m.group( 1 );
            // Strip trailing punctuation (.!?,;: etc)
            login = login.replaceAll( "[.!?,;:]+$", "" );
            if ( !login.isEmpty() ) out.add( login );
        }
        return out;
    }

    /** Intersection of {@code candidates} with users that the predicate
     *  considers existing. */
    public static Set< String > resolve( final Set< String > candidates,
                                         final Predicate< String > userExists ) {
        final Set< String > out = new HashSet<>();
        for ( final String c : candidates ) if ( userExists.test( c ) ) out.add( c );
        return out;
    }
}
