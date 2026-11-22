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

import com.wikantik.work.WorkScopes.Scope;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Project webhooks, reached through {@link WorkFeatures#webhooks()}. Managed by leads only (an agent never is), off
 * unless the wiki enables them. A webhook speaks for the lead who added or last saved it: it sends only while that
 * person is a lead (or a wiki admin), and its address and secret are never shown after creation.
 */
public final class WorkWebhooksService {

    private static final int MAX_URL = 2000;
    private static final int MAX_PER_PROJECT = 10;
    private static final String BAD_URL = "Give an https address with no user name or password in it.";

    private final WorkStore store;
    private final WorkScopes scopes;
    private final WorkAudit audit;
    private final WorkNotifyPorts ports;
    private final WebhookDelivery delivery;

    WorkWebhooksService( final WorkStore store, final WorkScopes scopes, final GuardedListener listener,
                         final WorkAudit audit, final WorkNotifyPorts ports, final Clock clock ) {
        this.store = store;
        this.scopes = scopes;
        this.audit = audit;
        this.ports = ports == null ? WorkNotifyPorts.NONE : ports;
        this.delivery = new WebhookDelivery( store, scopes, listener, this.ports, clock );
    }

    /** The project's webhooks; empty for anyone who is not a lead (or admin) of it. A stranger gets not found. */
    public List< WebhookView > list( final Actor a, final String projectKey ) throws SQLException {
        final Scope s = scopes.scope( a, projectKey );
        if ( !WorkPolicy.may( s.actor(), s.role(), Action.MANAGE_PROJECT, null ).allowed() ) {
            return List.of();
        }
        final List< WebhookView > out = new ArrayList<>();
        for ( final Webhook w : store.notifyStores().webhooks().forProject( s.project().id() ) ) {
            out.add( view( w ) );
        }
        return out;
    }

    /** Adds a webhook; the secret in the result is shown this once. */
    public WebhookCreated add( final Actor a, final String projectKey, final WebhookDraft d ) throws SQLException {
        final Scope s = gate( a, projectKey );
        final URI uri = parse( d.url() );
        ports.outbound().check( uri );
        final WorkWebhookStore webhooks = store.notifyStores().webhooks();
        if ( webhooks.forProject( s.project().id() ).size() >= MAX_PER_PROJECT ) {
            throw new WorkValidationException( "url", "A project has at most " + MAX_PER_PROJECT + " webhooks." );
        }
        final WebhookFormat format = d.format() == null ? WebhookFormat.GENERIC : WebhookFormat.fromWire( d.format() );
        final List< String > types = eventTypes( d.eventTypes() );
        final String secret = format == WebhookFormat.GENERIC ? WebhookPayloads.newSecret() : null;
        final Webhook made = webhooks.add( s.project(), uri, format, secret, types, a );
        audit.record( a.login(), "work.webhook.add", "work_webhook", s.project().key() + ":" + uri.getHost(), null );
        return new WebhookCreated( view( made ), secret );
    }

    /** Changes a webhook (an address is checked again); saving it makes the caller its owner. */
    public WebhookView update( final Actor a, final String projectKey, final long id, final WebhookPatch p )
            throws SQLException {
        final Scope s = gate( a, projectKey );
        final Webhook current = owned( s, id );
        URI uri = null;
        if ( p.url() != null ) {
            uri = parse( p.url() );
            ports.outbound().check( uri );
        }
        final List< String > types = p.eventTypes() == null ? null : eventTypes( p.eventTypes() );
        final Webhook changed = store.notifyStores().webhooks().update( current, uri, types, p.enabled(), a );
        audit.record( a.login(), "work.webhook.update", "work_webhook",
            s.project().key() + ":" + changed.url().getHost(), null );
        return view( changed );
    }

    public void remove( final Actor a, final String projectKey, final long id ) throws SQLException {
        final Scope s = gate( a, projectKey );
        final Webhook current = owned( s, id );
        store.notifyStores().webhooks().remove( current, a );
        audit.record( a.login(), "work.webhook.remove", "work_webhook",
            s.project().key() + ":" + current.url().getHost(), null );
    }

    /**
     * Sends what is due to every webhook, each in order, each failing on its own; run by the host's scheduler.
     *
     * @return the number of events posted
     */
    public int deliver( final Instant now ) throws SQLException {
        return delivery.deliver( now );
    }

    /** scope, then the off switch, the archive check and the lead check, in that order. */
    private Scope gate( final Actor a, final String projectKey ) throws SQLException {
        final Scope s = scopes.scope( a, projectKey );
        if ( !ports.settings().webhooksEnabled() ) {
            throw new WorkConflictException( "Webhooks are turned off on this wiki.", WorkConflictException.WEBHOOKS_OFF );
        }
        s.requireWritable();
        s.require( Action.MANAGE_PROJECT, null );
        return s;
    }

    private Webhook owned( final Scope s, final long id ) throws SQLException {
        return store.notifyStores().webhooks().byId( id ).filter( w -> w.projectId() == s.project().id() )
            .orElseThrow( () -> new WorkNotFoundException( WorkScopes.NOT_FOUND ) );
    }

    private WebhookView view( final Webhook w ) {
        return new WebhookView( w.id(), w.format().wire(), w.target(), w.eventTypes(), scopes.displayName( w.owner() ),
            w.createdAt(), w.lastDeliveredAt(), w.failingSince(), w.lastError(), w.disabledAt(),
            !delivery.ownerMaySend( w ) );
    }

    /** An absolute https URI with a host, no user-info, within the length limit; the text of a bad one is never echoed. */
    private static URI parse( final String raw ) {
        final String text = raw == null ? "" : raw.strip();
        if ( text.isEmpty() || text.length() > MAX_URL ) {
            throw new WorkValidationException( "url", BAD_URL );
        }
        final URI uri;
        try {
            uri = new URI( text );
        } catch ( final URISyntaxException e ) {
            throw new WorkValidationException( "url", BAD_URL );
        }
        if ( !"https".equals( uri.getScheme() == null ? null : uri.getScheme().toLowerCase( Locale.ROOT ) )
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawAuthority() == null ) {
            throw new WorkValidationException( "url", BAD_URL );
        }
        return uri;
    }

    private static List< String > eventTypes( final List< String > raw ) {
        final List< String > out = new ArrayList<>();
        for ( final String t : raw == null ? List.< String >of() : raw ) {
            if ( t == null || !WebhookPayloads.SENDABLE.contains( t.strip() ) ) {
                throw new WorkValidationException( "eventTypes", t + " is not an event a webhook can send." );
            }
            if ( !out.contains( t.strip() ) ) {
                out.add( t.strip() );
            }
        }
        return out;
    }
}
