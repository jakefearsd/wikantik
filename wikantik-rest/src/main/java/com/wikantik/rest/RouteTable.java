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
package com.wikantik.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * First-match-wins route table shared by the admin REST servlets that
 * hand-roll a GoF Command/Registry dispatch idiom: {@link AdminKnowledgeResource},
 * {@link AdminHubDiscoveryResource}, and {@link AdminContentResource}. Each of
 * those independently declared its own record pairing a match test (a plain
 * exact-match string key, or a {@link Predicate} over the raw path) with an
 * action, plus its own lookup loop; this class is the one place that plumbing
 * lives now.
 *
 * <p>Generic over the action type {@code A} on purpose: the three servlets'
 * actions take different arguments — one closes over a resolved domain
 * service, one takes the matched raw path plus request/response, one takes
 * only request/response — so forcing them behind one shared functional
 * interface would need lossy adapters. Instead {@link #match(String)} only
 * resolves *which* action applies and hands it back for the caller to invoke;
 * each servlet keeps its own action type, its own invocation, and — because
 * it writes the "not found" response itself rather than this class doing it —
 * its own not-found status code and message wording exactly as before.
 *
 * <p><b>Serialization.</b> These servlets are {@link java.io.Serializable}
 * (that's inherited from {@code HttpServlet}), but a table built from method
 * references/lambdas that close over live subsystem accessors is not. Java
 * resets {@code transient} fields to {@code null} on deserialization and does
 * <em>not</em> re-run field initializers, so the table must be rebuilt lazily
 * on first use rather than assumed present:
 * <pre>{@code
 * private transient RouteTable<MyAction> routes;
 *
 * private RouteTable<MyAction> routes() {
 *     if ( routes == null ) {
 *         routes = RouteTable.<MyAction>builder()
 *             .exact( "foo", ( req, resp ) -> handleFoo( req, resp ) )
 *             .build();
 *     }
 *     return routes;
 * }
 * }</pre>
 */
final class RouteTable< A > {

    private final List< Entry< A > > entries;

    private RouteTable( final List< Entry< A > > entries ) {
        this.entries = entries;
    }

    static < A > Builder< A > builder() {
        return new Builder<>();
    }

    /**
     * Returns the action bound to the first route whose matcher accepts {@code key},
     * in declaration order, or {@link Optional#empty()} if none match.
     */
    Optional< A > match( final String key ) {
        for ( final Entry< A > entry : entries ) {
            if ( entry.matcher.test( key ) ) {
                return Optional.of( entry.action );
            }
        }
        return Optional.empty();
    }

    private record Entry< A >( Predicate< String > matcher, A action ) {}

    static final class Builder< A > {

        private final List< Entry< A > > entries = new ArrayList<>();

        /** Route matches when the dispatch key equals {@code literal} exactly. */
        Builder< A > exact( final String literal, final A action ) {
            entries.add( new Entry<>( literal::equals, action ) );
            return this;
        }

        /** Route matches when the dispatch key fully matches the compiled regex {@code pattern}. */
        Builder< A > regex( final String pattern, final A action ) {
            final Pattern compiled = Pattern.compile( pattern );
            entries.add( new Entry<>( s -> compiled.matcher( s ).matches(), action ) );
            return this;
        }

        /** Route matches on an arbitrary predicate (prefix/suffix combos, etc). */
        Builder< A > when( final Predicate< String > matcher, final A action ) {
            entries.add( new Entry<>( matcher, action ) );
            return this;
        }

        RouteTable< A > build() {
            return new RouteTable<>( List.copyOf( entries ) );
        }
    }
}
