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
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The code-host integration, reached through {@link WorkFeatures#codeHost()}. The wiki only receives: a product's
 * hook has a secret, a delivery that proves it knows it becomes a {@link HookDelivery}, and only that can be applied.
 * Nothing here calls the code host, and nothing here moves an item's status.
 */
public final class WorkCodeHostService {

    private static final Set< String > HOSTS = Set.of( "github", "generic" );
    /** A product id no product has (ids are positive): the hook lookup of an unknown product. */
    private static final long NO_PRODUCT = -1L;

    private final WorkStore store;
    private final WorkScopes scopes;
    private final Clock clock;
    private final WorkAudit audit;
    private final WorkDirectory directory;
    private final GuardedListener listener;

    /** What one delivery did, summed over its events and the items they name. */
    public record Applied( int applied, int duplicate, int ignored ) {
    }

    WorkCodeHostService( final WorkStore store, final WorkScopes scopes, final Clock clock, final FeatureDeps deps ) {
        this.store = store;
        this.scopes = scopes;
        this.clock = clock;
        this.audit = deps.audit();
        this.directory = deps.directory();
        this.listener = deps.listener();
    }

    /**
     * Applies the events of a verified delivery: notes that the hook heard from the host, then writes each event's
     * effect on the change records of the items it names, one transaction per item. Content it cannot use is counted
     * as ignored, never thrown. A database or directory failure propagates ({@code SQLException},
     * {@link WorkUnavailableException}) so the delivery can be redelivered; redelivery is safe (idempotent), and
     * deliveries arriving in any order converge. Nothing here moves an item's status.
     */
    public Applied apply( final HookDelivery d, final List< CodeEvent > events ) throws SQLException {
        store.notifyStores().codeHost().received( d.productId(), clock.instant() );
        final CodeHostApplier applier = new CodeHostApplier( store, directory, clock, Actor.system( d.host() ) );
        int applied = 0;
        int duplicate = 0;
        int ignored = 0;
        for ( final CodeEvent e : events == null ? List.< CodeEvent >of() : events ) {
            final CodeHostApplier.Counts c = applier.apply( d, e );
            applied += c.applied();
            duplicate += c.duplicate();
            ignored += c.ignored();
            listener.codeHost( e.kind().name().toLowerCase( Locale.ROOT ), c.overall().wire() );
        }
        return new Applied( applied, duplicate, ignored );
    }

    /**
     * Counts one refused delivery, as {@code delivery/rejected} and by its reason; records nothing else. For the REST
     * hook.
     */
    public void rejected( final HookRejection reason ) {
        listener.codeHost( "delivery", "rejected" );
        listener.codeHostRejected( reason );
    }

    /**
     * Verifies a delivery. Empty for an unknown or archived product, no hook, a disabled hook, a host other than
     * {@code github} or {@code generic}, or a missing or wrong signature; every call computes one HMAC, so the time
     * taken does not tell an unknown product from a wrong signature. Never throws for a bad signature.
     */
    public Optional< HookDelivery > authenticate( final String productKey, final String host, final byte[] body,
                                                  final String signatureHeader ) throws SQLException {
        final Product product = productKey == null ? null : store.product( productKey ).orElse( null );
        // the hook is looked up even for an unknown product (under an id no product has), so an unknown product and a
        // wrong signature cost the same two reads and one HMAC
        final Optional< WorkCodeHostStore.Hook > found = store.notifyStores().codeHost()
            .hook( product == null ? NO_PRODUCT : product.id() );
        final WorkCodeHostStore.Hook hook = product == null || product.archivedAt() != null ? null
            : found.filter( WorkCodeHostStore.Hook::live ).orElse( null );
        final String secret = hook == null ? CodeHostSecrets.DUMMY_KEY : hook.secret();
        final boolean signed = CodeHostSecrets.matches( secret, body, signatureHeader );
        final String wireHost = host == null ? "" : host.toLowerCase( Locale.ROOT );
        if ( hook == null || !signed || !HOSTS.contains( wireHost ) ) {
            return Optional.empty();
        }
        return Optional.of( new HookDelivery( product.id(), product.key(), wireHost, product.repoUrl(),
            product.defaultBranch() ) );
    }

    /** Turns the hook on, or replaces its secret; the secret in the result is shown this once. Wiki admins only. */
    public HookCreated enableHook( final Actor a, final String productKey ) throws SQLException {
        scopes.requireInstance( a, Action.MANAGE_INSTANCE );
        final Product product = writableProduct( productKey );
        final String secret = CodeHostSecrets.newSecret();
        final boolean rotated = store.notifyStores().codeHost().putHook( product, secret, clock.instant(), a );
        audit.record( a.login(), rotated ? "work.product.hook.rotate" : "work.product.hook.enable", "work_product",
            product.key(), null );
        return new HookCreated( status( product.key(), store.notifyStores().codeHost().hook( product.id() ).orElse( null ) ),
            secret );
    }

    /** Turns the hook off; the secret stays stored but nothing verifies against it. Wiki admins only. */
    public void disableHook( final Actor a, final String productKey ) throws SQLException {
        scopes.requireInstance( a, Action.MANAGE_INSTANCE );
        final Product product = store.product( productKey )
            .orElseThrow( () -> new WorkNotFoundException( WorkScopes.NOT_FOUND ) );
        if ( store.notifyStores().codeHost().disableHook( product, clock.instant(), a ) ) {
            audit.record( a.login(), "work.product.hook.disable", "work_product", product.key(), null );
        }
    }

    /** Every product's hook status, by product key; wiki admins only (others are denied). */
    public Map< String, HookStatus > hooks( final Actor a ) throws SQLException {
        scopes.requireInstance( a, Action.MANAGE_INSTANCE );
        final Map< Long, WorkCodeHostStore.Hook > hooks = store.notifyStores().codeHost().hooks();
        final Map< String, HookStatus > out = new LinkedHashMap<>();
        for ( final Product p : store.products() ) {
            final WorkCodeHostStore.Hook h = hooks.get( p.id() );
            if ( h != null ) {
                out.put( p.key(), status( p.key(), h ) );
            }
        }
        return out;
    }

    private Product writableProduct( final String productKey ) throws SQLException {
        final Product product = store.product( productKey )
            .orElseThrow( () -> new WorkNotFoundException( WorkScopes.NOT_FOUND ) );
        if ( product.archivedAt() != null ) {
            throw new WorkConflictException( "This product is archived.", WorkConflictException.ARCHIVED );
        }
        return product;
    }

    private static HookStatus status( final String productKey, final WorkCodeHostStore.Hook h ) {
        return new HookStatus( productKey, h != null && h.live(), h == null ? null : h.createdAt(),
            h == null ? null : h.lastReceivedAt() );
    }
}
