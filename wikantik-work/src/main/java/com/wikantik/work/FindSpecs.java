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

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns what a caller typed into what the store runs: the projects searched are the caller's visible projects
 * (resolved by {@link WorkStanding}, passed in) narrowed to those named, so a project the caller cannot see is
 * indistinguishable from one that does not exist. A name that resolves to nobody matches nothing; the answer never
 * says which names exist.
 */
final class FindSpecs {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    /** A product id no product has: the spec for a product key that names nothing. */
    private static final long NO_PRODUCT = -1L;

    /** A person reference resolved: a login, an agent id, both null for no filter; {@code null} for unresolvable. */
    private record Who( String login, Long agentId ) {
        static final Who ANY = new Who( null, null );
    }

    private FindSpecs() {
    }

    /** The page size asked for: 50 when unset, at most 200. */
    static int limit( final FindFilter f ) {
        return f.limit() <= 0 ? DEFAULT_LIMIT : Math.min( f.limit(), MAX_LIMIT );
    }

    /**
     * The spec for this caller, or empty when the search can match nothing.
     *
     * @param visible the projects the caller can see, from {@code WorkStanding.visibleProjects}
     * @throws WorkValidationException on field {@code cursor}, the same for every caller
     */
    static Optional< FindSpec > resolve( final Actor actor, final FindFilter f, final List< Project > visible,
                                         final List< Product > products, final WorkPeople people ) {
        final FindCursor after = f.cursor() == null ? null : FindCursor.decode( f.cursor(), f.sort() );
        final Set< Long > ids = covered( f, visible );
        final Who assignee = who( actor, f.assignee(), people );
        final Who reporter = who( actor, f.reporter(), people );
        final Long product = product( f.productKey(), products );
        final String watcher = watcher( actor, f );
        if ( ids.isEmpty() || assignee == null || reporter == null || ( product != null && product == NO_PRODUCT )
            || ( f.watching() && watcher == null ) ) {
            return Optional.empty();
        }
        return Optional.of( new FindSpec( ids, f.statuses(), f.levels(), f.type(), f.labels(), f.priorities(),
            assignee.login(), assignee.agentId(), blankToNull( f.queue() ), reporter.login(), reporter.agentId(),
            product, f.sprint(), watcher, blankToNull( f.text() ), f.dueBefore(), f.updatedSince(), f.sort(),
            limit( f ) + 1, after ) );
    }

    /** The visible projects whose key is named (any case), or all of them when none is. */
    private static Set< Long > covered( final FindFilter f, final List< Project > visible ) {
        final Set< String > named = f.projectKeys().stream().map( k -> k.trim().toUpperCase( Locale.ROOT ) )
            .collect( Collectors.toSet() );
        return visible.stream().filter( p -> named.isEmpty() || named.contains( p.key().toUpperCase( Locale.ROOT ) ) )
            .map( Project::id ).collect( Collectors.toSet() );
    }

    /** {@code me} is the caller (an agent: by id); a name is a display name, or a login for a wiki admin. */
    private static Who who( final Actor actor, final String ref, final WorkPeople people ) {
        if ( ref == null || ref.isBlank() ) {
            return Who.ANY;
        }
        if ( FindFilter.ME.equals( ref.trim() ) ) {
            return actor.kind() == Actor.Kind.AGENT ? new Who( null, actor.agentId() ) : new Who( actor.login(), null );
        }
        return people.login( actor, ref ).map( l -> new Who( l, null ) ).orElse( null );
    }

    /** The product's id; null when none is named, {@link #NO_PRODUCT} when the key names no product. */
    private static Long product( final String key, final List< Product > products ) {
        if ( key == null || key.isBlank() ) {
            return null;
        }
        return products.stream().filter( p -> p.key().equals( key.trim() ) ).map( Product::id ).findFirst()
            .orElse( NO_PRODUCT );
    }

    /** The login whose watches count; null for an agent (agents do not watch) or when not asked. */
    private static String watcher( final Actor actor, final FindFilter f ) {
        return f.watching() && actor.kind() != Actor.Kind.AGENT ? actor.login() : null;
    }

    private static String blankToNull( final String s ) {
        return s == null || s.isBlank() ? null : s;
    }
}
