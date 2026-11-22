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
package com.wikantik.work;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * People as the work module names them: a reference a caller sends (a display name; a login too from a wiki admin)
 * resolved to the login that is stored, and the mentions in a comment.
 *
 * <p><b>Mentions.</b> A comment names a person with the wiki's mention token {@code @[Display Name](user:<id>)} (the
 * comment composer's picker inserts it; the id is the key, the name a snapshot), or with a bare {@code @name} whose
 * name is a one-word display name. A wiki admin's bare {@code @login} also resolves. Before a comment is stored every
 * bare mention that resolved is rewritten to the token, so stored text never carries a login a mention put there.
 * At most {@value #MAX_MENTIONS} distinct mentions per comment are resolved; any further ones stay plain text.
 */
final class WorkPeople {

    static final int MAX_MENTIONS = 50;
    private static final String NOT_A_MEMBER = "The assignee is not a member of this project.";

    /** Same shape as the page comments' token (wikantik-main {@code MentionExtractor.TOKEN}). */
    private static final Pattern TOKEN = Pattern.compile( "@\\[([^\\]\\n]{1,64})\\]\\(user:([A-Za-z0-9-]{1,64})\\)" );
    private static final Pattern BARE = Pattern.compile( "(?<![\\w@.])@([A-Za-z0-9][A-Za-z0-9._-]*)" );
    private static final Pattern ANY = Pattern.compile( TOKEN.pattern() + "|" + BARE.pattern() );
    private static final Pattern TRAILING = Pattern.compile( "[._-]+$" );
    private static final Pattern MARKDOWN_SPECIAL = Pattern.compile( "([\\\\`*_{}\\[\\]()#+\\-.!|<>~$&])" );
    private static final int SNAPSHOT_MAX = 64;

    /** A comment body ready to store, and the logins it mentions (distinct, in order). */
    record Mentions( String body, List< String > logins ) {
    }

    /** A comment body as Markdown with every mention shown as {@code @Display Name}, and those names. */
    record Display( String markdown, List< String > names ) {
    }

    private final WorkDirectory directory;

    WorkPeople( final WorkDirectory directory ) {
        this.directory = directory;
    }

    /** The members of a directory group; empty when unknown. */
    Set< String > groupMembers( final String group ) {
        final Set< String > members = directory.groupMembers( group );
        return members == null ? Set.of() : members;
    }

    /** The login an assignee reference names; one "not a member" answer when it names nobody. */
    String assigneeLogin( final Actor actor, final String reference ) {
        return login( actor, reference )
            .orElseThrow( () -> new WorkValidationException( "assignee", NOT_A_MEMBER ) );
    }

    /** The changes with a text {@code assignee} reference replaced by the login it names. */
    Map< String, Object > withAssigneeLogin( final Actor actor, final Map< String, Object > changes ) {
        if ( !( changes.get( "assignee" ) instanceof String ref ) || ref.isBlank() ) {
            return changes;
        }
        final Map< String, Object > out = new LinkedHashMap<>( changes );
        out.put( "assignee", assigneeLogin( actor, ref ) );
        return out;
    }

    /**
     * The login a person reference names: a display name, for anyone; a login, for a wiki admin only (anyone else
     * could otherwise probe which logins exist). Empty when it names nobody.
     */
    Optional< String > login( final Actor actor, final String reference ) {
        if ( reference == null || reference.isBlank() ) {
            return Optional.empty();
        }
        final String ref = reference.trim();
        final Optional< String > byName = directory.loginForDisplayName( ref );
        if ( byName.isPresent() ) {
            return byName;
        }
        return actor.instanceAdmin() && directory.exists( ref ) ? Optional.of( ref ) : Optional.empty();
    }

    /** The body as it is to be stored, and who it mentions; see the class comment. */
    Mentions mentions( final Actor actor, final String body ) {
        final Map< String, Optional< String > > seen = new LinkedHashMap<>();
        final Set< String > logins = new LinkedHashSet<>();
        final StringBuilder out = new StringBuilder();
        final Matcher m = ANY.matcher( body );
        int last = 0;
        while ( m.find() ) {
            final boolean token = m.group( 2 ) != null;
            final String raw = token ? m.group( 2 ) : m.group( 3 );
            final String name = token ? raw : TRAILING.matcher( raw ).replaceAll( "" );
            final String key = ( token ? "id:" : "name:" ) + name;
            if ( name.isEmpty() || ( !seen.containsKey( key ) && seen.size() >= MAX_MENTIONS ) ) {
                continue;   // over the cap, or nothing left once trailing punctuation is dropped: plain text
            }
            final Optional< String > login = seen.computeIfAbsent( key,
                k -> token ? directory.loginForMentionId( name ) : login( actor, name ) );
            if ( login.isEmpty() ) {
                continue;
            }
            logins.add( login.get() );
            if ( !token ) {
                out.append( body, last, m.start() ).append( tokenFor( login.get() ) )
                    .append( raw.substring( name.length() ) );
                last = m.end();
            }
        }
        out.append( body.substring( last ) );
        return new Mentions( out.toString(), new ArrayList<>( logins ) );
    }

    /** The canonical token for a login, or {@code @Display Name} when its account has no mention id. */
    private String tokenFor( final String login ) {
        final String name = snapshot( directory.displayName( login ) );
        return directory.mentionId( login ).map( id -> "@[" + name + "](user:" + id + ")" ).orElse( "@" + name );
    }

    private static String snapshot( final String displayName ) {
        final StringBuilder b = new StringBuilder();
        for ( final char ch : String.valueOf( displayName ).toCharArray() ) {
            if ( ch != '[' && ch != ']' && !Character.isISOControl( ch ) && b.length() < SNAPSHOT_MAX ) {
                b.append( ch );
            }
        }
        final String s = b.toString().trim();
        return s.isEmpty() ? "user" : s;
    }

    /**
     * The stored body as Markdown for display: each token becomes {@code @} and the person's current display name
     * (the token's snapshot when the id no longer resolves), escaped so a name can never become markup.
     */
    Display display( final String body ) {
        if ( body == null || body.isEmpty() ) {
            return new Display( "", List.of() );
        }
        final Map< String, String > names = new LinkedHashMap<>();
        final StringBuilder out = new StringBuilder();
        final Matcher m = TOKEN.matcher( body );
        int last = 0;
        while ( m.find() ) {
            final String id = m.group( 2 );
            if ( !names.containsKey( id ) && names.size() >= MAX_MENTIONS ) {
                continue;
            }
            final String name = names.computeIfAbsent( id, k -> directory.loginForMentionId( k )
                .map( directory::displayName ).orElse( m.group( 1 ) ) );
            out.append( body, last, m.start() ).append( '@' ).append( MARKDOWN_SPECIAL.matcher( name )
                .replaceAll( "\\\\$1" ) );
            last = m.end();
        }
        out.append( body.substring( last ) );
        return new Display( out.toString(), new ArrayList<>( new LinkedHashSet<>( names.values() ) ) );
    }
}
