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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses mentions out of a comment body.
 *
 *  <p>Two forms are recognised:</p>
 *  <ul>
 *    <li><b>Token</b> — {@code @[Display Name](user:<uid>)}, written by the mention picker.
 *        The display text is a snapshot taken when the comment was written; the {@code uid}
 *        ({@code users.uid}) is the key, and is what {@link #extract} returns.</li>
 *    <li><b>Legacy</b> — {@code @<login>}. The regex requires a non-word character before the
 *        {@code @} (so {@code alice@example.com} doesn't mention {@code alice}). A login that is an
 *        email address can never match this form; it is mentionable only through a token.</li>
 *  </ul>
 *  Comment bodies are plain text, not Markdown, so a token inside backticks still counts. */
public final class MentionExtractor {

    // @[            — literal opener
    // ([^\]\n]{1,64}) — display-name snapshot: 1-64 chars, no ']' and no newline
    // ](user:       — literal separator
    // ([A-Za-z0-9-]{1,64}) — users.uid (UUIDs, legacy negative numbers and slugs like
    //                 "agents-service-account" all fit)
    // )             — literal closer
    private static final Pattern TOKEN =
            Pattern.compile( "@\\[([^\\]\\n]{1,64})\\]\\(user:([A-Za-z0-9-]{1,64})\\)" );

    // (?<=^|\W)  — preceded by start-of-string or a non-word character
    // @          — literal at-sign
    // ([A-Za-z0-9._-]+) — captured login (allows letters, digits, '.', '_', '-')
    private static final Pattern PATTERN =
            Pattern.compile( "(?<=^|\\W)@([A-Za-z0-9._-]+)" );

    private MentionExtractor() {}

    /** What a comment body mentions: {@code userIds} from tokens, {@code legacyLogins} from
     *  {@code @login} text. Neither is checked against the user database. */
    public record Mentions( Set< String > userIds, Set< String > legacyLogins ) {

        /** No mentions at all. */
        public static final Mentions NONE = new Mentions( Set.of(), Set.of() );

        public Mentions {
            userIds = Set.copyOf( userIds );
            legacyLogins = Set.copyOf( legacyLogins );
        }

        public boolean isEmpty() {
            return userIds.isEmpty() && legacyLogins.isEmpty();
        }
    }

    /** Every mention in {@code body}: token uids and legacy {@code @login} names. The legacy scan
     *  runs on the body with the tokens removed, so a snapshot such as {@code @[Bob @carol](user:u2)}
     *  does not mention {@code carol}. */
    public static Mentions extract( final String body ) {
        if ( body == null || body.isEmpty() ) return Mentions.NONE;
        final Set< String > userIds = new LinkedHashSet<>();
        final Matcher tokens = TOKEN.matcher( body );
        while ( tokens.find() ) userIds.add( tokens.group( 2 ) );
        final String withoutTokens = userIds.isEmpty() ? body : TOKEN.matcher( body ).replaceAll( " " );
        return new Mentions( userIds, parse( withoutTokens ) );
    }

    /** Distinct candidate logins from legacy {@code @login} text in {@code body} (tokens are not
     *  recognised here — use {@link #extract}). */
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
