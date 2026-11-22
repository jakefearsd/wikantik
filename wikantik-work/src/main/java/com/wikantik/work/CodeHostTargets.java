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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which items an event is about: the item keys it names that exist, belong to this product and can take a change. A
 * key that names nothing usable is counted, never logged by key.
 */
final class CodeHostTargets {

    /** An item the event names, with the policy resolved for it now. */
    record Target( Item item, EffectivePolicy policy, boolean closed ) {
    }

    /** The usable items, and how many named keys were not usable. */
    record Targets( List< Target > items, int ignoredKeys ) {

        static final Targets NONE = new Targets( List.of(), 0 );
    }

    private static final int MAX_KEYS = 10;

    private final WorkStore store;

    CodeHostTargets( final WorkStore store ) {
        this.store = store;
    }

    /** Whether the delivery's repository is the one the product names; true when either side names none. */
    static boolean repoMatches( final HookDelivery d, final CodeEvent e ) {
        final String url = d.repoUrl();
        final String repo = e.repo();
        if ( url == null || url.isBlank() || repo == null || repo.isBlank() ) {
            return true;
        }
        String trimmed = url.trim().toLowerCase( Locale.ROOT );
        while ( trimmed.endsWith( "/" ) ) {
            trimmed = trimmed.substring( 0, trimmed.length() - 1 );
        }
        final String wanted = "/" + repo.trim().toLowerCase( Locale.ROOT );
        return trimmed.endsWith( wanted ) || trimmed.endsWith( wanted + ".git" );
    }

    /** The branch the delivery treats as the default: the product's, else the event's own. */
    static String defaultBranch( final HookDelivery d, final CodeEvent e ) {
        return d.defaultBranch() != null && !d.defaultBranch().isBlank() ? d.defaultBranch() : e.defaultBranch();
    }

    static boolean isDefaultBranchPush( final HookDelivery d, final CodeEvent e ) {
        return e.kind() == CodeEvent.Kind.PUSH && e.branch() != null && e.branch().equals( defaultBranch( d, e ) );
    }

    /** Keys named by the event: branch and title for review events and branch pushes, distinct commits on the default branch. */
    static Set< String > keys( final HookDelivery d, final CodeEvent e ) {
        final Set< String > found = new LinkedHashSet<>();
        if ( isDefaultBranchPush( d, e ) ) {
            for ( final CodeEvent.Commit c : e.commits() ) {
                if ( c.distinct() ) {
                    found.addAll( ItemKeyScan.keys( c.message() ) );
                }
            }
        } else {
            found.addAll( ItemKeyScan.keys( e.branch() ) );
            if ( e.review() != null ) {
                found.addAll( ItemKeyScan.keys( e.review().title() ) );
            }
        }
        final List< String > capped = new ArrayList<>( found );
        return new LinkedHashSet<>( capped.subList( 0, Math.min( MAX_KEYS, capped.size() ) ) );
    }

    Targets items( final HookDelivery d, final CodeEvent e ) throws SQLException {
        if ( !repoMatches( d, e ) ) {
            return Targets.NONE;
        }
        final List< Target > out = new ArrayList<>();
        int ignored = 0;
        for ( final String key : keys( d, e ) ) {
            final Target t = target( d, key );
            if ( t == null ) {
                ignored++;
            } else {
                out.add( t );
            }
        }
        return new Targets( out, ignored );
    }

    private Target target( final HookDelivery d, final String key ) throws SQLException {
        final Item item = store.item( key ).orElse( null );
        if ( item == null || item.level() == WorkLevel.EPIC ) {
            return null;
        }
        final PolicyChain chain = store.policyChain( item.id() );
        final Product product = chain.productOrNull();
        if ( product == null || product.id() != d.productId() || chain.project().archivedAt() != null ) {
            return null;
        }
        return new Target( item, WorkAutonomy.resolve( chain ), SqlValues.isClosed( item.status() ) );
    }
}
