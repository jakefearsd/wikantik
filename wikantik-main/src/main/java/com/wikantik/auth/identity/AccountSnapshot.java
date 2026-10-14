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
package com.wikantik.auth.identity;

import com.wikantik.auth.ProfileNameRules;
import com.wikantik.auth.user.AccountSummary;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An immutable, in-memory index of the account table: login -> account, stored uid -> login, and display-name key -> login.
 * Display names are keyed by {@link ProfileNameRules#displayNameKey} — the very key the uniqueness rules compare (NFKC,
 * whitespace collapsed, trimmed, lower-cased) — so two names those rules call the same are ambiguous here too: they are left
 * out of the name index (the name resolves to nobody) and listed in {@code ambiguousNames}.
 *
 * @param expires        when the snapshot stops being trusted
 * @param generation     the {@link DefaultPublicIdentity} invalidation generation current when the load began; a snapshot from an
 *                       older generation is stale (it may predate a write) and is only kept as the fallback if a reload fails
 * @param byLogin        exact-case login -> account (a case variant of a login is a different, non-existent account)
 * @param loginByUid     stored uid -> login; an account with a NULL/blank uid is absent
 * @param loginByName    display-name key -> login
 * @param ambiguousNames the display-name keys held by more than one account
 */
record AccountSnapshot( Instant expires, long generation, Map< String, AccountSummary > byLogin,
        Map< String, String > loginByUid, Map< String, String > loginByName, Set< String > ambiguousNames ) {

    static AccountSnapshot build( final Instant expires, final long generation, final Collection< AccountSummary > accounts ) {
        final Map< String, AccountSummary > byLogin = new HashMap<>();
        final Map< String, String > loginByUid = new HashMap<>();
        final Map< String, String > loginByName = new HashMap<>();
        final Set< String > ambiguousNames = new HashSet<>();
        for ( final AccountSummary stored : accounts ) {
            if ( stored.login() == null ) {
                continue;
            }
            final AccountSummary account = withoutBlankUid( stored );
            byLogin.put( account.login(), account );
            if ( account.uid() != null ) {
                loginByUid.put( account.uid(), account.login() );
            }
            indexName( loginByName, ambiguousNames, account );
        }
        ambiguousNames.forEach( loginByName::remove );
        return new AccountSnapshot( expires, generation, Map.copyOf( byLogin ), Map.copyOf( loginByUid ),
                Map.copyOf( loginByName ), Set.copyOf( ambiguousNames ) );
    }

    private static AccountSummary withoutBlankUid( final AccountSummary account ) {
        final String uid = account.uid();
        return uid == null || !uid.isBlank() ? account : new AccountSummary( account.login(), account.fullName(), null );
    }

    private static void indexName( final Map< String, String > loginByName, final Set< String > ambiguousNames,
            final AccountSummary account ) {
        final String key = ProfileNameRules.displayNameKey( account.fullName() );
        if ( key == null || key.isEmpty() ) {
            return;   // no name, or blank once normalised (e.g. only no-break spaces): never a name anyone could ask for
        }
        if ( loginByName.putIfAbsent( key, account.login() ) != null ) {
            ambiguousNames.add( key );
        }
    }
}
