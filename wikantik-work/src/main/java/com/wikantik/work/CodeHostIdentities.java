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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves code-host people to wiki users: by a username learned earlier on the same host, else by an email that is
 * not a GitHub noreply address; a match with a username teaches the username for next time. Memoised for one
 * delivery only (build a new instance per delivery), so a changed address or account is never remembered here.
 *
 * <p>Only a username the host itself asserts is ever learned: the adapters fill {@link CodeEvent.Person#username()}
 * from account fields (GitHub's {@code login}s, and a push commit's {@code author.username}, which GitHub sets only
 * when the commit address is verified on that account), never from a commit's free-text name or address. The first
 * mapping of a username wins; a later delivery that would map it to another account is logged at WARN and not applied.
 */
final class CodeHostIdentities {

    private static final Logger LOG = LogManager.getLogger( CodeHostIdentities.class );

    private static final String NOREPLY = "@users.noreply.github.com";

    private final WorkCodeHostStore store;
    private final WorkDirectory directory;
    private final Map< String, CodeHostWho > memo = new HashMap<>();

    CodeHostIdentities( final WorkCodeHostStore store, final WorkDirectory directory ) {
        this.store = store;
        this.directory = directory;
    }

    CodeHostWho resolve( final String host, final CodeEvent.Person p ) throws SQLException {
        if ( p == null ) {
            return CodeHostWho.UNKNOWN;
        }
        final String key = host + "\u0000" + p.username() + "\u0000" + p.name() + "\u0000" + p.email();
        final CodeHostWho known = memo.get( key );
        if ( known != null ) {
            return known;
        }
        final CodeHostWho made = lookup( host, p );
        memo.put( key, made );
        return made;
    }

    /**
     * Learns the username of a commit author the host matched to an account (its host-asserted username with an
     * address that names exactly one wiki account), and nothing else: a commit author is never who anything is
     * attributed to, because its address is whatever the committer typed.
     */
    void teach( final String host, final CodeEvent.Person p ) throws SQLException {
        if ( p != null && p.username() != null && !p.username().isBlank() && usable( p.email() ) ) {
            resolve( host, p );
        }
    }

    private CodeHostWho lookup( final String host, final CodeEvent.Person p ) throws SQLException {
        final String external = external( p );
        final boolean hasUsername = p.username() != null && !p.username().isBlank();
        if ( hasUsername ) {
            final Optional< String > learned = store.learned( host, p.username() );
            if ( learned.isPresent() ) {
                warnOnConflict( host, p, learned.get() );
                return new CodeHostWho( learned.get(), external );
            }
        }
        if ( !usable( p.email() ) ) {
            return new CodeHostWho( null, external );
        }
        final Optional< String > login = directory.loginForEmail( p.email().trim() );
        if ( login.isEmpty() ) {
            return new CodeHostWho( null, external );
        }
        if ( hasUsername ) {
            store.learn( host, p.username(), login.get() );
        }
        return new CodeHostWho( login.get(), external );
    }

    /** First match wins: an address that now names another account is reported, never applied. */
    private void warnOnConflict( final String host, final CodeEvent.Person p, final String learned ) throws SQLException {
        if ( !usable( p.email() ) ) {
            return;
        }
        final Optional< String > byEmail = directory.loginForEmail( p.email().trim() );
        if ( byEmail.isPresent() && !byEmail.get().equals( learned ) ) {
            LOG.warn( "Code-host username on host {} is already matched to another wiki account; the later match by "
                + "address is not applied", host );
        }
    }

    private static boolean usable( final String email ) {
        return email != null && !email.isBlank() && !email.toLowerCase( Locale.ROOT ).endsWith( NOREPLY );
    }

    private static String external( final CodeEvent.Person p ) {
        if ( p.username() != null && !p.username().isBlank() ) {
            return p.username();
        }
        return p.name() != null && !p.name().isBlank() ? p.name() : "unknown";
    }
}
